package net.kigawa.exkes.terminalgateway.routes

import io.ktor.http.URLBuilder
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.kigawa.exkes.terminalgateway.audit.TerminalAudit
import net.kigawa.exkes.terminalgateway.auth.AdmissionResult
import net.kigawa.exkes.terminalgateway.auth.RejectReason
import net.kigawa.exkes.terminalgateway.auth.TerminalAuthorizer
import net.kigawa.exkes.terminalgateway.config.TerminalGatewayConfig
import net.kigawa.exkes.terminalgateway.protocol.AuthFrame
import net.kigawa.exkes.terminalgateway.protocol.AuthRequest
import net.kigawa.exkes.terminalgateway.protocol.errorFrame
import net.kigawa.exkes.terminalgateway.protocol.textFrame
import net.kigawa.exkes.terminalgateway.relay.RelayEnd
import net.kigawa.exkes.terminalgateway.relay.TerminalRelay

/** Path of the client WebSocket. The token is NOT in the URL; see docs/terminal-gateway.md. */
const val TERMINAL_PATH = "/v1/terminal"

// Client-facing texts. Deliberately generic: a client must not learn whether a token was
// unknown, consumed or expired, nor whether a runtime exists.
private const val REJECTED_TEXT = "terminal session rejected"
private const val UNAVAILABLE_TEXT = "terminal unavailable"
private const val IDLE_TEXT = "terminal session was idle for too long"
private const val MAX_LIFETIME_TEXT = "terminal session reached its maximum lifetime"
private const val ENDED_TEXT = "terminal session ended"

/**
 * Registers `GET /v1/terminal` (WebSocket upgrade).
 *
 * The first client frame must be the auth frame ([AuthRequest]). Nothing is relayed before it
 * is accepted, and the agent is not contacted for a rejected token.
 */
fun io.ktor.server.routing.Route.terminalRoutes(
    config: TerminalGatewayConfig,
    authorizer: TerminalAuthorizer,
    relay: TerminalRelay,
) {
    webSocket(TERMINAL_PATH) {
        handleConnection(config, authorizer, relay)
    }
}

private suspend fun WebSocketSession.handleConnection(
    config: TerminalGatewayConfig,
    authorizer: TerminalAuthorizer,
    relay: TerminalRelay,
) {
    val auth = when (val read = readAuthFrame(config)) {
        is AuthRead.Ok -> read.request
        is AuthRead.Failed -> {
            TerminalAudit.rejected(read.reason, runtimeId = null, detail = null)
            rejectAndClose()
            return
        }
    }

    // Blocking JDBC: never on the Netty event loop.
    val admission = try {
        withContext(Dispatchers.IO) { authorizer.admit(auth.token) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Log the class only: the JDBC or SQL message may echo SQL parameters.
        TerminalAudit.admissionFailed(e.javaClass.name)
        closeQuietly(CloseReason(CloseReason.Codes.INTERNAL_ERROR, UNAVAILABLE_TEXT))
        return
    }

    val admitted = when (admission) {
        is AdmissionResult.Rejected -> {
            TerminalAudit.rejected(admission.reason, admission.runtimeId, admission.detail)
            rejectAndClose()
            return
        }
        is AdmissionResult.Admitted -> admission
    }

    val sessionId = admitted.session.id
    val runtimeId = admitted.session.runtimeId
    TerminalAudit.accepted(sessionId, runtimeId)
    val startedAt = System.nanoTime()

    val result = relay.relay(this, agentUrlWith(admitted.agentUrl, auth))

    closeClientFor(result.end)
    TerminalAudit.closed(
        sessionId = sessionId,
        runtimeId = runtimeId,
        end = result.end,
        durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        clientToAgentBytes = result.clientToAgentBytes,
        agentToClientBytes = result.agentToClientBytes,
    )
}

/** Final frames for the way the relay ended. When the client is already gone, nothing is sent. */
private suspend fun WebSocketSession.closeClientFor(end: RelayEnd) {
    when (end) {
        RelayEnd.AGENT_CLOSED -> closeQuietly(CloseReason(CloseReason.Codes.NORMAL, "terminal closed"))
        RelayEnd.AGENT_UNREACHABLE, RelayEnd.AGENT_FAILED -> {
            sendQuietly(errorFrame(UNAVAILABLE_TEXT))
            closeQuietly(CloseReason(CloseReason.Codes.INTERNAL_ERROR, UNAVAILABLE_TEXT))
        }
        RelayEnd.IDLE_TIMEOUT -> {
            sendQuietly(errorFrame(IDLE_TEXT))
            closeQuietly(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ENDED_TEXT))
        }
        RelayEnd.MAX_LIFETIME -> {
            sendQuietly(errorFrame(MAX_LIFETIME_TEXT))
            closeQuietly(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ENDED_TEXT))
        }
        RelayEnd.CLIENT_CLOSED, RelayEnd.CLIENT_FAILED -> Unit
    }
}

private suspend fun WebSocketSession.rejectAndClose() {
    sendQuietly(errorFrame(REJECTED_TEXT))
    closeQuietly(CloseReason(CloseReason.Codes.VIOLATED_POLICY, REJECTED_TEXT))
}

private suspend fun WebSocketSession.sendQuietly(text: String) {
    try {
        send(textFrame(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The peer is already gone; the close that follows is a no-op.
    }
}

private suspend fun WebSocketSession.closeQuietly(reason: CloseReason) {
    try {
        close(reason)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Already closed by the peer.
    }
}

private sealed interface AuthRead {
    class Ok(val request: AuthRequest) : AuthRead
    class Failed(val reason: RejectReason) : AuthRead
}

/** Reads and validates the first frame within [TerminalGatewayConfig.authTimeout]. */
private suspend fun WebSocketSession.readAuthFrame(config: TerminalGatewayConfig): AuthRead = try {
    withTimeout(config.authTimeout.toMillis()) {
        val frame = incoming.receive()
        if (frame !is Frame.Text || !frame.fin || frame.data.size > AuthFrame.MAX_BYTES) {
            AuthRead.Failed(RejectReason.MALFORMED_AUTH)
        } else {
            // Parse errors are swallowed on purpose: their messages can quote the token.
            val request = try {
                AuthFrame.parse(frame.readText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            request?.let { AuthRead.Ok(it) } ?: AuthRead.Failed(RejectReason.MALFORMED_AUTH)
        }
    }
} catch (e: TimeoutCancellationException) {
    AuthRead.Failed(RejectReason.AUTH_TIMEOUT)
} catch (e: ClosedReceiveChannelException) {
    AuthRead.Failed(RejectReason.CLIENT_CLOSED_BEFORE_AUTH)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AuthRead.Failed(RejectReason.MALFORMED_AUTH)
}

/**
 * Adds the optional initial PTY size from the auth frame to the agent URL. Nothing else from
 * the client reaches the agent URL: cwd and shell are not client-controlled.
 */
private fun agentUrlWith(agentUrl: String, auth: AuthRequest): String {
    if (auth.cols == null && auth.rows == null) return agentUrl
    return URLBuilder(agentUrl).apply {
        auth.cols?.let { parameters.append("cols", it.toString()) }
        auth.rows?.let { parameters.append("rows", it.toString()) }
    }.buildString()
}
