package ai.rootforge.os.adb

import ai.rootforge.os.PipeEnd
import ai.rootforge.os.core.asLe
import ai.rootforge.os.core.leBuffer
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/** A just-enough adbd for protocol tests: auth, shell, sync push/pull, sideload-host, reboot. */
class FakeAdbd(
    private val end: PipeEnd,
    private val banner: String = "device::ro.product.model=Fake;ro.product.device=fakephone;",
    private val shellHandler: (String) -> Pair<String, Int> = { "" to 0 },
) : Thread("fake-adbd") {
    val files = HashMap<String, ByteArray>()
    val receivedSideload = ByteArrayOutputStream()
    /** Payloads the host sent, one per block request, in the order they arrived. */
    val sideloadChunks = mutableListOf<ByteArray>()
    /**
     * When set, these block indexes are requested in this order and then
     * `DONEDONE`. Null keeps the sequential 0,1,2,… plan.
     */
    var sideloadPlan: List<Int>? = null
    /** Sent when [sideloadPlan] is exhausted. Recovery uses `FAILFAIL` when the install failed. */
    var sideloadTerminal: String = "DONEDONE"
    var sideloadSource: ByteArray? = null
    var lastRebootTarget: String? = null
    var authSignatureValid: Boolean? = null
    var publicKeyOffered: ByteArray? = null
    @Volatile var knownKey: RSAPublicKey? = null
    private val token = ByteArray(20) { (it * 7 + 3).toByte() }

    private var service = ""
    private var remoteId = 0
    private var localId = 7
    private val syncBuf = ByteArrayOutputStream()
    private var syncPath = ""
    private var syncData = ByteArrayOutputStream()
    private var syncState = 0 // 0 idle, 1 receiving SEND data
    private var sideloadSize = 0L
    private var sideloadBlock = 0
    private var sideloadNext = 0
    private var sideloadPlanPos = 0

    init { isDaemon = true }

    override fun run() {
        try {
            while (true) loopOnce()
        } catch (_: Exception) {
        }
    }

    private fun loopOnce() {
        val head = end.nextPacket(10_000)
        val h = head.asLe()
        val cmd = h.getInt(0); val a0 = h.getInt(4); val a1 = h.getInt(8); val len = h.getInt(12)
        val payload = if (len > 0) end.nextPacket() else ByteArray(0)
        when (cmd) {
            AdbProtocol.A_CNXN -> sendAuthToken()
            AdbProtocol.A_AUTH -> onAuth(a0, payload)
            AdbProtocol.A_OPEN -> onOpen(a0, String(payload).trimEnd('\u0000'))
            AdbProtocol.A_WRTE -> { reply(AdbProtocol.A_OKAY, localId, remoteId); onData(payload) }
            AdbProtocol.A_OKAY -> if (service.startsWith("sideload-host")) pumpSideload()
            AdbProtocol.A_CLSE -> Unit
        }
    }

    private fun sendAuthToken() = reply(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)

    private fun onAuth(type: Int, payload: ByteArray) {
        when (type) {
            AdbProtocol.AUTH_SIGNATURE -> {
                val pub = knownKey
                if (pub != null && verify(pub, payload)) {
                    authSignatureValid = true
                    connected()
                } else {
                    if (pub == null) authSignatureValid = false
                    sendAuthToken()
                }
            }
            AdbProtocol.AUTH_RSAPUBLICKEY -> {
                publicKeyOffered = payload
                connected()
            }
        }
    }

    private fun verify(pub: RSAPublicKey, signature: ByteArray): Boolean {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.DECRYPT_MODE, pub)
        return runCatching { c.doFinal(signature).contentEquals(SoftwareAdbKey.SHA1_DIGEST_INFO_PREFIX + token) }.getOrDefault(false)
    }

    private fun connected() = reply(AdbProtocol.A_CNXN, AdbProtocol.VERSION, 1024 * 1024, banner.toByteArray() + byteArrayOf(0))

    private fun onOpen(remote: Int, dest: String) {
        remoteId = remote
        service = dest
        localId++
        when {
            dest.startsWith("shell:") -> {
                reply(AdbProtocol.A_OKAY, localId, remoteId)
                val command = dest.removePrefix("shell:")
                val (out, code) = shellHandler(command)
                val text = out.replace("\n", "\r\n") + (if (command.contains("__RF_EXIT__")) "__RF_EXIT__$code\r\n" else "")
                if (text.isNotEmpty()) reply(AdbProtocol.A_WRTE, localId, remoteId, text.toByteArray())
                // The host acks each WRTE; closing straight away is what adbd does when the command ends.
                reply(AdbProtocol.A_CLSE, localId, remoteId)
            }
            dest == "sync:" -> reply(AdbProtocol.A_OKAY, localId, remoteId)
            dest.startsWith("reboot:") -> {
                lastRebootTarget = dest.removePrefix("reboot:")
                reply(AdbProtocol.A_OKAY, localId, remoteId)
                reply(AdbProtocol.A_CLSE, localId, remoteId)
            }
            dest.startsWith("sideload-host:") -> {
                val (size, block) = dest.removePrefix("sideload-host:").split(":").map { it.toLong() }
                sideloadSize = size; sideloadBlock = block.toInt(); sideloadNext = 0
                reply(AdbProtocol.A_OKAY, localId, remoteId)
                requestBlock()
            }
            else -> reply(AdbProtocol.A_CLSE, 0, remote)
        }
    }

    private fun requestBlock() {
        val plan = sideloadPlan
        if (plan != null) {
            if (sideloadPlanPos >= plan.size) {
                reply(AdbProtocol.A_WRTE, localId, remoteId, sideloadTerminal.toByteArray())
            } else {
                val block = plan[sideloadPlanPos++]
                reply(AdbProtocol.A_WRTE, localId, remoteId, "%08d".format(block).toByteArray())
            }
            return
        }
        val blocks = (sideloadSize + sideloadBlock - 1) / sideloadBlock
        if (sideloadNext >= blocks) {
            reply(AdbProtocol.A_WRTE, localId, remoteId, "DONEDONE".toByteArray())
        } else {
            reply(AdbProtocol.A_WRTE, localId, remoteId, "%08d".format(sideloadNext).toByteArray())
        }
    }

    private fun pumpSideload() = Unit

    private fun onData(payload: ByteArray) {
        if (service.startsWith("sideload-host:")) {
            receivedSideload.write(payload)
            sideloadChunks.add(payload.copyOf())
            sideloadNext++
            requestBlock()
            return
        }
        syncBuf.write(payload)
        processSync()
    }

    private fun processSync() {
        while (true) {
            val buf = syncBuf.toByteArray()
            if (buf.size < 8) return
            val id = String(buf, 0, 4)
            val n = buf.asLe().getInt(4)
            when {
                syncState == 1 && id == "DATA" -> {
                    if (buf.size < 8 + n) return
                    syncData.write(buf, 8, n); consume(8 + n)
                }
                syncState == 1 && id == "DONE" -> {
                    files[syncPath] = syncData.toByteArray(); syncState = 0; consume(8)
                    reply(AdbProtocol.A_WRTE, localId, remoteId, "OKAY".toByteArray() + ByteArray(4))
                }
                id == "SEND" -> {
                    if (buf.size < 8 + n) return
                    syncPath = String(buf, 8, n).substringBeforeLast(','); syncData = ByteArrayOutputStream(); syncState = 1
                    consume(8 + n)
                }
                id == "RECV" -> {
                    if (buf.size < 8 + n) return
                    val path = String(buf, 8, n); consume(8 + n)
                    val data = files[path]
                    if (data == null) {
                        val msg = "no such file".toByteArray()
                        reply(AdbProtocol.A_WRTE, localId, remoteId, "FAIL".toByteArray() + leBuffer(4).putInt(msg.size).array() + msg)
                    } else {
                        var off = 0
                        while (off < data.size) {
                            val c = minOf(64 * 1024, data.size - off)
                            reply(AdbProtocol.A_WRTE, localId, remoteId, "DATA".toByteArray() + leBuffer(4).putInt(c).array() + data.copyOfRange(off, off + c))
                            off += c
                        }
                        reply(AdbProtocol.A_WRTE, localId, remoteId, "DONE".toByteArray() + ByteArray(4))
                    }
                }
                id == "QUIT" -> consume(8)
                else -> return
            }
        }
    }

    private fun consume(count: Int) {
        val rest = syncBuf.toByteArray().copyOfRange(count, syncBuf.size())
        syncBuf.reset(); syncBuf.write(rest)
    }

    private fun reply(cmd: Int, a0: Int, a1: Int, payload: ByteArray = ByteArray(0)) {
        end.send(AdbProtocol.encodeHeader(cmd, a0, a1, payload))
        if (payload.isNotEmpty()) end.send(payload)
    }
}
