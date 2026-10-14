package ai.rootforge.os.edl

import ai.rootforge.os.usb.BulkTransport
import ai.rootforge.os.usb.UsbTimeoutException
import java.io.IOException
import java.io.InputStream

class FirehoseException(message: String) : IOException(message)

/**
 * [EXPERIMENTAL][UNTESTED ON HARDWARE] A minimal Firehose client: XML
 * commands in, `<response>` / `<log>` XML out, raw sector data in between.
 *
 * It exists so the EDL screen has a real, inspectable code path. The XML
 * framing is exercised against a simulated programmer in the unit tests; no
 * programmer or phone was available to verify it end to end. Writes require
 * explicit LUN, start sector and sector size from the user: there is no
 * partition-table parsing and no "flash everything" mode here.
 */
class FirehoseClient(private val transport: BulkTransport) {
    var maxPayload: Int = 1024 * 1024
        private set

    fun configure(memoryName: String = "ufs", maxPayloadBytes: Int = 1024 * 1024, onLog: (String) -> Unit = {}) {
        require(memoryName in setOf("ufs", "emmc", "nand", "spinor")) { "unsupported memory type $memoryName" }
        val reply = exchange(
            """<configure MemoryName="$memoryName" Verbose="0" AlwaysValidate="0" MaxPayloadSizeToTargetInBytes="$maxPayloadBytes" ZlpAwareHost="1" SkipStorageInit="0" />""",
            onLog,
        )
        // A programmer that supports less than we asked for answers NAK and states its own limit.
        val negotiated = Regex("MaxPayloadSizeToTargetInBytes=\"(\\d+)\"").find(reply.raw)?.groupValues?.get(1)?.toIntOrNull()
        if (!reply.ack && negotiated != null) {
            maxPayload = negotiated
            val retry = exchange(
                """<configure MemoryName="$memoryName" Verbose="0" AlwaysValidate="0" MaxPayloadSizeToTargetInBytes="$negotiated" ZlpAwareHost="1" SkipStorageInit="0" />""",
                onLog,
            )
            if (!retry.ack) throw FirehoseException("configure rejected: ${retry.raw}")
        } else if (!reply.ack) {
            throw FirehoseException("configure rejected: ${reply.raw}")
        } else {
            maxPayload = negotiated ?: maxPayloadBytes
        }
    }

    fun storageInfo(lun: Int = 0, onLog: (String) -> Unit = {}): List<String> {
        val logs = mutableListOf<String>()
        val reply = exchange("""<getstorageinfo physical_partition_number="$lun"/>""") { logs += it; onLog(it) }
        if (!reply.ack) throw FirehoseException("getstorageinfo failed: ${reply.raw}")
        return logs
    }

    /** Writes [sectorCount] sectors of [data] at [startSector] on [lun]. Raw: no partition awareness. */
    fun programRaw(
        lun: Int,
        startSector: Long,
        sectorSize: Int,
        data: InputStream,
        dataSize: Long,
        onLog: (String) -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        require(sectorSize == 512 || sectorSize == 4096) { "sector size must be 512 or 4096" }
        require(lun in 0..7 && startSector >= 0) { "bad LUN or start sector" }
        val sectors = (dataSize + sectorSize - 1) / sectorSize
        val reply = exchange(
            """<program SECTOR_SIZE_IN_BYTES="$sectorSize" num_partition_sectors="$sectors" physical_partition_number="$lun" start_sector="$startSector"/>""",
            onLog,
        )
        if (!reply.ack) throw FirehoseException("program refused: ${reply.raw}")
        val total = sectors * sectorSize
        val buf = ByteArray(minOf(maxPayload, 1024 * 1024))
        var sent = 0L
        while (sent < total) {
            val want = minOf(buf.size.toLong(), total - sent).toInt()
            var got = 0
            while (got < want) {
                val n = data.read(buf, got, want - got)
                if (n < 0) break
                got += n
            }
            // Pad the final sector with zeros; Firehose writes whole sectors only.
            java.util.Arrays.fill(buf, got, want, 0)
            transport.write(buf, 0, want, 15_000)
            sent += want
            progress(minOf(sent, dataSize), dataSize)
        }
        val done = readResponse(60_000, onLog)
        if (!done.ack) throw FirehoseException("program failed: ${done.raw}")
    }

    fun reset(onLog: (String) -> Unit = {}) {
        exchange("""<power value="reset" DelayInSeconds="1"/>""", onLog)
    }


    data class Reply(val ack: Boolean, val raw: String)

    private fun exchange(xml: String, onLog: (String) -> Unit): Reply {
        transport.write(wrap(xml).toByteArray(Charsets.UTF_8), timeoutMs = 5000)
        return readResponse(30_000, onLog)
    }

    private fun readResponse(timeoutMs: Long, onLog: (String) -> Unit): Reply {
        val deadline = System.currentTimeMillis() + timeoutMs
        val pending = StringBuilder()
        val buf = ByteArray(8192)
        while (System.currentTimeMillis() < deadline) {
            val n = try {
                transport.read(buf, 0, buf.size, 1000)
            } catch (_: UsbTimeoutException) {
                continue
            }
            pending.append(String(buf, 0, n, Charsets.UTF_8))
            while (true) {
                val end = pending.indexOf("</data>")
                if (end < 0) break
                val doc = pending.substring(0, end + "</data>".length)
                pending.delete(0, end + "</data>".length)
                LOG_REGEX.findAll(doc).forEach { onLog(it.groupValues[1]) }
                val resp = RESPONSE_REGEX.find(doc) ?: continue
                val value = Regex("value=\"([^\"]*)\"").find(resp.value)?.groupValues?.get(1)
                return Reply(value.equals("ACK", ignoreCase = true), resp.value)
            }
        }
        throw FirehoseException("Timed out waiting for the Firehose programmer")
    }

    private fun wrap(inner: String) = """<?xml version="1.0" encoding="UTF-8" ?><data>$inner</data>"""

    private companion object {
        val RESPONSE_REGEX = Regex("<response[^>]*/>")
        val LOG_REGEX = Regex("<log value=\"([^\"]*)\"\\s*/>")
    }
}
