package net.kigawa.exkes.common.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Terminal session lifecycle (V9 `terminal_sessions`).
 *
 * ```text
 * ISSUED --consume--> CONSUMED (final)
 *    |
 *    +--- past expires_at, on consume attempt ---> EXPIRED (final)
 * ```
 *
 * Transitions go through [net.kigawa.exkes.common.db.TerminalSessionRepository] as
 * conditional UPDATEs (`WHERE id = ? AND status = ?`), so a token is consumed at most once.
 */
enum class TerminalSessionStatus {
    ISSUED,
    CONSUMED,
    EXPIRED,
}

/** Persisted session metadata. The token itself (plaintext or hash) is deliberately absent. */
data class TerminalSession(
    val id: String,
    val runtimeId: String,
    val status: TerminalSessionStatus,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant?,
)

/**
 * Opaque, single-use bearer token helpers.
 *
 * 256 bits from [SecureRandom], base64url without padding. Only [hash] (hex SHA-256)
 * is persisted: the token already has 256 bits of entropy, so a fast hash is enough
 * for lookup and a leaked row cannot be replayed.
 */
object TerminalSessionToken {
    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
