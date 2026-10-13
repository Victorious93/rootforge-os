package ai.rootforge.os.fastboot

import ai.rootforge.os.usb.BulkTransport
import ai.rootforge.os.usb.UsbTimeoutException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

sealed class FastbootReply {
    data class Okay(val message: String) : FastbootReply()
    data class Fail(val message: String) : FastbootReply()
    data class Data(val length: Long) : FastbootReply()
}

class FastbootException(message: String) : IOException(message)

/**
 * Fastboot over bulk USB endpoints. The protocol is a request/response text
 * protocol: a command of at most 64 bytes, then zero or more `INFO` lines and
 * one final `OKAY`, `FAIL` or `DATA<hex length>`.
 *
 * Image transfer is `download:<8 hex digits>` followed by the raw bytes and
 * then a command (`flash:`, `boot`) that consumes the staged data.
 */
class FastbootClient(private val transport: BulkTransport) : Closeable {

    /** Sends [command] and collects INFO lines until a terminal reply. */
    fun command(
        command: String,
        timeoutMs: Long = 30_000,
        onInfo: (String) -> Unit = {},
    ): FastbootReply {
        val bytes = command.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_COMMAND) { "fastboot command longer than $MAX_COMMAND bytes" }
        transport.write(bytes, timeoutMs = 5000)
        return readReply(timeoutMs, onInfo)
    }

    /** Like [command] but treats FAIL as an error. */
    fun run(command: String, timeoutMs: Long = 30_000, onInfo: (String) -> Unit = {}): String =
        when (val r = command(command, timeoutMs, onInfo)) {
            is FastbootReply.Okay -> r.message
            is FastbootReply.Fail -> throw FastbootException("$command failed: ${r.message}")
            is FastbootReply.Data -> throw FastbootException("$command unexpectedly asked for data")
        }

    fun getvar(name: String): String = run("getvar:$name").trim()

    /** `getvar all` reports each variable as an INFO line, e.g. `(bootloader) slot-count: 2`. */
    fun getvarAll(onInfo: (String) -> Unit = {}): Map<String, String> {
        val vars = linkedMapOf<String, String>()
        val finalReply = command("getvar:all", 30_000) { line ->
            onInfo(line)
            parseVarLine(line)?.let { (k, v) -> vars.putIfAbsent(k, v) }
        }
        if (finalReply is FastbootReply.Fail) throw FastbootException("getvar all failed: ${finalReply.message}")
        return vars
    }

    fun maxDownloadSize(): Long? = parseNumber(runCatching { getvar("max-download-size") }.getOrNull())

    /**
     * Stages [size] bytes from [input] on the device with `download:`. The
     * caller follows with the command that uses them.
     */
    fun download(input: InputStream, size: Long, progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(size in 1..0xFFFFFFFFL) { "image size $size is out of range for a single download" }
        val reply = command("download:%08x".format(size), 10_000)
        val accepted = when (reply) {
            is FastbootReply.Data -> reply.length
            is FastbootReply.Fail -> throw FastbootException("download refused: ${reply.message}")
            is FastbootReply.Okay -> throw FastbootException("download answered OKAY without DATA")
        }
        if (accepted != size) throw FastbootException("Target accepted $accepted bytes, expected $size")
        val buf = ByteArray(CHUNK)
        var sent = 0L
        while (sent < size) {
            val want = minOf(CHUNK.toLong(), size - sent).toInt()
            var got = 0
            while (got < want) {
                val n = input.read(buf, got, want - got)
                if (n < 0) throw FastbootException("Image ended after $sent of $size bytes")
                got += n
            }
            transport.write(buf, 0, got, timeoutMs = 15_000)
            sent += got
            progress(sent, size)
        }
        when (val done = readReply(60_000)) {
            is FastbootReply.Okay -> Unit
            is FastbootReply.Fail -> throw FastbootException("download failed: ${done.message}")
            is FastbootReply.Data -> throw FastbootException("unexpected DATA after download")
        }
    }

    fun flash(partition: String, input: InputStream, size: Long, progress: (Long, Long) -> Unit = { _, _ -> }, onInfo: (String) -> Unit = {}) {
        requirePartitionName(partition)
        download(input, size, progress)
        run("flash:$partition", timeoutMs = 10 * 60_000L, onInfo = onInfo)
    }

    fun bootImage(input: InputStream, size: Long, progress: (Long, Long) -> Unit = { _, _ -> }, onInfo: (String) -> Unit = {}) {
        download(input, size, progress)
        run("boot", timeoutMs = 60_000, onInfo = onInfo)
    }

    fun erase(partition: String) {
        requirePartitionName(partition)
        run("erase:$partition", timeoutMs = 5 * 60_000L)
    }

    fun setActiveSlot(slot: String) {
        require(slot == "a" || slot == "b") { "slot must be a or b" }
        run("set_active:$slot")
    }

    fun reboot(target: RebootTarget) {
        // The device resets as it answers; a lost link after the reply is normal.
        runCatching { command(target.command, 10_000) }
    }

    override fun close() {
        runCatching { transport.close() }
    }

    private fun readReply(timeoutMs: Long, onInfo: (String) -> Unit = {}): FastbootReply {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(MAX_REPLY)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw FastbootException("Timed out waiting for the bootloader to reply")
            val n = try {
                transport.read(buf, 0, buf.size, minOf(left, 2000L).toInt())
            } catch (_: UsbTimeoutException) {
                continue
            }
            if (n < 4) throw FastbootException("Short fastboot reply ($n bytes)")
            val text = String(buf, 4, n - 4, Charsets.UTF_8)
            when (String(buf, 0, 4, Charsets.US_ASCII)) {
                "INFO" -> onInfo(text)
                "TEXT" -> onInfo(text)
                "OKAY" -> return FastbootReply.Okay(text)
                "FAIL" -> return FastbootReply.Fail(text)
                "DATA" -> return FastbootReply.Data(text.trim().toLongOrNull(16) ?: throw FastbootException("Bad DATA length '$text'"))
                else -> throw FastbootException("Unrecognised fastboot reply '${String(buf, 0, minOf(n, 16), Charsets.ISO_8859_1)}'")
            }
        }
    }

    companion object {
        const val MAX_COMMAND = 64
        private const val MAX_REPLY = 256
        private const val CHUNK = 1024 * 1024
        private val PARTITION = Regex("[A-Za-z0-9_.-]{1,40}")

        fun requirePartitionName(name: String) {
            require(PARTITION.matches(name)) { "'$name' is not a plausible partition name" }
        }

        fun parseVarLine(line: String): Pair<String, String>? {
            val body = line.removePrefix("(bootloader)").trim()
            val i = body.indexOf(':')
            if (i <= 0) return null
            return body.substring(0, i).trim() to body.substring(i + 1).trim()
        }

        fun parseNumber(text: String?): Long? {
            val t = text?.trim().orEmpty()
            return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull()
        }
    }
}

enum class RebootTarget(val label: String, val command: String) {
    SYSTEM("System", "reboot"),
    BOOTLOADER("Bootloader", "reboot-bootloader"),
    RECOVERY("Recovery", "reboot-recovery"),
    FASTBOOTD("Fastbootd", "reboot-fastboot"),
}
