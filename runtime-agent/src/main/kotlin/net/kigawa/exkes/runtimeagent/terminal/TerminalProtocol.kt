package net.kigawa.exkes.runtimeagent.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire messages of the terminal WebSocket (`GET /terminal`). Every frame is one JSON text
 * object whose `type` field selects the variant. See docs/runtime-agent-terminal.md.
 */
@Serializable
sealed interface TerminalClientMessage {
    /** Keystrokes or pasted text. [data] is UTF-8 text and is written to the PTY as its UTF-8 bytes. */
    @Serializable
    @SerialName("input")
    data class Input(val data: String) : TerminalClientMessage

    @Serializable
    @SerialName("resize")
    data class Resize(val cols: Int, val rows: Int) : TerminalClientMessage

    /** Ends the session: the shell and its process group are killed and the socket is closed. */
    @Serializable
    @SerialName("close")
    data object Close : TerminalClientMessage
}

@Serializable
sealed interface TerminalServerMessage {
    /**
     * PTY output. [data] is UTF-8 text. Byte sequences split across reads are reassembled;
     * bytes that are not valid UTF-8 are replaced with U+FFFD.
     */
    @Serializable
    @SerialName("output")
    data class Output(val data: String) : TerminalServerMessage

    /** The shell exited with [code]. Sent once, right before the socket is closed. */
    @Serializable
    @SerialName("exit")
    data class Exit(val code: Int) : TerminalServerMessage

    /** Session-level failure or termination (rejected request, limit, idle/TTL expiry, ...). */
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : TerminalServerMessage
}

/** Tolerant decoder for client frames. Unknown keys are ignored; unknown `type` values fail. */
val terminalJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
