package net.kigawa.exkes.api

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import net.kigawa.exkes.api.routes.configureHealthRoutes
import net.kigawa.exkes.api.routes.configureWorkspaceRoutes
import net.kigawa.exkes.api.routes.installErrorPages
import net.kigawa.exkes.api.workspace.WorkspaceService
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * Route tests on testApplication + H2 (MODE=MySQL). The schema is created by
 * Flyway, so this doubles as an integration test of migration + repository +
 * routes (plan chapter 11.1).
 */
class WorkspaceRoutesTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: WorkspaceRepository
    private lateinit var service: WorkspaceService

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_routes_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = WorkspaceRepository()
        service = WorkspaceService(
            repository = repository,
            workspaceConfig = WorkspaceConfig(
                storageClass = "rook-cephfs",
                defaultStorageSizeBytes = 1024L * 1024 * 1024,
            ),
        )
    }

    private fun Application.testModule() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Same error handling as Application.module (StatusPages).
        installErrorPages()
        routing {
            configureHealthRoutes()
            configureWorkspaceRoutes(service)
            // Test-only route exercising the StatusPages catch-all handler.
            get("/test/unhandled") {
                throw IllegalStateException("connect failed: jdbc:mariadb://exkes:hunter2@mariadb:3306/exkes")
            }
        }
    }

    private fun uniqueName(prefix: String): String = "$prefix-${System.nanoTime()}"

    private fun extractId(body: String): String =
        Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private suspend fun HttpClient.createWorkspace(body: String): HttpResponse = post("/v1/workspaces") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application { testModule() }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `create returns accepted with creating status`() = testApplication {
        application { testModule() }

        val response = client.createWorkspace("""{"name":"${uniqueName("ws-create")}"}""")
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"CREATING\""))
        assertTrue(body.contains("\"storageClass\":\"rook-cephfs\""))
        assertTrue(body.contains("\"storageSizeBytes\":1073741824"))
        assertTrue(body.contains("\"pvcName\":null"))
    }

    @Test
    fun `create stores labels and custom storage size`() = testApplication {
        application { testModule() }

        val response = client.createWorkspace(
            """
            {"name":"${uniqueName("ws-labels")}",
             "storageSizeBytes":2147483648,
             "labels":{"project":"ai-scheduler"}}
            """.trimIndent(),
        )
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"project\":\"ai-scheduler\""))
        assertTrue(body.contains("\"storageSizeBytes\":2147483648"))
    }

    @Test
    fun `create with duplicate name returns conflict`() = testApplication {
        application { testModule() }

        val name = uniqueName("ws-dup")
        assertEquals(HttpStatusCode.Accepted, client.createWorkspace("""{"name":"$name"}""").status)

        val second = client.createWorkspace("""{"name":"$name"}""")
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertTrue(second.bodyAsText().contains("\"code\":\"CONFLICT\""))
    }

    @Test
    fun `create with invalid name returns validation error`() = testApplication {
        application { testModule() }

        val blank = client.createWorkspace("""{"name":""}""")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertTrue(blank.bodyAsText().contains("\"code\":\"VALIDATION_ERROR\""))

        val badChars = client.createWorkspace("""{"name":"not a valid name!"}""")
        assertEquals(HttpStatusCode.BadRequest, badChars.status)

        val tooLong = client.createWorkspace("""{"name":"${"a".repeat(129)}"}""")
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }

    @Test
    fun `create with non positive storage size returns validation error`() = testApplication {
        application { testModule() }

        val response = client.createWorkspace("""{"name":"${uniqueName("ws-size")}","storageSizeBytes":0}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"VALIDATION_ERROR\""))
    }

    @Test
    fun `create with malformed json returns validation error`() = testApplication {
        application { testModule() }

        val response = client.createWorkspace("this is not json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"VALIDATION_ERROR\""))
    }

    @Test
    fun `get returns the created workspace`() = testApplication {
        application { testModule() }

        val name = uniqueName("ws-get")
        val created = client.createWorkspace("""{"name":"$name"}""")
        val id = extractId(created.bodyAsText())

        val response = client.get("/v1/workspaces/$id")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(id))
        assertTrue(body.contains(name))
        assertTrue(body.contains("\"status\":\"CREATING\""))
    }

    @Test
    fun `get unknown workspace returns 404`() = testApplication {
        application { testModule() }

        val response = client.get("/v1/workspaces/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"NOT_FOUND\""))
    }

    @Test
    fun `list returns created workspaces`() = testApplication {
        application { testModule() }

        val name = uniqueName("ws-list")
        val created = client.createWorkspace("""{"name":"$name"}""")
        val id = extractId(created.bodyAsText())

        val response = client.get("/v1/workspaces?status=CREATING&limit=100")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(id))
        assertTrue(body.contains("\"limit\":100"))
        assertTrue(body.contains("\"offset\":0"))
    }

    @Test
    fun `list rejects unknown status filter`() = testApplication {
        application { testModule() }

        val response = client.get("/v1/workspaces?status=BOGUS")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"VALIDATION_ERROR\""))
    }

    @Test
    fun `list rejects invalid limit`() = testApplication {
        application { testModule() }

        val nonNumeric = client.get("/v1/workspaces?limit=abc")
        assertEquals(HttpStatusCode.BadRequest, nonNumeric.status)

        val outOfRange = client.get("/v1/workspaces?limit=0")
        assertEquals(HttpStatusCode.BadRequest, outOfRange.status)

        val negativeOffset = client.get("/v1/workspaces?offset=-1")
        assertEquals(HttpStatusCode.BadRequest, negativeOffset.status)
    }

    @Test
    fun `delete returns accepted and moves workspace to deleting`() = testApplication {
        application { testModule() }

        val created = client.createWorkspace("""{"name":"${uniqueName("ws-delete")}"}""")
        val id = extractId(created.bodyAsText())

        val deleted = client.delete("/v1/workspaces/$id")
        assertEquals(HttpStatusCode.Accepted, deleted.status)
        assertTrue(deleted.bodyAsText().contains("\"status\":\"DELETING\""))

        // The row is still visible while DELETING (DELETED hides it as 404).
        val fetched = client.get("/v1/workspaces/$id")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertTrue(fetched.bodyAsText().contains("\"status\":\"DELETING\""))
    }

    @Test
    fun `delete twice stays accepted`() = testApplication {
        application { testModule() }

        val created = client.createWorkspace("""{"name":"${uniqueName("ws-delete-twice")}"}""")
        val id = extractId(created.bodyAsText())

        assertEquals(HttpStatusCode.Accepted, client.delete("/v1/workspaces/$id").status)
        assertEquals(HttpStatusCode.Accepted, client.delete("/v1/workspaces/$id").status)
    }

    @Test
    fun `delete unknown workspace returns 404`() = testApplication {
        application { testModule() }

        val response = client.delete("/v1/workspaces/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"NOT_FOUND\""))
    }

    /** DELETE is accepted while the PVC is still being provisioned (plan chapter 6.2). */
    @Test
    fun `delete while creating is accepted and moves workspace to deleting`() = testApplication {
        application { testModule() }

        val created = client.createWorkspace("""{"name":"${uniqueName("ws-delete-creating")}"}""")
        val id = extractId(created.bodyAsText())
        assertTrue(created.bodyAsText().contains("\"status\":\"CREATING\""))

        val deleted = client.delete("/v1/workspaces/$id")
        assertEquals(HttpStatusCode.Accepted, deleted.status)
        val body = deleted.bodyAsText()
        assertTrue(body.contains("\"status\":\"DELETING\""))
        assertTrue(body.contains("\"pvcName\":null"), "the PVC name is only known once provisioned")
        assertEquals(WorkspaceStatus.DELETING, repository.findById(id)!!.status)
    }

    @Test
    fun `deleted workspace returns 404 and disappears from the list`() = testApplication {
        application { testModule() }

        val survivorId = extractId(client.createWorkspace("""{"name":"${uniqueName("ws-tombstone")}"}""").bodyAsText())
        val deletedId = extractId(client.createWorkspace("""{"name":"${uniqueName("ws-tombstone")}"}""").bodyAsText())
        assertTrue(repository.transition(deletedId, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))
        assertTrue(repository.transition(deletedId, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED))

        val fetched = client.get("/v1/workspaces/$deletedId")
        assertEquals(HttpStatusCode.NotFound, fetched.status)
        assertTrue(fetched.bodyAsText().contains("\"code\":\"NOT_FOUND\""))

        val listed = client.get("/v1/workspaces?limit=100")
        assertEquals(HttpStatusCode.OK, listed.status)
        val listBody = listed.bodyAsText()
        assertTrue(listBody.contains(survivorId))
        assertTrue(!listBody.contains(deletedId), "DELETED rows must not appear in the list")

        // An explicit DELETED filter must not resurrect the tombstone either.
        val filtered = client.get("/v1/workspaces?status=DELETED&limit=100")
        assertEquals(HttpStatusCode.OK, filtered.status)
        assertTrue(!filtered.bodyAsText().contains(deletedId))
    }

    /**
     * StatusPages must answer the common error format for anything unhandled and
     * must not forward the exception message: JDBC URLs, credentials and K8s API
     * response bodies have to stay in the server log.
     */
    @Test
    fun `unhandled exception returns internal error without leaking details`() = testApplication {
        application { testModule() }

        val response = client.get("/test/unhandled")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"code\":\"INTERNAL\""))
        assertTrue(body.contains("\"message\":\"internal error\""))
        assertTrue(!body.contains("hunter2"), "credentials must never reach the client")
        assertTrue(!body.contains("jdbc:mariadb"), "the JDBC URL must never reach the client")
    }
}
