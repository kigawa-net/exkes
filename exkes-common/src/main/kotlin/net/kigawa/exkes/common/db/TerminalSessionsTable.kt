package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed definition of the `terminal_sessions` table.
 * The authoritative DDL is db/migration/V9__terminal_sessions.sql; this object mirrors it.
 */
object TerminalSessionsTable : Table("terminal_sessions") {
    val id = varchar("id", 36)
    val runtimeId = varchar("runtime_id", 36)
    val tokenHash = varchar("token_hash", 64)
    val status = varchar("status", 16)
    val expiresAt = timestamp("expires_at")
    val consumedAt = timestamp("consumed_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
