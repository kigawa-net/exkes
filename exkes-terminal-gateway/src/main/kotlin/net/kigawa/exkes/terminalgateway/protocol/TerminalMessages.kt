package net.kigawa.exkes.terminalgateway.protocol

import io.ktor.websocket.Frame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A UTF-8 text frame (the only frame type of the terminal protocol). */
fun textFrame(text: String): Frame.Text = Frame.Text(true, text.toByteArray(Charsets.UTF_8))

/** The `error` frame of docs/runtime-agent-terminal.md, built with a JSON encoder (no string escaping). */
fun errorFrame(message: String): String = buildJsonObject {
    put("type", "error")
    put("message", message)
}.toString()

/**
 * The first frame a client must send: `{"type":"auth","token":"...","cols":120,"rows":40}`.
 * `cols` / `rows` are optional and forwarded to the agent as its initial PTY size.
 */
data class AuthRequest(val token: String, val cols: Int?, val rows: Int?)

/**
 * Strict parser for the auth frame. Unknown fields are ignored.
 *
 * Callers must not log the input or the exception message: a JSON parse error can quote part
 * of the input, and the input contains the token. [parse] therefore never reports why a frame
 * is invalid; it throws or returns null, and the caller reports a generic rejection.
 */
object AuthFrame {
    /** The auth frame is tiny; anything bigger is rejected before parsing. */
    const val MAX_BYTES = 2048
    const val MAX_TOKEN_LENGTH = 256
    const val MAX_TERMINAL_SIZE = 1000

    /** Returns null for a frame that is not a valid auth request. May throw on malformed JSON. */
    fun parse(text: String): AuthRequest? {
        if (text.length > MAX_BYTES) return null
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return null
        if (stringOf(obj["type"]) != "auth") return null
        val token = stringOf(obj["token"])?.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH }
            ?: return null
        val cols = obj["cols"]?.let { sizeOf(it) ?: return null }
        val rows = obj["rows"]?.let { sizeOf(it) ?: return null }
        return AuthRequest(token, cols, rows)
    }

    private fun stringOf(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun sizeOf(element: JsonElement): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.content.toIntOrNull() ?: return null
        return value.takeIf { it in 1..MAX_TERMINAL_SIZE }
    }
}
