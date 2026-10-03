package net.kigawa.exkes.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import net.kigawa.exkes.api.workspace.InvalidWorkspaceRequestException
import net.kigawa.exkes.api.workspace.WorkspaceConflictException
import net.kigawa.exkes.api.workspace.WorkspaceNotFoundException
import net.kigawa.exkes.common.db.DuplicateWorkspaceNameException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ErrorPages")

/**
 * Maps every failure to the common error format `{"code": ..., "message": ...}`
 * (plan chapter 6.2): NOT_FOUND / CONFLICT / VALIDATION_ERROR / INTERNAL.
 *
 * Typed handlers are declared from the most specific to the catch-all;
 * StatusPages resolves them by exception type, so the Throwable handler is the
 * safety net for everything else.
 *
 * The catch-all never forwards the exception message: JDBC URLs with
 * credentials, Kubernetes API response bodies and stack traces must stay in the
 * server log. They are logged here with full detail instead.
 */
fun Application.installErrorPages() {
    install(StatusPages) {
        exception<WorkspaceNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", cause.message ?: "workspace not found")
        }

        exception<DuplicateWorkspaceNameException> { call, cause ->
            call.respondError(HttpStatusCode.Conflict, "CONFLICT", cause.message ?: "conflict")
        }

        exception<WorkspaceConflictException> { call, cause ->
            call.respondError(HttpStatusCode.Conflict, "CONFLICT", cause.message ?: "conflict")
        }

        exception<InvalidWorkspaceRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", cause.message ?: "invalid request")
        }

        // Ktor raises these while decoding the request body / query.
        exception<BadRequestException> { call, cause ->
            logger.debug("malformed request rejected", cause)
            call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "invalid request body")
        }

        exception<ContentTransformationException> { call, cause ->
            logger.debug("body transformation failed", cause)
            call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "invalid request body")
        }

        exception<SerializationException> { call, cause ->
            logger.debug("payload serialization failed", cause)
            call.respondError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "invalid request body")
        }

        exception<Throwable> { call, cause ->
            // Cancellation is control flow, not an error: never turn it into a 500.
            if (cause is CancellationException) throw cause
            logger.error("unhandled exception while serving ${call.request.local.uri}", cause)
            call.respondError(HttpStatusCode.InternalServerError, "INTERNAL", "internal error")
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respond(status, ErrorResponse(code, message))