package net.kigawa.exkes.api.routes

import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import net.kigawa.exkes.api.workspace.InvalidWorkspaceRequestException
import net.kigawa.exkes.api.workspace.WorkspaceService
import net.kigawa.exkes.common.domain.WorkspaceStatus

/**
 * /v1/workspaces routes (plan chapter 6.1).
 *
 * POST and DELETE answer 202 Accepted immediately: they only write desired
 * state (a CREATING row / a DELETING transition). PVC provisioning and deletion
 * are performed asynchronously by exkes-controller, which is the only process
 * allowed to call the Kubernetes API (issue #2 "Controller RBAC").
 *
 * Error mapping lives in [installErrorPages] (StatusPages) so that every
 * route, including future ones, answers the common error format
 * (plan chapter 6.2): 404 NOT_FOUND / 409 CONFLICT / 400 VALIDATION_ERROR /
 * 500 INTERNAL.
 */
fun Route.configureWorkspaceRoutes(service: WorkspaceService) {
    route("/v1/workspaces") {
        post {
            val request = receiveBody<CreateWorkspaceRequest>(call)
            val workspace = service.create(
                name = request.name,
                storageSizeBytes = request.storageSizeBytes,
                labels = request.labels,
            )
            call.respond(HttpStatusCode.Accepted, workspace.toResponse())
        }

        get {
            val statusParam = call.request.queryParameters["status"]
            val status = statusParam?.let { raw ->
                try {
                    WorkspaceStatus.valueOf(raw.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw InvalidWorkspaceRequestException("unknown status: $raw")
                }
            }
            val limit = queryInt(call, "limit", 50)
            val offset = queryInt(call, "offset", 0)

            val result = service.list(status, limit, offset)
            call.respond(
                HttpStatusCode.OK,
                WorkspaceListResponse(
                    items = result.items.map { it.toResponse() },
                    total = result.total,
                    limit = limit,
                    offset = offset,
                ),
            )
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            call.respond(HttpStatusCode.OK, service.get(id).toResponse())
        }

        delete("/{id}") {
            val id = call.parameters["id"] ?: throw InvalidWorkspaceRequestException("missing workspace id")
            val workspace = service.requestDeletion(id)
            call.respond(HttpStatusCode.Accepted, workspace.toResponse())
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

private fun queryInt(call: ApplicationCall, name: String, default: Int): Int {
    val raw = call.request.queryParameters[name] ?: return default
    return raw.toIntOrNull() ?: throw InvalidWorkspaceRequestException("invalid query parameter $name: $raw")
}