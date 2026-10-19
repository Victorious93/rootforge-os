package ai.rootforge.os.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppTest {
    @Test
    fun acceptsApplicationIds() {
        assertTrue(TerminalApp.isValidPackage("com.termux"))
        assertTrue(TerminalApp.isValidPackage("ai.victor.suite.debug"))
        assertTrue(TerminalApp.isValidPackage("a.b_c.d1"))
    }

    @Test
    fun rejectsAnythingElse() {
        for (bad in listOf("", "termux", ".com.termux", "com.termux.", "com..termux", "1com.termux", "com.termux; rm -rf /",
            "com.termux/.app.TermuxActivity", "intent://x#Intent;end", "com.ter mux", "com.termux\n", "a." + "b".repeat(300))) {
            assertFalse("should reject '$bad'", TerminalApp.isValidPackage(bad))
        }
    }
}
