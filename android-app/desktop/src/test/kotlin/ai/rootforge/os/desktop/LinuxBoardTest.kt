package ai.rootforge.os.desktop

import ai.rootforge.os.device.HostPlatform
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxBoardTest {
    @Test
    fun deviceTreeRaspberryPiReusesTheDesktopHostCaption() {
        val dir = createTempDirectory("rf-linux-board").toFile()
        val model = File(dir, "model")
        model.writeBytes("Raspberry Pi 5 Model B Rev 1.0\u0000".toByteArray(Charsets.UTF_8))
        assertEquals("Raspberry Pi 5 Model B Rev 1.0", LinuxBoard.deviceTreeModel(model))
        assertEquals(HostPlatform.PI_CAPTION, LinuxBoard.hostCaption(model))
        assertTrue(HostPlatform.isHostComputer(LinuxBoard.hostCaption(model)))

        model.writeText("Intel Corporation NUC")
        assertEquals("Intel Corporation NUC", LinuxBoard.deviceTreeModel(model))
        assertEquals(HostPlatform.COMPUTER_CAPTION, LinuxBoard.hostCaption(model))

        assertNull(LinuxBoard.deviceTreeModel(File(dir, "missing")))
    }
}
