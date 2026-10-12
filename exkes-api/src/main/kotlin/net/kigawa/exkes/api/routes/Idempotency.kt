package net.kigawa.exkes.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import net.kigawa.exkes.api.workspace.InvalidWorkspaceRequestException
import net.kigawa.exkes.common.db.IdempotentResult

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"
private val IDEMPOTENCY_KEY_PATTERN = Regex("[\\x21-\\x7E]{1,255}")

/**
 * Reads the optional `Idempotency-Key` header: 1-255 visible ASCII characters (RFC 9110 token-like).
 * Anything else is a 400, so a malformed key is never silently treated as "no key".
 */
internal fun idempotencyKeyOf(call: ApplicationCall): String? {
    val raw = call.request.headers[IDEMPOTENCY_KEY_HEADER] ?: return null
    if (!IDEMPOTENCY_KEY_PATTERN.matches(raw)) {
        throw InvalidWorkspaceRequestException("$IDEMPOTENCY_KEY_HEADER must be 1-255 visible ASCII characters")
    }
    return raw
}

/** Answers 202 with the resource; a replayed request also gets `Idempotent-Replayed: true`. */
internal suspend inline fun <T, reified R : Any> respondCreated(
    call: ApplicationCall,
    result: IdempotentResult<T>,
    toResponse: (T) -> R,
) {
    if (result.replayed) call.response.headers.append(IDEMPOTENT_REPLAYED_HEADER, "true")
    call.respond(HttpStatusCode.Accepted, toResponse(result.value))
}
