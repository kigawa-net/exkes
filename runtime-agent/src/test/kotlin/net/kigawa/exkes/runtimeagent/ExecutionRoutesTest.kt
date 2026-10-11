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
import net.kigawa.exkes.runtimeagent.execution.InMemoryExecutionStore
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val dup = client.post("/v1/executions") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Conflict, dup.status)
    }
}
