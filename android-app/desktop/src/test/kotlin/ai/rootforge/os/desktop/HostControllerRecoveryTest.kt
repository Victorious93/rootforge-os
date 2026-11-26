package ai.rootforge.os.desktop

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.OutputLog
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Detect asks `getprop` for an explicit key list. The installer then
 * re-reads a full `getprop` dump and refuses the write unless serial, device
 * and slot still match. A serial that Detect never asked for is null on the
 * probe and does not match the live phone, so dry-run and write both stop.
 */
class HostControllerRecoveryTest {
    @Test
    fun detectRecordsSerialSoDryRunIsNotRefusedOnTheSamePhone() = runBlocking {
        val dir = createTempDirectory("rf-host-recovery").toFile()
        val controller = HostController(
            HostToolset(File(dir, "adb"), null, null),
            RecoveryRunner(),
            OutputLog(),
            Library(File(dir, "library")),
            ApprovalCenter(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        controller.scan()
        val target = controller.targets.value.single()
        assertTrue(controller.connect(target).isSuccess)

        val probe = controller.probeAdbRecovery().getOrThrow()
        assertEquals(SERIAL, probe.identity.serial)
        assertEquals("olive", probe.identity.device)

        val image = controller.library.importStream(
            "twrp.img",
            "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream(),
        )
        val dryRun = controller.installCustomRecovery(probe, image, dryRun = true)
        assertTrue(dryRun.exceptionOrNull()?.message ?: "dry-run failed", dryRun.isSuccess)
        assertTrue(dryRun.getOrThrow().dryRun)
    }

    private class RecoveryRunner : ProcessRunner() {
        override fun run(exe: File, args: List<String>, timeoutMs: Long, stdinBytes: ByteArray?): CmdResult {
            val shell = args.dropWhile { it != "shell" }.drop(1)
            val cmd = shell.joinToString(" ")
            return when {
                args == listOf("devices", "-l") -> CmdResult(0, DEVICES, "")
                cmd == "getprop" -> CmdResult(0, DUMP, "")
                cmd.startsWith("getprop ") -> CmdResult(0, PROPS[cmd.removePrefix("getprop ").trim()].orEmpty(), "")
                cmd == "su -c 'id'" -> CmdResult(0, "uid=0(root) gid=0(root)", "")
                cmd.contains("blockdev --getsize64") -> CmdResult(0, "67108864", "")
                cmd.contains("ls -l ") -> CmdResult(0, "brw------- 1 root root 259, 5 recovery", "")
                cmd.contains("ls -1 ") -> CmdResult(0, LISTING, "")
                cmd.contains("getprop") -> CmdResult(0, DUMP, "")
                else -> CmdResult(0, "", "")
            }
        }
    }

    private companion object {
        const val SERIAL = "OLIVE1234"

        val DEVICES = """
            List of devices attached
            $SERIAL         device product:olive model:Redmi_8 device:olive transport_id:1
        """.trimIndent()

        val PROPS = mapOf(
            "ro.product.device" to "olive",
            "ro.build.product" to "olive",
            "ro.product.name" to "olive",
            "ro.product.model" to "Redmi 8",
            "ro.boot.slot_suffix" to "",
            "ro.build.ab_update" to "false",
            "ro.serialno" to SERIAL,
            "ro.boot.serialno" to SERIAL,
        )

        val DUMP = """
            [ro.product.device]: [olive]
            [ro.build.product]: [olive]
            [ro.product.name]: [olive]
            [ro.product.model]: [Redmi 8]
            [ro.build.ab_update]: [false]
            [ro.serialno]: [$SERIAL]
            [ro.boot.serialno]: [$SERIAL]
        """.trimIndent()

        val LISTING = """
            DIR /dev/block/by-name
            boot
            recovery
            system
            DIR /dev/block/bootdevice/by-name
        """.trimIndent()
    }
}
