package ai.rootforge.os.desktop

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class HeadlessApiAuthTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun theExactBearerTokenIsAccepted() {
        assertTrue(HeadlessApi.tokenMatches("Bearer $token", token))
        assertTrue(HeadlessApi.tokenMatches("Bearer  $token  ", token))
    }

    @Test
    fun anythingElseIsRefused() {
        assertFalse(HeadlessApi.tokenMatches(null, token))
        assertFalse(HeadlessApi.tokenMatches("", token))
        assertFalse(HeadlessApi.tokenMatches("Bearer ", token))
        assertFalse(HeadlessApi.tokenMatches("Bearer ${token.dropLast(1)}", token))
        assertFalse(HeadlessApi.tokenMatches("Bearer ${token}0", token))
        // Same length, differs only in the last character.
        assertFalse(HeadlessApi.tokenMatches("Bearer ${token.dropLast(1)}0", token))
        assertFalse(HeadlessApi.tokenMatches("Bearer ${token.uppercase()}", token))
        assertFalse(HeadlessApi.tokenMatches("Bearer café", token))
    }

    @Test
    fun anEmptyServerTokenAcceptsNothing() {
        assertFalse(HeadlessApi.tokenMatches("Bearer ", ""))
        assertFalse(HeadlessApi.tokenMatches("", ""))
        assertFalse(HeadlessApi.tokenMatches(null, ""))
    }

    @Test
    fun pairingFileIsCreatedOwnerOnly() {
        assumePosix()
        val file = createTempDirectory("rf-pairing").toFile().resolve("paired.json")
        FilePairingPersistence(file).write("""[{"packageName":"ai.example","certSha256":"aa","pairedAtMs":1}]""")
        assertTrue(file.isFile)
        assertEquals("rw-------", posixMode(file))
    }

    @Test
    fun anExistingWorldReadablePairingFileIsTightened() {
        assumePosix()
        val file = createTempDirectory("rf-pairing").toFile().resolve("paired.json")
        file.writeText("[]")
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals("rw-rw-rw-", posixMode(file))
        val persist = FilePairingPersistence(file)
        persist.read()
        assertEquals("rw-------", posixMode(file))
        persist.write("[]")
        assertEquals("rw-------", posixMode(file))
    }

    private fun assumePosix() {
        val probe = createTempDirectory("rf-posix-probe")
        assumeTrue(probe.fileSystem.supportedFileAttributeViews().contains("posix"))
    }

    private fun posixMode(file: java.io.File): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
}
