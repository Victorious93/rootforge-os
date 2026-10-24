package ai.rootforge.os.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
