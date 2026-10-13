package net.kigawa.exkes.runtimeagent.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.exkes.runtimeagent.execution.ExecutionConflictException
import net.kigawa.exkes.runtimeagent.execution.ExecutionManager
import net.kigawa.exkes.runtimeagent.execution.ExecutionNotFoundException
import net.kigawa.exkes.runtimeagent.execution.ExecutionSnapshot
import net.kigawa.exkes.runtimeagent.execution.InvalidExecutionRequestException
import net.kigawa.exkes.runtimeagent.execution.LogChunk
import net.kigawa.exkes.runtimeagent.execution.StartExecutionRequest
import org.slf4j.LoggerFactory

@Serializable
data class ErrorResponse(val code: String, val message: String)

@Serializable
data class ExecutionList(val items: List<ExecutionSnapshot>)

/**
 * [droppedBefore] is the highest seq evicted from the bounded buffer (0 if none).
 * [truncated] is true when chunks after the requested cursor were evicted, so the client has a gap.
 */
@Serializable
data class LogsResponse(
    val chunks: List<LogChunk>,
    val finished: Boolean,
    val droppedBefore: Long,
    val truncated: Boolean,
)

private val logger = LoggerFactory.getLogger("ExecutionRoutes")
private val sseJson = Json

fun Application.installAgentErrorPages() {
    install(StatusPages) {
        exception<ExecutionNotFoundException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", e.message ?: "not found"))
        }
        exception<ExecutionConflictException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse("CONFLICT", e.message ?: "conflict"))
        }
        exception<InvalidExecutionRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", e.message ?: "invalid"))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "malformed request body"))
        }
        exception<Throwable> { call, e ->
            logger.error("unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL", "internal error"))
        }
    }
}

/**
 * JSON over HTTP, consumed by the control plane (never the reverse).
 *
 * - POST /v1/executions                  start (202)
 * - GET  /v1/executions                  list
 * - GET  /v1/executions/{id}             status + exit code
 * - POST /v1/executions/{id}/cancel      cancel
 * - GET  /v1/executions/{id}/logs?after= ordered chunks with seq > after
 * - GET  /v1/executions/{id}/logs/stream text/event-stream; one `data:` JSON LogChunk per event.
 *                                        `event: gap` reports chunks lost to the buffer limit.
 *                                        Ends with `event: end` when the execution has finished
 */
fun Application.configureExecutionRoutes(manager: ExecutionManager) {
    routing {
        post("/v1/executions") {
            val request = call.receive<StartExecutionRequest>()
            // Blocking file I/O (state persistence) must not run on the Netty event loop.
            val result = withContext(Dispatchers.IO) { manager.start(request) }
            call.respond(HttpStatusCode.Accepted, result.snapshot)
        }
        get("/v1/executions") {
            call.respond(ExecutionList(manager.list()))
        }
        get("/v1/executions/{id}") {
            call.respond(manager.get(call.parameters["id"]!!))
        }
        post("/v1/executions/{id}/cancel") {
            val snapshot = withContext(Dispatchers.IO) { manager.cancel(call.parameters["id"]!!) }
            call.respond(HttpStatusCode.Accepted, snapshot)
        }
        get("/v1/executions/{id}/logs") {
            val log = manager.log(call.parameters["id"]!!)
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val page = log.read(after)
            call.respond(LogsResponse(page.chunks, page.finished, page.droppedBefore, truncated = page.droppedBefore > after))
        }
        get("/v1/executions/{id}/logs/stream") {
            val log = manager.log(call.parameters["id"]!!)
            var cursor = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            call.respondTextWriter(ContentType.Text.EventStream) {
                while (true) {
                    val seen = log.version.value
                    val page = log.read(cursor)
                    if (page.droppedBefore > cursor) {
                        // Chunks in (cursor, droppedBefore] were evicted before this client read them.
                        write("event: gap\ndata: {\"after\":$cursor,\"droppedBefore\":${page.droppedBefore}}\n\n")
                        cursor = page.droppedBefore
                    }
                    for (c in page.chunks) {
                        write("data: ${sseJson.encodeToString(LogChunk.serializer(), c)}\n\n")
                        cursor = c.seq
                    }
                    flush()
                    // The read is atomic: a finished page already holds every remaining chunk.
                    if (page.finished) {
                        write("event: end\ndata: {}\n\n")
                        flush()
                        break
                    }
                    log.version.first { it != seen }
                }
            }
        }
    }
}
