package net.kigawa.exkes.terminalgateway

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.closeDataSource
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.TerminalSessionStatus
import net.kigawa.exkes.common.domain.TerminalSessionToken
import net.kigawa.exkes.terminalgateway.support.FakeAgent
import net.kigawa.exkes.terminalgateway.support.GatewayFixture
import net.kigawa.exkes.terminalgateway.support.WAIT_LIMIT
import net.kigawa.exkes.terminalgateway.support.authFrame
import net.kigawa.exkes.terminalgateway.support.closedPort
import net.kigawa.exkes.terminalgateway.support.h2Url
import net.kigawa.exkes.terminalgateway.support.inputFrame
import net.kigawa.exkes.terminalgateway.support.parseJson
import org.slf4j.LoggerFactory

private const val REJECTED = "terminal session rejected"
private const val UNAVAILABLE = "terminal unavailable"
private const val IDLE = "terminal session was idle for too long"
private const val MAX_LIFETIME = "terminal session reached its maximum lifetime"
private const val CODE_NORMAL = 1000
private const val CODE_POLICY = 1008
private const val CODE_INTERNAL = 1011

/**
 * End-to-end tests of `/v1/terminal`. The gateway runs under `testApplication`; the
 * runtime-agent is a real loopback WebSocket server ([FakeAgent]). No fixed sleeps: every wait
 * is bounded by [WAIT_LIMIT] and tied to a frame, a close, or an observable state.
 */
class TerminalGatewayRoutesTest {
    private lateinit var fixture: GatewayFixture
    private lateinit var agent: FakeAgent

    @BeforeTest
    fun setup() {
        fixture = GatewayFixture(h2Url("routes"))
        agent = FakeAgent()
    }

    @AfterTest
    fun teardown() {
        agent.close()
        fixture.close()
    }

    @Test
    fun `valid token relays input and output and client close ends the session`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token, cols = 100, rows = 30))
                send(inputFrame("hello"))
                val output = nextJson()
                assertEquals("output", output.str("type"))
                assertEquals("hello", output.str("data"))

                send(Frame.Text("""{"type":"close"}"""))
                assertEquals("exit", nextJson().str("type"))
                assertEquals(CODE_NORMAL, nextClose().code.toInt())
            }
        }

        assertEquals("100", agent.queries.single()["cols"])
        assertEquals("30", agent.queries.single()["rows"])
        fixture.reconnect()
        assertEquals(
            TerminalSessionStatus.CONSUMED,
            fixture.sessions.findById(issued.session.id)!!.status,
        )
    }

    @Test
    fun `a token cannot be used twice and the agent is not contacted for the second attempt`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token))
                send(inputFrame("first"))
                assertEquals("first", nextJson().str("data"))
            }
            rejectedWith(this, issued.token)
        }

        assertEquals(1, agent.connections.get())
    }

    @Test
    fun `concurrent use of one token admits exactly one connection`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway {
            val admitted = coroutineScope {
                listOf("a", "b").map { label ->
                    async { attemptAdmitted(label, issued.token) }
                }.awaitAll()
            }
            assertEquals(1, admitted.count { it })
        }

        assertEquals(1, agent.connections.get())
    }

    @Test
    fun `expired token is rejected without contacting the agent`() {
        val now = Instant.now()
        val issued = fixture.issueSession(
            fixture.runningRuntime().id,
            issuedAt = now.minusSeconds(600),
            expiresAt = now.minusSeconds(1),
        )

        runGateway { rejectedWith(this, issued.token) }

        assertEquals(0, agent.connections.get())
        fixture.reconnect()
        assertEquals(TerminalSessionStatus.EXPIRED, fixture.sessions.findById(issued.session.id)!!.status)
    }

    @Test
    fun `unknown token is rejected with the same generic message`() {
        fixture.runningRuntime()

        runGateway { rejectedWith(this, TerminalSessionToken.generate()) }

        assertEquals(0, agent.connections.get())
    }

    @Test
    fun `runtime that stopped after issuance is rejected`() {
        val rt = fixture.runningRuntime()
        val issued = fixture.issueSession(rt.id)
        check(fixture.runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING))
        check(fixture.runtimes.transition(rt.id, RuntimeStatus.STOPPING, RuntimeStatus.STOPPED))

        runGateway { rejectedWith(this, issued.token) }

        assertEquals(0, agent.connections.get())
    }

    @Test
    fun `stale Ready observation rejects the connection`() {
        val rt = fixture.runningRuntime(readyAt = Instant.now().minusSeconds(3600))
        val issued = fixture.issueSession(rt.id)

        runGateway { rejectedWith(this, issued.token) }

        assertEquals(0, agent.connections.get())
    }

    @Test
    fun `a first frame that is not an auth request is rejected`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway {
            webSocket("/v1/terminal") {
                send(inputFrame("no auth first"))
                assertRejected(nextJson(), nextClose())
            }
            webSocket("/v1/terminal") {
                // Malformed JSON that still carries the token: must be rejected without echoing it.
                send("""{"type":"auth","token":"${issued.token}""")
                assertRejected(nextJson(), nextClose())
            }
        }

        assertEquals(0, agent.connections.get())
    }

    @Test
    fun `connection without an auth frame is closed when the auth timeout passes`() {
        runGateway(authTimeoutSeconds = 1) {
            webSocket("/v1/terminal") {
                assertRejected(nextJson(), nextClose())
            }
        }

        assertEquals(0, agent.connections.get())
    }

    @Test
    fun `unreachable agent closes the client with 1011 and a generic message`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway(agentPort = closedPort()) {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token))
                assertEquals(UNAVAILABLE, nextJson().str("message"))
                assertEquals(CODE_INTERNAL, nextClose().code.toInt())
            }
        }
    }

    @Test
    fun `idle connection is closed after the idle timeout`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway(idleSeconds = 1) {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token))
                assertEquals(IDLE, nextJson().str("message"))
                assertEquals(CODE_POLICY, nextClose().code.toInt())
            }
        }

        awaitAgentDisconnect()
    }

    @Test
    fun `connection is closed at the maximum lifetime even without idleness`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway(maxLifetimeSeconds = 1, idleSeconds = 600) {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token))
                assertEquals(MAX_LIFETIME, nextJson().str("message"))
                assertEquals(CODE_POLICY, nextClose().code.toInt())
            }
        }

        awaitAgentDisconnect()
    }

    @Test
    fun `client disconnect closes the agent connection`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token))
                send(inputFrame("ping"))
                assertEquals("ping", nextJson().str("data"))
            }
        }

        awaitAgentDisconnect()
    }

    @Test
    fun `binary frame gets an error and the session continues`() {
        val issued = fixture.issueSession(fixture.runningRuntime().id)

        runGateway {
            webSocket("/v1/terminal") {
                send(authFrame(issued.token))
                send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                assertEquals("binary frames are not supported", nextJson().str("message"))
                send(inputFrame("still-here"))
                assertEquals("still-here", nextJson().str("data"))
            }
        }
    }

    @Test
    fun `audit log never contains the token or the relayed keystrokes`() {
        val secret = "super-secret-keystrokes-4711"
        val issued = fixture.issueSession(fixture.runningRuntime().id)
        val capture = startLogCapture()
        try {
            runGateway {
                webSocket("/v1/terminal") {
                    send(authFrame(issued.token))
                    send(inputFrame(secret))
                    assertEquals(secret, nextJson().str("data"))
                    send(Frame.Text("""{"type":"close"}"""))
                    nextJson()
                    nextClose()
                }
                webSocket("/v1/terminal") {
                    send("""{"type":"auth","token":"${issued.token}" trailing""")
                    assertRejected(nextJson(), nextClose())
                }
            }
        } finally {
            capture.stop()
        }

        val lines = capture.events.map { renderEvent(it) }
        assertFalse(lines.any { it.contains(issued.token) }, "token leaked into logs")
        assertFalse(lines.any { it.contains(secret) }, "relayed keystrokes leaked into logs")
        assertTrue(lines.any { it.contains("terminal connection accepted") }, "missing accept audit line")
        assertTrue(lines.any { it.contains("terminal connection closed") }, "missing close audit line")
        assertTrue(lines.any { it.contains("reason=malformed_auth") }, "missing rejection reason code")
    }

    @Test
    fun `gateway does not run database migrations`() {
        val url = h2Url("no-migrations")
        val appConfig = gatewayConfig(url, agentPort = 1)
        testApplication {
            environment { config = appConfig }
            application { module() }
            assertEquals(200, client.get("/health").status.value)
        }

        val ds = createDataSource(DatabaseConfig(url, "sa", ""))
        try {
            val hasTable = ds.connection.use { conn ->
                conn.metaData.getTables(null, null, "TERMINAL_SESSIONS", null).use { rs -> rs.next() }
            }
            assertFalse(hasTable, "the gateway must not create the schema")
        } finally {
            closeDataSource(ds)
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private fun runGateway(
        agentPort: Int = agent.port,
        idleSeconds: Long = 60,
        maxLifetimeSeconds: Long = 60,
        authTimeoutSeconds: Long = 10,
        block: suspend HttpClient.() -> Unit,
    ) = testApplication {
        environment {
            config = gatewayConfig(
                fixture.jdbcUrl,
                agentPort,
                idleSeconds,
                maxLifetimeSeconds,
                authTimeoutSeconds,
            )
        }
        application { module() }
        val client = createClient { install(ClientWebSockets) }
        client.block()
    }

    private fun gatewayConfig(
        jdbcUrl: String,
        agentPort: Int,
        idleSeconds: Long = 60,
        maxLifetimeSeconds: Long = 60,
        authTimeoutSeconds: Long = 10,
    ) = MapApplicationConfig(
        "exkes.database.jdbcUrl" to jdbcUrl,
        "exkes.database.user" to "sa",
        "exkes.database.password" to "",
        "exkes.admission.freshnessSeconds" to "15",
        "exkes.terminalGateway.agentUrlTemplate" to "ws://{podName}:$agentPort/terminal",
        "exkes.terminalGateway.authTimeoutSeconds" to authTimeoutSeconds.toString(),
        "exkes.terminalGateway.idleTimeoutSeconds" to idleSeconds.toString(),
        "exkes.terminalGateway.maxLifetimeSeconds" to maxLifetimeSeconds.toString(),
        "exkes.terminalGateway.maxMessageBytes" to "131072",
    )

    /** Connects with [token] and expects the generic rejection (no agent involved). */
    private suspend fun rejectedWith(client: HttpClient, token: String) {
        client.webSocket("/v1/terminal") {
            send(authFrame(token))
            assertRejected(nextJson(), nextClose())
        }
    }

    /** Connects with [token], proves admission by a round trip, and reports whether it was admitted. */
    private suspend fun HttpClient.attemptAdmitted(label: String, token: String): Boolean {
        var admitted = false
        webSocket("/v1/terminal") {
            send(authFrame(token))
            send(inputFrame("attempt-$label"))
            val reply = nextJson()
            if (reply.str("type") == "output") {
                admitted = true
                send(Frame.Text("""{"type":"close"}"""))
                nextJson()
                nextClose()
            } else {
                assertEquals(REJECTED, reply.str("message"))
                nextClose()
            }
        }
        return admitted
    }

    private fun assertRejected(message: JsonObject, close: CloseReason) {
        assertEquals("error", message.str("type"))
        assertEquals(REJECTED, message.str("message"))
        assertEquals(CODE_POLICY, close.code.toInt())
    }

    private suspend fun DefaultClientWebSocketSession.nextFrame(): Frame =
        withTimeout(WAIT_LIMIT) { incoming.receive() }

    /** The next text frame as JSON. Fails the test if the connection closes first. */
    private suspend fun DefaultClientWebSocketSession.nextJson(): JsonObject {
        while (true) {
            when (val frame = nextFrame()) {
                is Frame.Text -> return parseJson(frame.readText())
                is Frame.Close -> error("connection closed before a text frame: ${frame.readReason()?.code}")
                else -> Unit
            }
        }
    }

    /**
     * The close reason of the connection. Ktor consumes the server's Close frame and closes
     * `incoming` without surfacing it, so the reason is read from `closeReason`.
     */
    private suspend fun DefaultClientWebSocketSession.nextClose(): CloseReason {
        while (true) {
            val frame = try {
                nextFrame()
            } catch (e: ClosedReceiveChannelException) {
                return assertNotNull(withTimeout(WAIT_LIMIT) { closeReason.await() })
            }
            when (frame) {
                is Frame.Close -> return assertNotNull(frame.readReason())
                is Frame.Text -> error("expected a close frame, got a text frame")
                else -> Unit
            }
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun awaitAgentDisconnect() = runBlocking {
        withTimeout(WAIT_LIMIT) { agent.disconnected.await() }
    }

    private class LogCapture(private val root: Logger, val appender: ListAppender<ILoggingEvent>) {
        val events: List<ILoggingEvent> get() = appender.list.toList()

        fun stop() {
            root.detachAppender(appender)
        }
    }

    private fun startLogCapture(): LogCapture {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        root.addAppender(appender)
        return LogCapture(root, appender)
    }

    private fun renderEvent(event: ILoggingEvent): String = buildString {
        append(event.formattedMessage)
        event.throwableProxy?.message?.let { append(' ').append(it) }
    }
}
