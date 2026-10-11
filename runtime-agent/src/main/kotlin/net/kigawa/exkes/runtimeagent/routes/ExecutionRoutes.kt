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
import kotlinx.coroutines.flow.first
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

@Serializable
data class LogsResponse(val chunks: List<LogChunk>, val finished: Boolean)

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
 * - GET  /v1/executions/{id}/logs/stream text/event-stream; one `data:` JSON LogChunk per event,
 *                                        ends with `event: end` when the execution has finished
 */
fun Application.configureExecutionRoutes(manager: ExecutionManager) {
    routing {
        post("/v1/executions") {
            val snapshot = manager.start(call.receive<StartExecutionRequest>())
            call.respond(HttpStatusCode.Accepted, snapshot)
        }
        get("/v1/executions") {
            call.respond(ExecutionList(manager.list()))
        }
        get("/v1/executions/{id}") {
            call.respond(manager.get(call.parameters["id"]!!))
        }
        post("/v1/executions/{id}/cancel") {
            call.respond(HttpStatusCode.Accepted, manager.cancel(call.parameters["id"]!!))
        }
        get("/v1/executions/{id}/logs") {
            val log = manager.log(call.parameters["id"]!!)
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val (chunks, closed) = log.read(after)
            call.respond(LogsResponse(chunks, closed))
        }
        get("/v1/executions/{id}/logs/stream") {
            val log = manager.log(call.parameters["id"]!!)
            var cursor = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            call.respondTextWriter(ContentType.Text.EventStream) {
                while (true) {
                    val seen = log.version.value
                    val (chunks, closed) = log.read(cursor)
                    for (c in chunks) {
                        write("data: ${sseJson.encodeToString(LogChunk.serializer(), c)}\n\n")
                        cursor = c.seq
                    }
                    flush()
                    if (closed && log.read(cursor).first.isEmpty()) {
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
