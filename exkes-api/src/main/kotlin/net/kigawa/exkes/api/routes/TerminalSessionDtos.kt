package net.kigawa.exkes.api.routes

import java.time.Instant
import kotlinx.serialization.Serializable
import net.kigawa.exkes.api.runtime.IssuedTerminalSession

/**
 * Response of `POST /v1/runtimes/{runtimeId}/terminal-sessions`.
 *
 * [token] is the plaintext single-use credential. It is returned only here and is never
 * retrievable afterwards (the server keeps only its hash). Responses carry `Cache-Control: no-store`.
 */
@Serializable
data class TerminalSessionResponse(
    val id: String,
    val runtimeId: String,
    val status: String,
    val token: String,
    @Serializable(with = InstantIsoSerializer::class)
    val issuedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant,
)

internal fun IssuedTerminalSession.toResponse(): TerminalSessionResponse = TerminalSessionResponse(
    id = session.id,
    runtimeId = session.runtimeId,
    status = session.status.name,
    token = token,
    issuedAt = session.issuedAt,
    expiresAt = session.expiresAt,
)
