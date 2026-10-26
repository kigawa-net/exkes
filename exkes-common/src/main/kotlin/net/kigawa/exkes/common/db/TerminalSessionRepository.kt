package net.kigawa.exkes.common.db

import java.time.Instant
import java.util.UUID
import net.kigawa.exkes.common.domain.TerminalSession
import net.kigawa.exkes.common.domain.TerminalSessionStatus
import net.kigawa.exkes.common.domain.TerminalSessionToken
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Persistence for `terminal_sessions` (plan chapter 5.1 / 第5MS).
 *
 * The plaintext token never reaches this class except as the argument of
 * [consume], which hashes it before any query. [create] takes the hash only.
 *
 * Every status change is a conditional UPDATE keyed by `id` and the expected
 * status (`WHERE id = ? AND status = ?`), the same rule as [RuntimeRepository.transition].
 *
 * `open` so route tests can wrap it; only [consume] and [create] are used across modules.
 */
open class TerminalSessionRepository {

    /** Inserts an ISSUED session for [runtimeId]. [tokenHash] is hex SHA-256 of the token. */
    fun create(
        runtimeId: String,
        tokenHash: String,
        issuedAt: Instant,
        expiresAt: Instant,
    ): TerminalSession = transaction {
        val id = UUID.randomUUID().toString()
        TerminalSessionsTable.insert {
            it[TerminalSessionsTable.id] = id
            it[TerminalSessionsTable.runtimeId] = runtimeId
            it[TerminalSessionsTable.tokenHash] = tokenHash
            it[TerminalSessionsTable.status] = TerminalSessionStatus.ISSUED.name
            it[TerminalSessionsTable.expiresAt] = expiresAt
            it[TerminalSessionsTable.createdAt] = issuedAt
            it[TerminalSessionsTable.updatedAt] = issuedAt
        }
        findById(id) ?: throw IllegalStateException("terminal session vanished after insert: $id")
    }

    fun findById(id: String): TerminalSession? = transaction {
        TerminalSessionsTable.selectAll()
            .where { TerminalSessionsTable.id eq id }
            .singleOrNull()
            ?.toSession()
    }

    /**
     * Consumes the session that [token] belongs to, exactly once.
     *
     * Returns the session in CONSUMED state, or null when the token is unknown, was already
     * consumed, or has expired. The single-row conditional UPDATE also requires
     * `expires_at > now`, so two concurrent callers cannot both win and an expired token
     * can never be consumed. A consume attempt on an ISSUED but expired session moves it to
     * EXPIRED (conditional UPDATE, best effort: a lost race leaves it ISSUED, which still
     * cannot be consumed).
     */
    open fun consume(token: String, now: Instant): TerminalSession? = transaction {
        val row = TerminalSessionsTable.selectAll()
            .where { TerminalSessionsTable.tokenHash eq TerminalSessionToken.hash(token) }
            .singleOrNull()
            ?: return@transaction null
        val id = row[TerminalSessionsTable.id]
        if (row[TerminalSessionsTable.status] != TerminalSessionStatus.ISSUED.name) {
            return@transaction null
        }
        if (!now.isBefore(row[TerminalSessionsTable.expiresAt])) {
            transition(id, TerminalSessionStatus.ISSUED, TerminalSessionStatus.EXPIRED, now)
            return@transaction null
        }

        val consumed = TerminalSessionsTable.update({
            (TerminalSessionsTable.id eq id) and
                (TerminalSessionsTable.status eq TerminalSessionStatus.ISSUED.name) and
                (TerminalSessionsTable.expiresAt greater now)
        }) { stmt ->
            stmt[TerminalSessionsTable.status] = TerminalSessionStatus.CONSUMED.name
            stmt[TerminalSessionsTable.consumedAt] = now
            stmt[TerminalSessionsTable.updatedAt] = now
        }
        if (consumed == 1) findById(id) else null
    }

    /** Conditional status change. Returns false when the row was not in [from]. */
    private fun transition(
        id: String,
        from: TerminalSessionStatus,
        to: TerminalSessionStatus,
        at: Instant,
    ): Boolean {
        require(from == TerminalSessionStatus.ISSUED && to == TerminalSessionStatus.EXPIRED) {
            "illegal terminal session transition: $from -> $to"
        }
        val updated = TerminalSessionsTable.update({
            (TerminalSessionsTable.id eq id) and (TerminalSessionsTable.status eq from.name)
        }) { stmt ->
            stmt[TerminalSessionsTable.status] = to.name
            stmt[TerminalSessionsTable.updatedAt] = at
        }
        return updated == 1
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toSession(): TerminalSession = TerminalSession(
        id = this[TerminalSessionsTable.id],
        runtimeId = this[TerminalSessionsTable.runtimeId],
        status = TerminalSessionStatus.valueOf(this[TerminalSessionsTable.status]),
        issuedAt = this[TerminalSessionsTable.createdAt],
        expiresAt = this[TerminalSessionsTable.expiresAt],
        consumedAt = this[TerminalSessionsTable.consumedAt],
    )
}
