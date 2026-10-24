package ai.rootforge.os.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPromptTest {
    @Test
    fun everyPrimitiveIsShownWhole() {
        val command = "a".repeat(2000)
        val lines = approvalParamLines(
            buildJsonObject {
                put("command", JsonPrimitive(command))
                put("version_code", JsonPrimitive(3))
            },
        )
        assertEquals(listOf("command = \"$command\"", "version_code = 3"), lines)
    }

    /** JSON escapes each control character as 6 characters; the longest valid command must still fit. */
    @Test
    fun theLongestValidCommandIsNeverRefused() {
        val worst = JsonPrimitive("\u0001".repeat(2000))
        assertTrue(worst.toString().length <= APPROVAL_PARAM_MAX_CHARS)
        assertEquals(1, approvalParamLines(buildJsonObject { put("command", worst) }).size)
    }

    @Test
    fun aValueTooLongToShowInFullIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            approvalParamLines(buildJsonObject { put("command", JsonPrimitive("x".repeat(APPROVAL_PARAM_MAX_CHARS))) })
        }
        assertTrue(e.message!!.contains("'command'"))
    }

    /** `JsonElement.toString()` recurses per level, so a deep value must be refused without ever being printed. */
    @Test
    fun aNestedValueIsRefusedWithoutRecursingIntoIt() {
        var deepArray: JsonElement = JsonPrimitive("x")
        repeat(200_000) { deepArray = JsonArray(listOf(deepArray)) }
        var deepObject: JsonElement = JsonPrimitive("x")
        repeat(200_000) { deepObject = JsonObject(mapOf("k" to deepObject)) }
        for (value in listOf(deepArray, deepObject)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                approvalParamLines(JsonObject(mapOf("command" to value)))
            }
            assertTrue(e.message!!.contains("'command'"))
        }
    }
}
