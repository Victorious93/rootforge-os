package ai.rootforge.os.desktop

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopSettingsTest {
    @Test
    fun newFileIsOwnerOnlyAndTokenRoundTrips() {
        assumePosix()
        val file = tempProperties()
        val settings = DesktopSettings(file)
        val token = settings.ensureApiToken()
        assertFalse(token.isBlank())
        assertEquals("rw-------", mode(file))

        val again = DesktopSettings(file)
        assertEquals(token, again.apiToken)
        assertEquals(settings.nodeId, again.nodeId)
        assertEquals("rw-------", mode(file))

        again.bridgeEnabled = true
        assertEquals("rw-------", mode(file))
        assertEquals(token, again.apiToken)
    }

    @Test
    fun existingWorldReadableTokenIsTightenedOnLoad() {
        assumePosix()
        val file = tempProperties()
        file.writeText("node_id=rf-already\napi_token=sekrit\n")
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals("rw-rw-rw-", mode(file))

        val settings = DesktopSettings(file)
        assertEquals("rw-------", mode(file))
        assertEquals("sekrit", settings.apiToken)
        assertEquals("rf-already", settings.nodeId)
        assertEquals("sekrit", settings.ensureApiToken())
    }

    private fun tempProperties(): java.io.File {
        val dir = createTempDirectory("rf-desktop-settings").toFile()
        return dir.resolve("desktop.properties")
    }

    private fun assumePosix() {
        val probe = createTempDirectory("rf-posix-probe")
        assumeTrue(probe.fileSystem.supportedFileAttributeViews().contains("posix"))
    }

    private fun mode(file: java.io.File): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
}
