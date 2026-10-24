package ai.rootforge.os.desktop

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.OutputLog
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostControllerSerialPinTest {
    @Test
    fun eraseStaysOnTheOriginalSerialWhenThatPhoneDrops() = runBlocking {
        val dir = createTempDirectory("rf-host-pin").toFile()
        val calls = Collections.synchronizedList(mutableListOf<List<String>>())
        val runner = object : ProcessRunner() {
            @Volatile var fastbootDevices = "PHONEA\tfastboot\nPHONEB\tfastboot\n"

            override fun run(exe: File, args: List<String>, timeoutMs: Long, stdinBytes: ByteArray?): CmdResult {
                calls += args
                return when {
                    args == listOf("devices") -> CmdResult(0, fastbootDevices, "")
                    args == listOf("devices", "-l") -> CmdResult(0, "", "")
                    else -> CmdResult(0, "", "")
                }
            }
        }
        val approvals = ApprovalCenter()
        val controller = HostController(
            HostToolset(File(dir, "adb"), File(dir, "fastboot"), null),
            runner,
            OutputLog(),
            Library(File(dir, "library")),
            approvals,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        controller.scan()
        val phoneA = controller.targets.value.first { it.serial == "PHONEA" }
        val phoneB = controller.targets.value.first { it.serial == "PHONEB" }
        assertTrue(controller.connect(phoneA).isSuccess)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val erase = scope.async { controller.fastbootErase("userdata") }
        withTimeout(5_000) {
            while (approvals.pending.value.isEmpty()) delay(20)
        }
        runner.fastbootDevices = "PHONEB\tfastboot\n"
        controller.scan()
        val switched = controller.connect(phoneB)
        assertTrue(switched.isFailure)
        assertTrue(switched.exceptionOrNull()!!.message!!.contains("Erase userdata"))

        val request = approvals.pending.value.single()
        assertTrue(approvals.answer(request.id, true, "ERASE userdata"))
        assertTrue(erase.await().isSuccess)
        val eraseCalls = calls.filter { it.contains("erase") }
        assertEquals(listOf("-s", "PHONEA", "erase", "userdata"), eraseCalls.single())
        assertFalse(eraseCalls.single().contains("PHONEB"))
    }
}
