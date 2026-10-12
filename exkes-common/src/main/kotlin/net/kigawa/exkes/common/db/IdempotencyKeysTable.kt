package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed definition of `idempotency_keys` (db/migration/V5). The DDL is authoritative.
 */
object IdempotencyKeysTable : Table("idempotency_keys") {
    val scope = varchar("scope", 128)
    val idempotencyKey = varchar("idempotency_key", 255)
    val requestDigest = varchar("request_digest", 64)
    val resourceId = varchar("resource_id", 36)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(scope, idempotencyKey)
}
