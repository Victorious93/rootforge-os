package ai.rootforge.os.adb

import ai.rootforge.os.adb.AdbProtocol.A_AUTH
import ai.rootforge.os.adb.AdbProtocol.A_CLSE
import ai.rootforge.os.adb.AdbProtocol.A_CNXN
import ai.rootforge.os.adb.AdbProtocol.A_OKAY
import ai.rootforge.os.adb.AdbProtocol.A_OPEN
import ai.rootforge.os.adb.AdbProtocol.A_WRTE
import ai.rootforge.os.core.leBuffer
import ai.rootforge.os.core.asLe
import ai.rootforge.os.usb.BulkTransport
import ai.rootforge.os.usb.UsbTimeoutException
import ai.rootforge.os.usb.readFully
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

data class ShellResult(val output: String, val exitCode: Int?)

/**
 * A pure-Kotlin ADB host over a [BulkTransport]. One stream is active at a
 * time (all public operations are serialised), which is all a toolkit that
 * runs one action at a time needs and keeps the flow control easy to verify.
 *
 * Deliberately not implemented: shell_v2 framing (exit codes are recovered
 * with an echoed marker instead), `adb forward`, and compressed sync.
 */
class AdbConnection(
    private val transport: BulkTransport,
    private val key: AdbKey,
) : Closeable {
    private val lock = Any()
    private var nextLocalId = 1

    @Volatile var banner: String = ""
        private set
    @Volatile var maxData: Int = 4096
        private set
    @Volatile var connected: Boolean = false
        private set

    /** Connects and authenticates. [onAuthPrompt] fires when the target must show its "Allow USB debugging?" dialog. */
    fun connect(promptTimeoutMs: Long = 60_000, onAuthPrompt: () -> Unit = {}) = synchronized(lock) {
        send(A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, HOST_BANNER.toByteArray())
        var signed = false
        var offeredKey = false
        var deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val msg = readMessage(deadline) ?: throw AdbException(
                if (offeredKey) "Timed out waiting for you to allow USB debugging on the target."
                else "The target did not answer the ADB handshake (is USB debugging enabled?)",
            )
            when (msg.command) {
                A_CNXN -> {
                    banner = msg.payload.toString(Charsets.US_ASCII).trimEnd('\u0000')
                    maxData = msg.arg1.coerceIn(4096, 1024 * 1024)
                    connected = true
                    return@synchronized
                }
                A_AUTH -> {
                    if (msg.arg0 != AdbProtocol.AUTH_TOKEN) continue
                    when {
                        !signed -> {
                            signed = true
                            send(A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, key.signToken(msg.payload))
                        }
                        !offeredKey -> {
                            offeredKey = true
                            send(A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload())
                            deadline = System.currentTimeMillis() + promptTimeoutMs
                            onAuthPrompt()
                        }
                        else -> throw AdbException("The target rejected this host's key.")
                    }
                }
            }
        }
    }

    // ----- shell -------------------------------------------------------------------------------------

    fun shell(command: String, timeoutMs: Long = 30_000, onOutput: (String) -> Unit = {}): ShellResult =
        synchronized(lock) {
            requireConnected()
            val stream = open("shell:($command); echo \"$EXIT_MARKER\$?\"")
            val deadline = System.currentTimeMillis() + timeoutMs
            val all = StringBuilder()
            var emitted = 0
            try {
                while (true) {
                    val chunk = stream.readChunk(deadline) ?: break
                    all.append(chunk.toString(Charsets.UTF_8))
                    val safe = all.length - EXIT_MARKER_HOLD_BACK
                    if (safe > emitted) {
                        onOutput(all.substring(emitted, safe))
                        emitted = safe
                    }
                }
            } finally {
                stream.close()
            }
            val text = all.toString()
            val m = EXIT_REGEX.find(text)
            val body = if (m != null) text.substring(0, m.range.first) else text
            if (body.length > emitted) onOutput(body.substring(emitted))
            ShellResult(body, m?.groupValues?.get(1)?.toIntOrNull())
        }

    /** Streams a long-running command (logcat) until [shouldStop] returns true or the target closes it. */
    fun streamShell(command: String, shouldStop: () -> Boolean, onChunk: (String) -> Unit) = synchronized(lock) {
        requireConnected()
        val stream = open("shell:$command")
        try {
            while (!shouldStop()) {
                val chunk = try {
                    stream.readChunk(System.currentTimeMillis() + 500)
                } catch (_: UsbTimeoutException) {
                    continue
                } ?: break
                onChunk(chunk.toString(Charsets.UTF_8))
            }
        } finally {
            stream.close()
        }
    }

    fun getprop(): Map<String, String> {
        val out = shell("getprop").output
        return out.lineSequence().mapNotNull { PROP_REGEX.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun reboot(target: String = "") = synchronized(lock) {
        requireConnected()
        require(target.matches(Regex("[a-z0-9_,-]*"))) { "Unsupported reboot target '$target'" }
        val stream = open("reboot:$target")
        try {
            // The target drops the link as it restarts; end of stream or an I/O error both mean success.
            runCatching { stream.readChunk(System.currentTimeMillis() + 3000) }
        } finally {
            runCatching { stream.close() }
        }
    }

    // ----- sync (push / pull / stat) -----------------------------------------------------------------

    fun push(
        input: InputStream,
        size: Long,
        remotePath: String,
        mode: Int = 0b110_100_100,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = synchronized(lock) {
        requireConnected()
        val stream = open("sync:")
        try {
            val spec = "$remotePath,${0x8000 or mode}".toByteArray()
            syncSend(stream, "SEND", spec)
            val buf = ByteArray(SYNC_DATA_MAX)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                syncHeader(stream, "DATA", n, buf, n)
                sent += n
                progress(sent, size)
            }
            stream.write(syncFrame("DONE", (System.currentTimeMillis() / 1000).toInt()))
            val reply = stream.readExactly(8, System.currentTimeMillis() + 60_000)
            val id = String(reply, 0, 4, Charsets.US_ASCII)
            val len = reply.asLe().getInt(4)
            if (id == "FAIL") throw AdbException("push failed: " + String(stream.readExactly(len, System.currentTimeMillis() + 5000)))
            if (id != "OKAY") throw AdbException("Unexpected sync reply '$id' after push")
            stream.write(syncFrame("QUIT", 0))
        } finally {
            stream.close()
        }
    }

    fun pull(remotePath: String, out: OutputStream, progress: (Long) -> Unit = {}) = synchronized(lock) {
        requireConnected()
        val stream = open("sync:")
        try {
            syncSend(stream, "RECV", remotePath.toByteArray())
            var received = 0L
            while (true) {
                val head = stream.readExactly(8, System.currentTimeMillis() + 60_000)
                val id = String(head, 0, 4, Charsets.US_ASCII)
                val len = head.asLe().getInt(4)
                when (id) {
                    "DATA" -> {
                        out.write(stream.readExactly(len, System.currentTimeMillis() + 60_000))
                        received += len
                        progress(received)
                    }
                    "DONE" -> break
                    "FAIL" -> throw AdbException("pull failed: " + String(stream.readExactly(len, System.currentTimeMillis() + 5000)))
                    else -> throw AdbException("Unexpected sync reply '$id' during pull")
                }
            }
            stream.write(syncFrame("QUIT", 0))
        } finally {
            stream.close()
        }
    }

    // ----- sideload (recovery "apply update from ADB") -----------------------------------------------

    /**
     * Serves `sideload-host`. Recovery's FUSE reader asks for blocks by
     * number, not in file order: it reads the zip central directory at the
     * end, then the payload, and it reads the package again to install it.
     * A forward-only stream aborts that second read, so the update never
     * finishes. [file] is seeked for every request.
     */
    fun sideload(file: File, progress: (Long, Long) -> Unit = { _, _ -> }, shouldStop: () -> Boolean = { false }) =
        synchronized(lock) {
            requireConnected()
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (size <= 0L) throw AdbException("Sideload package is empty")
                val stream = open("sideload-host:$size:$SIDELOAD_BLOCK")
                try {
                    val block = ByteArray(SIDELOAD_BLOCK)
                    val lastIndex = (size - 1) / SIDELOAD_BLOCK
                    while (!shouldStop()) {
                        val req = stream.readExactly(8, System.currentTimeMillis() + 120_000)
                        val text = String(req, Charsets.US_ASCII)
                        when (text) {
                            "DONEDONE" -> {
                                progress(size, size)
                                return@synchronized
                            }
                            "FAILFAIL" -> throw AdbException("The target reported that sideload failed")
                        }
                        val index = text.trim().toLongOrNull()
                            ?: throw AdbException("Bad sideload block request '$text'")
                        if (index < 0 || index > lastIndex) {
                            throw AdbException("Target asked for block $index beyond the end of the file")
                        }
                        val offset = index * SIDELOAD_BLOCK
                        raf.seek(offset)
                        val want = minOf(SIDELOAD_BLOCK.toLong(), size - offset).toInt()
                        raf.readFully(block, 0, want)
                        stream.write(block, 0, want)
                        progress(offset + want, size)
                    }
                    throw AdbException("Sideload cancelled")
                } finally {
                    stream.close()
                }
            }
        }

    override fun close() {
        connected = false
        runCatching { transport.close() }
    }

    // ----- streams -----------------------------------------------------------------------------------

    private fun requireConnected() {
        if (!connected) throw AdbException("Not connected to an ADB target.")
    }

    private fun open(destination: String): Stream {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, destination.toByteArray() + byteArrayOf(0))
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val msg = readMessage(deadline) ?: throw AdbException("Timed out opening '$destination'")
            when (msg.command) {
                A_OKAY -> if (msg.arg1 == localId) return Stream(localId, msg.arg0)
                A_CLSE -> if (msg.arg1 == localId) throw AdbException("The target refused '${destination.substringBefore(':')}:' service")
            }
        }
    }

    private inner class Stream(val localId: Int, val remoteId: Int) {
        private val incoming = ArrayDeque<ByteArray>()
        private var current: ByteArray = ByteArray(0)
        private var currentPos = 0
        private var closedByRemote = false
        private var ackPending = false

        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            var sent = 0
            while (sent < length) {
                val n = minOf(maxData, length - sent)
                send(A_WRTE, localId, remoteId, data.copyOfRange(offset + sent, offset + sent + n))
                ackPending = true
                val deadline = System.currentTimeMillis() + 30_000
                while (ackPending) {
                    if (closedByRemote) throw AdbException("The target closed the stream while writing")
                    pumpOne(deadline) ?: throw AdbException("Timed out waiting for the target to accept data")
                }
                sent += n
            }
        }

        /** Next chunk of stream data, or null once the target closes it. */
        fun readChunk(deadline: Long): ByteArray? {
            while (incoming.isEmpty()) {
                if (closedByRemote) return null
                pumpOne(deadline) ?: throw UsbTimeoutException("Timed out waiting for output from the target")
            }
            return incoming.removeFirst()
        }

        fun readExactly(count: Int, deadline: Long): ByteArray {
            val out = ByteArray(count)
            var got = 0
            while (got < count) {
                if (currentPos >= current.size) {
                    current = readChunk(deadline) ?: throw AdbException("Stream closed after $got of $count bytes")
                    currentPos = 0
                }
                val n = minOf(count - got, current.size - currentPos)
                System.arraycopy(current, currentPos, out, got, n)
                currentPos += n
                got += n
            }
            return out
        }

        fun close() {
            if (!closedByRemote) {
                closedByRemote = true
                runCatching { send(A_CLSE, localId, remoteId, ByteArray(0)) }
            }
        }

        private fun pumpOne(deadline: Long): Unit? {
            val msg = readMessage(deadline) ?: return null
            if (msg.arg1 != localId) return Unit
            when (msg.command) {
                A_WRTE -> {
                    incoming.addLast(msg.payload)
                    send(A_OKAY, localId, remoteId, ByteArray(0))
                }
                A_OKAY -> ackPending = false
                A_CLSE -> closedByRemote = true
            }
            return Unit
        }
    }

    // ----- sync helpers ------------------------------------------------------------------------------

    private fun syncFrame(id: String, value: Int): ByteArray =
        leBuffer(8).apply { put(id.toByteArray(Charsets.US_ASCII)); putInt(value) }.array()

    private fun syncSend(stream: Stream, id: String, payload: ByteArray) {
        stream.write(syncFrame(id, payload.size) + payload)
    }

    private fun syncHeader(stream: Stream, id: String, length: Int, data: ByteArray, dataLen: Int) {
        stream.write(syncFrame(id, length) + data.copyOf(dataLen))
    }

    // ----- raw messages ------------------------------------------------------------------------------

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        transport.write(AdbProtocol.encodeHeader(command, arg0, arg1, payload), timeoutMs = 5000)
        if (payload.isNotEmpty()) transport.write(payload, timeoutMs = 10_000)
    }

    /** Reads one message, or returns null if nothing arrives before [deadline]. */
    private fun readMessage(deadline: Long): AdbMessage? {
        val head = ByteArray(AdbProtocol.HEADER_SIZE)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val n = try {
                transport.read(head, 0, head.size, minOf(left, 1000L).toInt())
            } catch (_: UsbTimeoutException) {
                continue
            }
            if (n < head.size) {
                val rest = transport.readFully(head.size - n, 5000)
                System.arraycopy(rest, 0, head, n, rest.size)
            }
            break
        }
        val h = AdbProtocol.decodeHeader(head)
        val payload = if (h.length > 0) transport.readFully(h.length, 10_000) else ByteArray(0)
        return AdbMessage(h.command, h.arg0, h.arg1, payload)
    }

    companion object {
        const val HOST_BANNER = "host::features=cmd,stat_v2,ls_v2,fixed_push_mkdir"
        private const val EXIT_MARKER = "__RF_EXIT__"
        private const val EXIT_MARKER_HOLD_BACK = EXIT_MARKER.length + 8
        private val EXIT_REGEX = Regex("__RF_EXIT__(\\d+)\\s*$")
        private val PROP_REGEX = Regex("\\[(.+?)]: \\[(.*)]")
        private const val SYNC_DATA_MAX = 64 * 1024
        const val SIDELOAD_BLOCK = 64 * 1024
    }
}
