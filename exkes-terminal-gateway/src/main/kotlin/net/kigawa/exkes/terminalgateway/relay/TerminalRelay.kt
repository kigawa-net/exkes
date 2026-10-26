package net.kigawa.exkes.terminalgateway.relay

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kigawa.exkes.terminalgateway.config.TerminalGatewayConfig
import net.kigawa.exkes.terminalgateway.protocol.errorFrame
import net.kigawa.exkes.terminalgateway.protocol.textFrame

/** Why a relay ended. [code] is what the audit log records. */
enum class RelayEnd(val code: String) {
    /** The client closed the connection (or sent a Close frame). */
    CLIENT_CLOSED("client_closed"),
    /** The client connection failed while relaying (I/O error, frame too big, ...). */
    CLIENT_FAILED("client_failed"),
    /** The runtime-agent sent a Close frame or closed the connection normally. */
    AGENT_CLOSED("agent_closed"),
    /** The runtime-agent connection failed while relaying. */
    AGENT_FAILED("agent_failed"),
    /** The runtime-agent could not be reached at all. */
    AGENT_UNREACHABLE("agent_unreachable"),
    IDLE_TIMEOUT("idle_timeout"),
    MAX_LIFETIME("max_lifetime"),
}

class RelayResult(
    val end: RelayEnd,
    val clientToAgentBytes: Long,
    val agentToClientBytes: Long,
)

/**
 * Bidirectional relay between one client WebSocket and one runtime-agent WebSocket.
 *
 * Frames are forwarded as they are: the gateway does not parse or rewrite the JSON protocol
 * (docs/runtime-agent-terminal.md). It only enforces transport limits:
 * - text frames only, not fragmented, at most `maxMessageBytes` (binary frames get an `error`
 *   and are dropped; the session continues, as the agent does);
 * - an idle timeout that resets on every relayed frame, and a hard maximum lifetime.
 *
 * Backpressure: each direction is a sequential loop (receive one frame, then send it), so a
 * slow receiver stops that direction from reading, and TCP flow control propagates the stall.
 *
 * Shutdown: the first end reason wins. The other pumps are cancelled, and the agent session is
 * closed in a non-cancellable block. The caller closes the client side with the matching code.
 */
class TerminalRelay(
    private val agentClient: HttpClient,
    private val config: TerminalGatewayConfig,
) {
    /**
     * Connects to [agentUrl] and relays until one side ends. Never throws for connection or
     * I/O failures; they are reported through [RelayResult.end].
     */
    suspend fun relay(client: WebSocketSession, agentUrl: String): RelayResult {
        val agent = try {
            agentClient.webSocketSession(agentUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RelayResult(RelayEnd.AGENT_UNREACHABLE, 0, 0)
        }
        val lastActivity = AtomicLong(System.nanoTime())
        val toAgent = AtomicLong(0)
        val toClient = AtomicLong(0)
        try {
            return coroutineScope {
                val end = CompletableDeferred<RelayEnd>()
                val pumps = listOf(
                    launch { end.complete(pumpClientToAgent(client, agent, lastActivity, toAgent)) },
                    launch { end.complete(pumpAgentToClient(client, agent, lastActivity, toClient)) },
                    launch { end.complete(awaitIdle(lastActivity)) },
                    launch {
                        delay(config.maxLifetime.toMillis())
                        end.complete(RelayEnd.MAX_LIFETIME)
                    },
                )
                val reason = end.await()
                pumps.forEach { it.cancel() }
                RelayResult(reason, toAgent.get(), toClient.get())
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { agent.close() }
            }
        }
    }

    private suspend fun pumpClientToAgent(
        client: WebSocketSession,
        agent: WebSocketSession,
        lastActivity: AtomicLong,
        bytes: AtomicLong,
    ): RelayEnd {
        try {
            for (frame in client.incoming) {
                when (frame) {
                    is Frame.Close -> return RelayEnd.CLIENT_CLOSED
                    is Frame.Text -> {
                        val problem = clientTextProblem(frame)
                        if (problem != null) {
                            client.send(textFrame(errorFrame(problem)))
                            continue
                        }
                        lastActivity.set(System.nanoTime())
                        bytes.addAndGet(frame.data.size.toLong())
                        try {
                            agent.send(Frame.Text(true, frame.data))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return RelayEnd.AGENT_FAILED
                        }
                    }
                    is Frame.Binary -> client.send(textFrame(errorFrame(BINARY_NOT_SUPPORTED)))
                    else -> Unit // Ping / Pong are answered by Ktor itself.
                }
            }
            return RelayEnd.CLIENT_CLOSED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RelayEnd.CLIENT_FAILED
        }
    }

    private suspend fun pumpAgentToClient(
        client: WebSocketSession,
        agent: WebSocketSession,
        lastActivity: AtomicLong,
        bytes: AtomicLong,
    ): RelayEnd {
        try {
            for (frame in agent.incoming) {
                when (frame) {
                    is Frame.Close -> return RelayEnd.AGENT_CLOSED
                    // The protocol only uses text frames. Anything else from the agent is dropped.
                    is Frame.Text -> {
                        if (!frame.fin) continue
                        lastActivity.set(System.nanoTime())
                        bytes.addAndGet(frame.data.size.toLong())
                        try {
                            client.send(Frame.Text(true, frame.data))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return RelayEnd.CLIENT_FAILED
                        }
                    }
                    else -> Unit
                }
            }
            return RelayEnd.AGENT_CLOSED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RelayEnd.AGENT_FAILED
        }
    }

    private suspend fun awaitIdle(lastActivity: AtomicLong): RelayEnd {
        while (true) {
            val idleFor = Duration.ofNanos(System.nanoTime() - lastActivity.get())
            val remaining = config.idleTimeout - idleFor
            if (remaining <= Duration.ZERO) return RelayEnd.IDLE_TIMEOUT
            // Sleep until the deadline, then re-check: activity may have moved it.
            delay(remaining.toMillis().coerceAtLeast(1L))
        }
    }

    private fun clientTextProblem(frame: Frame.Text): String? = when {
        !frame.fin -> FRAGMENTED_NOT_SUPPORTED
        frame.data.size > config.maxMessageBytes -> MESSAGE_TOO_LARGE
        else -> null
    }

    private companion object {
        const val FRAGMENTED_NOT_SUPPORTED = "fragmented messages are not supported"
        const val MESSAGE_TOO_LARGE = "message too large"
        const val BINARY_NOT_SUPPORTED = "binary frames are not supported"
    }
}
