package net.kigawa.exkes.runtimeagent.routes

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import net.kigawa.exkes.runtimeagent.terminal.InvalidTerminalRequestException
import net.kigawa.exkes.runtimeagent.terminal.TerminalClientMessage
import net.kigawa.exkes.runtimeagent.terminal.TerminalLimitException
import net.kigawa.exkes.runtimeagent.terminal.TerminalManager
import net.kigawa.exkes.runtimeagent.terminal.TerminalRequest
import net.kigawa.exkes.runtimeagent.terminal.TerminalServerMessage
import net.kigawa.exkes.runtimeagent.terminal.TerminalSession
import net.kigawa.exkes.runtimeagent.terminal.terminalJson
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TerminalRoutes")

/**
 * `GET /terminal` (WebSocket upgrade). Query parameters, all optional: `cols`, `rows`
 * (1..1000, default 80x24), `cwd` (absolute existing directory), `shell` (absolute executable).
 * The wire protocol is documented in docs/runtime-agent-terminal.md.
 *
 * Authentication is not done here; the terminal gateway is responsible for it.
 */
fun Application.configureTerminalRoutes(manager: TerminalManager) {
    routing {
        webSocket("/terminal") {
            serveTerminal(manager, call.request.queryParameters::get)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.serveTerminal(
    manager: TerminalManager,
    query: (String) -> String?,
) {
    val request = try {
        TerminalRequest.fromQuery(query)
    } catch (e: InvalidTerminalRequestException) {
        rejectAndClose(e.message ?: "invalid request", CloseReason.Codes.VIOLATED_POLICY)
        return
    }
    val session = try {
        manager.open(request)
    } catch (e: TerminalLimitException) {
        rejectAndClose(e.message ?: "too many terminal sessions", CloseReason.Codes.TRY_AGAIN_LATER)
        return
    } catch (e: Exception) {
        // Details (paths, OS errors) stay in the log; the client gets a fixed message.
        logger.error("failed to start terminal shell", e)
        rejectAndClose("failed to start shell", CloseReason.Codes.INTERNAL_ERROR)
        return
    }
    try {
        coroutineScope {
            val pump = launch { pumpToClient(session) }
            try {
                readFromClient(session)
            } finally {
                pump.cancel()
            }
        }
    } finally {
        // Socket gone (or Close received): the shell and its process group must not outlive it.
        // Runs even when this handler is being cancelled; the /proc scan stays off the event loop.
        withContext(Dispatchers.IO + NonCancellable) { session.detach() }
    }
}

/** Sends output and terminal messages, then closes the socket when the session has ended. */
private suspend fun DefaultWebSocketServerSession.pumpToClient(session: TerminalSession) {
    try {
        for (message in session.outbound) {
            send(Frame.Text(terminalJson.encodeToString(TerminalServerMessage.serializer(), message)))
        }
        close(CloseReason(CloseReason.Codes.NORMAL, "terminal exited"))
    } catch (e: ClosedSendChannelException) {
        // The socket closed first; detach() handles the session.
    }
}

/** Applies client messages until `close`, or until the socket ends. Malformed messages do not end the session. */
private suspend fun DefaultWebSocketServerSession.readFromClient(session: TerminalSession) {
    for (frame in incoming) {
        if (frame !is Frame.Text) {
            sendError("only text frames are accepted")
            continue
        }
        // The payload is never logged: input can contain secrets.
        val message = try {
            terminalJson.decodeFromString(TerminalClientMessage.serializer(), frame.readText())
        } catch (e: SerializationException) {
            sendError("invalid message")
            continue
        } catch (e: IllegalArgumentException) {
            sendError("invalid message")
            continue
        }
        when (message) {
            is TerminalClientMessage.Input -> session.write(message.data)
            is TerminalClientMessage.Resize -> {
                try {
                    TerminalRequest.checkSize(message.cols, message.rows)
                } catch (e: InvalidTerminalRequestException) {
                    sendError(e.message ?: "invalid size")
                    continue
                }
                session.resize(message.cols, message.rows)
            }
            TerminalClientMessage.Close -> return
        }
    }
}

private suspend fun DefaultWebSocketServerSession.sendError(message: String) {
    send(Frame.Text(terminalJson.encodeToString(TerminalServerMessage.serializer(), TerminalServerMessage.Error(message))))
}

private suspend fun DefaultWebSocketServerSession.rejectAndClose(message: String, code: CloseReason.Codes) {
    logger.info("terminal request rejected: {}", message)
    sendError(message)
    close(CloseReason(code, message))
}
