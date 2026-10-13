package net.kigawa.exkes.runtimeagent

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.exkes.runtimeagent.execution.ExecutionRecord
import net.kigawa.exkes.runtimeagent.execution.ExecutionStore
import net.kigawa.exkes.runtimeagent.execution.InMemoryExecutionStore
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionRoutesTest {
    private fun status(text: String) = Json.parseToJsonElement(text).jsonObject["status"]!!.jsonPrimitive.content

    @Test
    fun `start returns 202 then status, logs and SSE stream are available`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        val resp = client.post("/v1/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"r1","command":["sh","-c","echo hi; echo err >&2; exit 2"]}""")
        }
        assertEquals(HttpStatusCode.Accepted, resp.status)

        // SSE stream returns only after the execution finished, with every chunk then an end event.
        val stream = client.get("/v1/executions/r1/logs/stream").bodyAsText()
        assertTrue(stream.contains("\"data\":\"hi\\n\""), stream)
        assertTrue(stream.contains("\"stream\":\"STDERR\""), stream)
        assertTrue(stream.trimEnd().endsWith("event: end\ndata: {}"), stream)

        val st = client.get("/v1/executions/r1").bodyAsText()
        assertEquals("FAILED", status(st))
        assertEquals("2", Json.parseToJsonElement(st).jsonObject["exitCode"]!!.jsonPrimitive.content)

        val logs = client.get("/v1/executions/r1/logs?after=1").bodyAsText()
        assertTrue(logs.contains("\"finished\":true"))
    }

    @Test
    fun `cancel endpoint stops a running execution`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        client.post("/v1/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"r2","command":["sleep","30"]}""")
        }
        assertEquals(HttpStatusCode.Accepted, client.post("/v1/executions/r2/cancel").status)
        var s = ""
        repeat(200) {
            s = status(client.get("/v1/executions/r2").bodyAsText())
            if (s == "CANCELLED") return@repeat
            Thread.sleep(25)
        }
        assertEquals("CANCELLED", s)
    }

    @Test
    fun `unknown id is 404 and bad request is 400, duplicate is 409`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/executions/nope").status)
        val bad = client.post("/v1/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"command":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val body = """{"id":"d1","command":["true"]}"""
        client.post("/v1/executions") { contentType(ContentType.Application.Json); setBody(body) }
        // Identical replay (e.g. after a lost response) is accepted without running again.
        val replay = client.post("/v1/executions") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Accepted, replay.status)
        val dup = client.post("/v1/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"d1","command":["false"]}""")
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)
    }

    @Test
    fun `persistence failure is a 500 and the process is never started`() = testApplication {
        val marker = Files.createTempDirectory("agent-route-marker").resolve("started")
        val failingStore = object : ExecutionStore {
            override fun save(record: ExecutionRecord) = throw IOException("disk unavailable (test)")
            override fun loadAll(): List<ExecutionRecord> = emptyList()
        }
        application { agentModule(failingStore) }
        val resp = client.post("/v1/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"p1","command":["sh","-c","touch $marker"]}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"INTERNAL\""), body)
        assertFalse(body.contains("disk unavailable"), "internal details must not leak: $body")
        Thread.sleep(200)
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `evicted log chunks are reported as truncated and as a gap event`() = testApplication {
        application { agentModule(InMemoryExecutionStore(), maxLogBytes = 1) }
        client.post("/v1/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"t1","command":["sh","-c","echo a; sleep 0.3; echo b; sleep 0.3; echo c"]}""")
        }
        // A reader that keeps up sees every chunk live.
        val live = client.get("/v1/executions/t1/logs/stream").bodyAsText()
        assertTrue(live.contains("\"data\":\"a\\n\""), live)
        // A reader that starts after eviction is told about the gap before the surviving chunk.
        val late = client.get("/v1/executions/t1/logs/stream").bodyAsText()
        assertTrue(late.contains("event: gap\ndata: {\"after\":0,\"droppedBefore\":2}"), late)
        assertFalse(late.contains("\"data\":\"a\\n\""), late)
        assertTrue(late.contains("\"data\":\"c\\n\""), late)

        val logs = client.get("/v1/executions/t1/logs?after=0").bodyAsText()
        assertTrue(logs.contains("\"truncated\":true"), logs)
        assertTrue(logs.contains("\"droppedBefore\":2"), logs)
        val caughtUp = client.get("/v1/executions/t1/logs?after=2").bodyAsText()
        assertTrue(caughtUp.contains("\"truncated\":false"), caughtUp)
    }
}
