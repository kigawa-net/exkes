package net.kigawa.exkes.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import net.kigawa.exkes.api.routes.configureExecutionRoutes
import net.kigawa.exkes.api.routes.configureRuntimeRoutes
import net.kigawa.exkes.api.routes.configureWorkspaceRoutes
import net.kigawa.exkes.api.routes.installErrorPages
import net.kigawa.exkes.api.runtime.RuntimeService
import net.kigawa.exkes.api.workspace.WorkspaceService
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.common.db.ExecutionRepository
import net.kigawa.exkes.common.domain.ExecutionStatus
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * Route tests for Idempotency-Key on POST, execution labels, the name filter,
 * and execution cancel. H2 (MODE=MySQL) with Flyway-created schema.
 */
class RuntimeRoutesTest {
    private lateinit var dataSource: DataSource
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var runtimes: RuntimeRepository
    private lateinit var executions: ExecutionRepository
    private lateinit var workspaceService: WorkspaceService
    private lateinit var runtimeService: RuntimeService

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_runtime_routes_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        workspaces = WorkspaceRepository()
        runtimes = RuntimeRepository()
        executions = ExecutionRepository()
        val templates = RuntimeTemplateRepository()
        templates.create(
            id = "route-template",
            name = "Route Template",
            description = null,
            image = "example/image:latest",
            defaultResourcesJson = null,
            defaultEnvJson = null,
            defaultTtlSeconds = null,
            labelsJson = null,
        )
        workspaceService = WorkspaceService(workspaces, WorkspaceConfig("rook-cephfs", 1024L * 1024 * 1024))
        runtimeService = RuntimeService(runtimes, executions, templates, workspaces)
    }

    private fun Application.testModule() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        installErrorPages()
        routing {
            configureWorkspaceRoutes(workspaceService)
            configureRuntimeRoutes(runtimeService)
            configureExecutionRoutes(runtimeService)
        }
    }

    private fun uniqueName(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun idOf(body: String): String = Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    /**
     * Creates a READY workspace with one RUNNING runtime that has a fresh Ready observation
     * (the controller's side, simulated), and returns (workspaceId, runtimeId).
     */
    private fun runningRuntime(): Pair<String, String> {
        val workspace = workspaces.create(uniqueName("rt-ws"), "rook-cephfs", 1024L, emptyMap())
        workspaces.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY)
        val runtime = runtimes.create(workspace.id, "route-template", null, null, null, null)
        runtimes.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING)
        runtimes.transition(runtime.id, RuntimeStatus.STARTING, RuntimeStatus.RUNNING)
        assertTrue(runtimes.recordReadyObserved(runtime.id, Instant.now()))
        return workspace.id to runtime.id
    }

    private suspend fun io.ktor.client.HttpClient.postJson(
        path: String,
        body: String,
        key: String? = null,
    ): io.ktor.client.statement.HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        key?.let { header("Idempotency-Key", it) }
        setBody(body)
    }

    @Test
    fun `same workspace idempotency key returns the first workspace and marks replay`() = testApplication {
        application { testModule() }
        val name = uniqueName("ws-idem")
        val first = client.postJson("/v1/workspaces", """{"name":"$name"}""", key = "key-$name")
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertNull(first.headers["Idempotent-Replayed"])

        val second = client.postJson("/v1/workspaces", """{"name":"$name"}""", key = "key-$name")
        assertEquals(HttpStatusCode.Accepted, second.status)
        assertEquals("true", second.headers["Idempotent-Replayed"])
        assertEquals(idOf(first.bodyAsText()), idOf(second.bodyAsText()))
    }

    @Test
    fun `reusing a workspace idempotency key with another body returns 422`() = testApplication {
        application { testModule() }
        val key = uniqueName("key")
        client.postJson("/v1/workspaces", """{"name":"${uniqueName("ws-a")}"}""", key = key)
        val response = client.postJson("/v1/workspaces", """{"name":"${uniqueName("ws-b")}"}""", key = key)
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"VALIDATION_ERROR\""))
    }

    @Test
    fun `malformed idempotency key returns validation error`() = testApplication {
        application { testModule() }
        val response = client.postJson("/v1/workspaces", """{"name":"${uniqueName("ws-bad")}"}""", key = "has space")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `workspace list filters by exact name`() = testApplication {
        application { testModule() }
        val name = uniqueName("ws-filter")
        client.postJson("/v1/workspaces", """{"name":"$name"}""")
        client.postJson("/v1/workspaces", """{"name":"$name-other"}""")
        val body = client.get("/v1/workspaces?name=$name").bodyAsText()
        assertTrue(body.contains("\"total\":1"), body)
        assertTrue(body.contains("\"name\":\"$name\""), body)
    }

    @Test
    fun `execution with labels and idempotency key is created once and filterable by label`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val path = "/v1/runtimes/$runtimeId/executions"
        val body = """{"type":"SYSTEM","labels":{"kivra.run-id":"run-1"}}"""

        val first = client.postJson(path, body, key = "exec-key-$runtimeId")
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertTrue(first.bodyAsText().contains("\"labels\":{\"kivra.run-id\":\"run-1\"}"))
        val second = client.postJson(path, body, key = "exec-key-$runtimeId")
        assertEquals("true", second.headers["Idempotent-Replayed"])
        assertEquals(idOf(first.bodyAsText()), idOf(second.bodyAsText()))

        val listed = client.get("$path?label=kivra.run-id=run-1").bodyAsText()
        assertTrue(listed.contains("\"total\":1"), listed)
        val none = client.get("$path?label=kivra.run-id=run-2").bodyAsText()
        assertTrue(none.contains("\"total\":0"), none)
    }

    @Test
    fun `replay of an existing execution is answered while admission is closed and creates nothing new`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val path = "/v1/runtimes/$runtimeId/executions"
        val body = """{"type":"SYSTEM"}"""
        val key = "replay-closed-$runtimeId"

        val first = client.postJson(path, body, key = key)
        assertEquals(HttpStatusCode.Accepted, first.status)
        // The controller reports Ready=False: admission is closed for new work.
        assertTrue(runtimes.clearReadyObservation(runtimeId))

        val replay = client.postJson(path, body, key = key)
        assertEquals(HttpStatusCode.Accepted, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replayed"])
        assertEquals(idOf(first.bodyAsText()), idOf(replay.bodyAsText()))
        val listed = client.get(path).bodyAsText()
        assertTrue(listed.contains("\"total\":1"), listed)
    }

    @Test
    fun `new execution with a key is refused with 503 while admission is closed and nothing is stored`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val path = "/v1/runtimes/$runtimeId/executions"
        assertTrue(runtimes.clearReadyObservation(runtimeId))

        val refused = client.postJson(path, """{"type":"SYSTEM"}""", key = "fresh-closed-$runtimeId")
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        val listed = client.get(path).bodyAsText()
        assertTrue(listed.contains("\"total\":0"), listed)
    }

    @Test
    fun `invalid execution label key returns validation error`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val response = client.postJson(
            "/v1/runtimes/$runtimeId/executions",
            """{"type":"SYSTEM","labels":{"bad key":"x"}}""",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cancel records a request as CANCELLING, not CANCELLED, and is idempotent afterwards`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val path = "/v1/runtimes/$runtimeId/executions"
        val created = client.postJson(path, """{"type":"SYSTEM"}""")
        val id = idOf(created.bodyAsText())

        val cancelled = client.post("$path/$id/cancel")
        assertEquals(HttpStatusCode.Accepted, cancelled.status)
        assertTrue(cancelled.bodyAsText().contains("\"status\":\"CANCELLING\""))
        assertFalse(cancelled.bodyAsText().contains("\"status\":\"CANCELLED\""))
        assertTrue(cancelled.bodyAsText().contains("\"cancelRequestedAt\":\""))

        // Repeated cancel: 200 with the current state, no new transition.
        val again = client.post("$path/$id/cancel")
        assertEquals(HttpStatusCode.OK, again.status)
        assertTrue(again.bodyAsText().contains("\"status\":\"CANCELLING\""))

        // GET reflects the same non-terminal state; a stop is not proven by the API.
        val fetched = client.get("$path/$id")
        assertTrue(fetched.bodyAsText().contains("\"status\":\"CANCELLING\""))
        assertTrue(fetched.bodyAsText().contains("\"finishedAt\":null"))

        // CANCELLING is non-terminal and shows up under the status filter.
        val listed = client.get("$path?status=cancelling")
        assertTrue(listed.bodyAsText().contains("\"total\":1"))
    }

    @Test
    fun `cancel of a running execution keeps it non-terminal until the stop is confirmed`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val path = "/v1/runtimes/$runtimeId/executions"
        val id = idOf(client.postJson(path, """{"type":"SYSTEM"}""").bodyAsText())
        // Simulate runtime-agent having started the process.
        assertTrue(executions.transition(id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(executions.transition(id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))

        val cancelled = client.post("$path/$id/cancel")
        assertEquals(HttpStatusCode.Accepted, cancelled.status)
        assertTrue(cancelled.bodyAsText().contains("\"status\":\"CANCELLING\""))
        assertFalse(executions.findById(id)!!.isTerminal)
    }

    @Test
    fun `cancel of a terminal execution returns it unchanged with 200`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val path = "/v1/runtimes/$runtimeId/executions"
        val id = idOf(client.postJson(path, """{"type":"SYSTEM"}""").bodyAsText())
        assertTrue(executions.transition(id, ExecutionStatus.QUEUED, ExecutionStatus.STARTING))
        assertTrue(executions.transition(id, ExecutionStatus.STARTING, ExecutionStatus.RUNNING))
        assertTrue(executions.transition(id, ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED, exitCode = 0))

        val response = client.post("$path/$id/cancel")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"SUCCEEDED\""))
        assertTrue(response.bodyAsText().contains("\"cancelRequestedAt\":null"))
    }

    @Test
    fun `cancel of an unknown execution returns not found in the common format`() = testApplication {
        application { testModule() }
        val (_, runtimeId) = runningRuntime()
        val response = client.post("/v1/runtimes/$runtimeId/executions/does-not-exist/cancel")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"NOT_FOUND\""))
    }
}
