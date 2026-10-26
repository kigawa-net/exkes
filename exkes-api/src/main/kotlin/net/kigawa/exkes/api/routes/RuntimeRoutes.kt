package net.kigawa.exkes.api.routes

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import net.kigawa.exkes.api.workspace.InvalidWorkspaceRequestException
import net.kigawa.exkes.api.runtime.RuntimeService
import net.kigawa.exkes.common.domain.ExecutionStatus
import net.kigawa.exkes.common.domain.ExecutionType
import net.kigawa.exkes.common.domain.RuntimeStatus

/**
 * /v1/workspaces/{workspaceId}/runtimes routes (plan chapter 6.1 / issue #1 "Runtime" section).
 *
 * POST answers 202 Accepted immediately: it only writes desired state (a CREATING row).
 * Pod provisioning is performed asynchronously by exkes-controller.
 *
 * Error mapping lives in [installErrorPages] (StatusPages) so that every
 * route answers the common error format (plan chapter 6.2).
 */
fun Route.configureRuntimeRoutes(service: RuntimeService) {
    route("/v1/workspaces/{workspaceId}/runtimes") {
        post {
            val workspaceId = call.parameters["workspaceId"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val request = receiveBody<CreateRuntimeRequest>(call)
            val result = service.createRuntime(
                workspaceId = workspaceId,
                templateId = request.templateId,
                resourcesJson = request.resourcesJson,
                envJson = request.envJson,
                ttlSeconds = request.ttlSeconds,
                labels = request.labels,
                idempotencyKey = idempotencyKeyOf(call),
            )
            respondCreated(call, result) { it.toResponse() }
        }

        get {
            val workspaceId = call.parameters["workspaceId"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val statusParam = call.request.queryParameters["status"]
            val status = statusParam?.let { raw ->
                try {
                    RuntimeStatus.valueOf(raw.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw InvalidWorkspaceRequestException("unknown status: $raw")
                }
            }
            val limit = queryInt(call, "limit", 50)
            val offset = queryInt(call, "offset", 0)

            val result = service.listRuntimes(workspaceId, status, limit, offset)
            call.respond(
                HttpStatusCode.OK,
                RuntimeListResponse(
                    items = result.items.map { it.toResponse() },
                    total = result.total,
                    limit = limit,
                    offset = offset,
                ),
            )
        }

        get("/{id}") {
            val workspaceId = call.parameters["workspaceId"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            call.respond(HttpStatusCode.OK, service.getRuntime(workspaceId, id).toResponse())
        }

        post("/{id}/start") {
            val workspaceId = call.parameters["workspaceId"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val runtime = service.startRuntime(workspaceId, id)
            call.respond(HttpStatusCode.Accepted, runtime.toResponse())
        }

        post("/{id}/stop") {
            val workspaceId = call.parameters["workspaceId"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val runtime = service.stopRuntime(workspaceId, id)
            call.respond(HttpStatusCode.Accepted, runtime.toResponse())
        }

        delete("/{id}") {
            val workspaceId = call.parameters["workspaceId"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val runtime = service.requestRuntimeDeletion(workspaceId, id)
            call.respond(HttpStatusCode.Accepted, runtime.toResponse())
        }
    }
}

/**
 * /v1/runtimes/{runtimeId}/executions routes (issue #1 "Execution" section).
 */
fun Route.configureExecutionRoutes(service: RuntimeService) {
    route("/v1/runtimes/{runtimeId}/executions") {
        post {
            val runtimeId = call.parameters["runtimeId"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val request = receiveBody<CreateExecutionRequest>(call)
            val type = try {
                ExecutionType.valueOf(request.type.uppercase())
            } catch (e: IllegalArgumentException) {
                throw InvalidWorkspaceRequestException("unknown execution type: ${request.type}")
            }
            val result = service.createExecution(
                runtimeId = runtimeId,
                type = type,
                commandJson = request.commandJson,
                agentConfigJson = request.agentConfigJson,
                labels = request.labels,
                idempotencyKey = idempotencyKeyOf(call),
            )
            respondCreated(call, result) { it.toResponse() }
        }

        get {
            val runtimeId = call.parameters["runtimeId"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val statusParam = call.request.queryParameters["status"]
            val status = statusParam?.let { raw ->
                try {
                    ExecutionStatus.valueOf(raw.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw InvalidWorkspaceRequestException("unknown status: $raw")
                }
            }
            val limit = queryInt(call, "limit", 50)
            val offset = queryInt(call, "offset", 0)
            val labels = labelFilterOf(call)

            val result = service.listExecutions(runtimeId, status, limit, offset, labels)
            call.respond(
                HttpStatusCode.OK,
                ExecutionListResponse(
                    items = result.items.map { it.toResponse() },
                    total = result.total,
                    limit = limit,
                    offset = offset,
                ),
            )
        }

        get("/{id}") {
            val runtimeId = call.parameters["runtimeId"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing execution id")
            call.respond(HttpStatusCode.OK, service.getExecution(runtimeId, id).toResponse())
        }

        // Cancel records a request only (non-terminal -> CANCELLING). 202 when this call changed
        // the state; 200 with the current execution when already CANCELLING or terminal.
        // Stop is confirmed asynchronously: poll GET until status is CANCELLED.
        post("/{id}/cancel") {
            val runtimeId = call.parameters["runtimeId"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing execution id")
            val result = service.cancelExecution(runtimeId, id)
            val status = if (result.accepted) HttpStatusCode.Accepted else HttpStatusCode.OK
            call.respond(status, result.execution.toResponse())
        }
    }
}

/**
 * /v1/runtimes/{runtimeId}/terminal-sessions (plan chapter 6.x, 第5MS).
 *
 * Answers 201 Created, not 202: the session is complete at response time (ISSUED, token
 * included). There is no asynchronous state to poll, unlike Workspace or Runtime creation.
 * The body carries the one-time plaintext token, so the response is marked `no-store`.
 */
fun Route.configureTerminalSessionRoutes(service: RuntimeService) {
    route("/v1/runtimes/{runtimeId}/terminal-sessions") {
        post {
            val runtimeId = call.parameters["runtimeId"] ?: throw InvalidWorkspaceRequestException("missing runtime id")
            val issued = service.createTerminalSession(runtimeId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.Created, issued.toResponse())
        }
    }
}

/**
 * Decodes the request body, normalizing Ktor / kotlinx.serialization decoding
 * failures into [InvalidWorkspaceRequestException] so StatusPages answers a
 * single 400 VALIDATION_ERROR shape.
 */
private suspend inline fun <reified T : Any> receiveBody(call: ApplicationCall): T = try {
    call.receive<T>()
} catch (e: SerializationException) {
    throw InvalidWorkspaceRequestException("invalid request body: ${e.message}")
} catch (e: BadRequestException) {
    throw InvalidWorkspaceRequestException("invalid request body: ${e.message}")
}

/** `?label=k=v` (repeatable, ANDed). Each key may appear once. */
private fun labelFilterOf(call: ApplicationCall): Map<String, String> {
    val labels = linkedMapOf<String, String>()
    call.request.queryParameters.getAll("label")?.forEach { raw ->
        val separator = raw.indexOf('=')
        if (separator <= 0) throw InvalidWorkspaceRequestException("label filter must be k=v: $raw")
        val key = raw.substring(0, separator)
        if (labels.containsKey(key)) throw InvalidWorkspaceRequestException("duplicate label filter key: $key")
        labels[key] = raw.substring(separator + 1)
    }
    return labels
}

private fun queryInt(call: ApplicationCall, name: String, default: Int): Int {
    val raw = call.request.queryParameters[name] ?: return default
    return raw.toIntOrNull() ?: throw InvalidWorkspaceRequestException("invalid query parameter $name: $raw")
}