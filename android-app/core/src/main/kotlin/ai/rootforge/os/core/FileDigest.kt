package ai.rootforge.os.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class DigestCheck(
    val ok: Boolean,
    val expected: String,
    val actual: String,
    val message: String,
)

/** SHA-256 of library files. Verify is case-insensitive; empty or malformed expected values fail. */
object FileDigest {
    const val MISMATCH = "Image SHA-256 does not match the manifest digest."
    const val EMPTY_EXPECTED = "Expected SHA-256 is empty."
    const val BAD_EXPECTED = "Expected SHA-256 is not a 64-character hex string."

    fun sha256Hex(data: ByteArray): String = digest().digest(data).toHex()

    fun sha256Hex(input: InputStream): String {
        val md = digest()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun md5Hex(data: ByteArray): String = md5().digest(data).toHex()

    fun md5Hex(input: InputStream): String {
        val md = md5()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    fun md5Hex(file: File): String = file.inputStream().use { md5Hex(it) }

    fun verify(expectedHex: String, actualHex: String): DigestCheck {
        val expected = expectedHex.trim().lowercase()
        val actual = actualHex.trim().lowercase()
        if (expected.isEmpty()) {
            return DigestCheck(false, expected, actual, EMPTY_EXPECTED)
        }
        if (!HEX64.matches(expected)) {
            return DigestCheck(false, expected, actual, BAD_EXPECTED)
        }
        return if (expected == actual) {
            DigestCheck(true, expected, actual, "SHA-256 matches.")
        } else {
            DigestCheck(false, expected, actual, MISMATCH)
        }
    }

    fun verifyMd5(expectedHex: String, actualHex: String): DigestCheck {
        val expected = expectedHex.trim().lowercase()
        val actual = actualHex.trim().lowercase()
        if (expected.isEmpty()) {
            return DigestCheck(false, expected, actual, MD5_EMPTY_EXPECTED)
        }
        if (!HEX32.matches(expected)) {
            return DigestCheck(false, expected, actual, MD5_BAD_EXPECTED)
        }
        return if (expected == actual) {
            DigestCheck(true, expected, actual, "MD5 matches.")
        } else {
            DigestCheck(false, expected, actual, MD5_MISMATCH)
        }
    }

    const val MD5_MISMATCH = "File MD5 does not match the published digest."
    const val MD5_EMPTY_EXPECTED = "Expected MD5 is empty."
    const val MD5_BAD_EXPECTED = "Expected MD5 is not a 32-character hex string."

    private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")
    private fun md5(): MessageDigest = MessageDigest.getInstance("MD5")

    private val HEX64 = Regex("[0-9a-f]{64}")
    private val HEX32 = Regex("[0-9a-f]{32}")
}
