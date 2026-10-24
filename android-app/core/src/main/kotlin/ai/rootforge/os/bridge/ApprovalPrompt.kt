package ai.rootforge.os.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Longest parameter text an approval prompt will show, as JSON. The largest string parameter is 2000
 * characters and JSON escaping can make each one 6 characters long, so every valid request fits. A
 * request over this is refused before anyone is asked: the person approving must be able to read all
 * of what will run, never a cut-off start of it.
 */
internal const val APPROVAL_PARAM_MAX_CHARS = 12_100

/**
 * One `name = value` line per request parameter for an approval prompt, each value shown whole.
 *
 * Every parameter of an action that asks first is a string or a number, so anything nested is refused
 * here, before it is printed: `JsonElement.toString()` recurses once per level and a request a few KB
 * deep would overflow the stack of whatever thread is serving the bridge.
 *
 * @throws IllegalArgumentException when a value is nested or too long to show in full.
 */
internal fun approvalParamLines(params: JsonObject): List<String> = params.entries.map { (key, value) ->
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("'$key' must be a string or number")
    val text = primitive.toString()
    require(text.length <= APPROVAL_PARAM_MAX_CHARS) { "'$key' is too long to show in full in the approval prompt" }
    "$key = $text"
}
