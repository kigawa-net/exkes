package net.kigawa.exkes.api

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import net.kigawa.exkes.api.routes.configureRuntimeRoutes
import net.kigawa.exkes.api.routes.configureTerminalSessionRoutes
import net.kigawa.exkes.api.routes.installErrorPages
import net.kigawa.exkes.api.runtime.IssuedTerminalSession
import net.kigawa.exkes.api.runtime.RuntimeService
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.TerminalSessionRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.TerminalSessionStatus
import net.kigawa.exkes.common.domain.TerminalSessionToken
import net.kigawa.exkes.common.domain.WorkspaceStatus
import org.slf4j.LoggerFactory

/**
 * POST /v1/runtimes/{id}/terminal-sessions: issuance, status mapping (404 / 409 / 503),
 * one-time token semantics, hash-only storage and audit logging. H2 (MODE=MySQL) + Flyway.
 */
class TerminalSessionRoutesTest {
    private lateinit var dataSource: DataSource
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var runtimes: RuntimeRepository
    private lateinit var sessions: TerminalSessionRepository
    private lateinit var runtimeService: RuntimeService
    private lateinit var now: Instant
    private lateinit var auditAppender: ListAppender<ILoggingEvent>
    private lateinit var auditLogger: Logger

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_terminal_routes_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        workspaces = WorkspaceRepository()
        runtimes = RuntimeRepository()
        sessions = TerminalSessionRepository()
        RuntimeTemplateRepository().create(
            id = "terminal-route-template",
            name = "Terminal Route Template",
            description = null,
            image = "example/image:latest",
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        runtimeService = newService(TTL_SECONDS)

        auditLogger = LoggerFactory.getLogger("TerminalSessionAudit") as Logger
        auditAppender = ListAppender<ILoggingEvent>().also {
            it.start()
            auditLogger.addAppender(it)
        }
    }

    @AfterTest
    fun teardown() {
        auditLogger.detachAppender(auditAppender)
        auditAppender.stop()
    }

    private fun newService(ttlSeconds: Long): RuntimeService = RuntimeService(
        runtimeRepository = runtimes,
        executionRepository = ExecutionRepository(),
        templateRepository = RuntimeTemplateRepository(),
        workspaceRepository = workspaces,
        admissionFreshnessSeconds = 15L,
        clock = { now },
        terminalSessionRepository = sessions,
        terminalSessionTtlSeconds = ttlSeconds,
    )

    private fun Application.testModule(service: RuntimeService = runtimeService) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        installErrorPages()
        routing {
            configureRuntimeRoutes(service)
            configureTerminalSessionRoutes(service)
        }
    }

    /** A workspace with a verified PVC placement and one runtime in [status]. */
    private fun runtimeIn(status: RuntimeStatus, readyAt: Instant? = now): String {
        val workspace = workspaces.create(uniqueName("ts-ws"), "rook-cephfs", 1024L, emptyMap())
        val namespace = "platform-exkes-stg-sandbox"
        assertTrue(workspaces.reservePlacementForCreating(workspace.id, namespace))
        assertTrue(workspaces.setPvcName(workspace.id, "ws-${workspace.id}"))
        assertTrue(workspaces.recordVerifiedPvcUid(workspace.id, namespace, "uid-${workspace.id}"))
        assertTrue(workspaces.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        val runtime = runtimes.create(workspace.id, "terminal-route-template", null, null, null, null)
        if (status != RuntimeStatus.CREATING) {
            runtimes.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING)
            if (status != RuntimeStatus.STARTING) {
                runtimes.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING)
            }
        }
        if (status == RuntimeStatus.DELETED) {
            runtimes.transition(runtime.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
            runtimes.transition(runtime.id, RuntimeStatus.STOPPING, RuntimeStatus.STOPPED)
            runtimes.transition(runtime.id, RuntimeStatus.STOPPED, RuntimeStatus.DELETING)
            runtimes.transition(runtime.id, RuntimeStatus.DELETING, RuntimeStatus.DELETED)
        }
        if (status == RuntimeStatus.RUNNING && readyAt != null) {
            assertTrue(runtimes.recordReadyObserved(runtime.id, readyAt))
        }
        return runtime.id
    }

    private fun uniqueName(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun sessionCount(runtimeId: String): Int = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM terminal_sessions WHERE runtime_id = ?").use { ps ->
            ps.setString(1, runtimeId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun tokenOf(body: String): String =
        Regex("\"token\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `issuing a session for a RUNNING runtime returns 201 with a token and no-store`() = testApplication {
        application { testModule() }
        val runtimeId = runtimeIn(RuntimeStatus.RUNNING, now)

        val response = client.post("/v1/runtimes/$runtimeId/terminal-sessions")

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        assertTrue(body.contains("\"runtimeId\":\"$runtimeId\""), body)
        assertTrue(body.contains("\"status\":\"ISSUED\""), body)
        assertTrue(body.contains("\"expiresAt\":\"${now.plusSeconds(TTL_SECONDS)}\""), body)
        // 32 random bytes, base64url without padding.
        assertEquals(43, tokenOf(body).length)
        assertEquals(1, sessionCount(runtimeId))
    }

    @Test
    fun `the issued token is consumable exactly once`() = testApplication {
        application { testModule() }
        val runtimeId = runtimeIn(RuntimeStatus.RUNNING, now)
        val token = tokenOf(client.post("/v1/runtimes/$runtimeId/terminal-sessions").bodyAsText())

        assertNotNull(sessions.consume(token, now.plusSeconds(1)))
        assertNull(sessions.consume(token, now.plusSeconds(2)), "second consumption must fail")
    }

    @Test
    fun `the database stores only the SHA-256 of the token`() = testApplication {
        application { testModule() }
        val runtimeId = runtimeIn(RuntimeStatus.RUNNING, now)
        val token = tokenOf(client.post("/v1/runtimes/$runtimeId/terminal-sessions").bodyAsText())

        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT * FROM terminal_sessions WHERE runtime_id = ?").use { ps ->
                ps.setString(1, runtimeId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(sha256Hex(token), rs.getString("token_hash"))
                    assertEquals(TerminalSessionStatus.ISSUED.name, rs.getString("status"))
                    for (column in 1..rs.metaData.columnCount) {
                        val value = rs.getString(column)
                        assertFalse(value != null && value.contains(token), "column ${rs.metaData.getColumnName(column)} leaks the token")
                    }
                }
            }
        }
    }

    @Test
    fun `each POST issues a distinct session and token`() = testApplication {
        application { testModule() }
        val runtimeId = runtimeIn(RuntimeStatus.RUNNING, now)

        val first = tokenOf(client.post("/v1/runtimes/$runtimeId/terminal-sessions").bodyAsText())
        val second = tokenOf(client.post("/v1/runtimes/$runtimeId/terminal-sessions").bodyAsText())

        assertFalse(first == second, "tokens must not repeat")
        assertEquals(2, sessionCount(runtimeId))
    }

    @Test
    fun `an unknown runtime returns 404 NOT_FOUND`() = testApplication {
        application { testModule() }

        val response = client.post("/v1/runtimes/${UUID.randomUUID()}/terminal-sessions")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"NOT_FOUND\""))
    }

    @Test
    fun `a DELETED runtime returns 404 and issues nothing`() = testApplication {
        application { testModule() }
        val runtimeId = runtimeIn(RuntimeStatus.DELETED)

        val response = client.post("/v1/runtimes/$runtimeId/terminal-sessions")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, sessionCount(runtimeId))
    }

    @Test
    fun `a non-RUNNING runtime returns 409 CONFLICT and issues nothing`() = testApplication {
        application { testModule() }
        val creating = runtimeIn(RuntimeStatus.CREATING)
        val starting = runtimeIn(RuntimeStatus.STARTING)

        for (runtimeId in listOf(creating, starting)) {
            val response = client.post("/v1/runtimes/$runtimeId/terminal-sessions")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("\"code\":\"CONFLICT\""), response.bodyAsText())
            assertEquals(0, sessionCount(runtimeId))
        }
    }

    @Test
    fun `a RUNNING runtime without a fresh Ready observation returns 503 and issues nothing`() = testApplication {
        application { testModule() }
        val neverReady = runtimeIn(RuntimeStatus.RUNNING, readyAt = null)
        val stale = runtimeIn(RuntimeStatus.RUNNING, readyAt = now.minusSeconds(60))

        for (runtimeId in listOf(neverReady, stale)) {
            val response = client.post("/v1/runtimes/$runtimeId/terminal-sessions")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("\"code\":\"UNAVAILABLE\""), response.bodyAsText())
            assertEquals(0, sessionCount(runtimeId))
        }
    }

    @Test
    fun `the session expiry follows the configured TTL`() = testApplication {
        val service = newService(60L)
        application { testModule(service) }
        val runtimeId = runtimeIn(RuntimeStatus.RUNNING, now)

        val body = client.post("/v1/runtimes/$runtimeId/terminal-sessions").bodyAsText()

        assertTrue(body.contains("\"expiresAt\":\"${now.plusSeconds(60)}\""), body)
    }

    @Test
    fun `issuance is audit-logged with identifiers and never with the token`() = testApplication {
        application { testModule() }
        val runtimeId = runtimeIn(RuntimeStatus.RUNNING, now)

        val token = tokenOf(client.post("/v1/runtimes/$runtimeId/terminal-sessions").bodyAsText())

        val messages = auditAppender.list.map { it.formattedMessage }
        assertEquals(1, messages.size, messages.toString())
        assertTrue(messages.single().contains("runtimeId=$runtimeId"), messages.single())
        assertTrue(messages.single().contains("sessionId="), messages.single())
        assertTrue(messages.none { it.contains(token) }, "token must never be logged")
    }

    @Test
    fun `IssuedTerminalSession does not expose the token through toString`() {
        val session = sessions.create(
            runtimeId = runtimeIn(RuntimeStatus.RUNNING, now),
            tokenHash = TerminalSessionToken.hash("secret-token-value"),
            issuedAt = now,
            expiresAt = now.plusSeconds(TTL_SECONDS),
        )

        val text = IssuedTerminalSession(session, "secret-token-value").toString()

        assertFalse(text.contains("secret-token-value"), text)
        assertTrue(text.contains("token=<redacted>"), text)
    }

    @Test
    fun `a TTL outside 1 to 3600 seconds is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { newService(0L) }
        assertFailsWith<IllegalArgumentException> { newService(3601L) }
    }

    companion object {
        private const val TTL_SECONDS = 300L
    }
}
