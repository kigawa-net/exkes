package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed definition of the `runtimes` table.
 * The authoritative DDL is db/migration/V2__runtimes.sql (plan chapter 5.2);
 * this object mirrors it for type-safe queries.
 */
object RuntimesTable : Table("runtimes") {
    val id = varchar("id", 36)
    val workspaceId = varchar("workspace_id", 36)
    val templateId = varchar("template_id", 64)
    val status = varchar("status", 32)
    val resourcesJson = text("resources_json").nullable()
    val envJson = text("env_json").nullable()
    val ttlSeconds = long("ttl_seconds").nullable()
    val podName = varchar("pod_name", 128).nullable()
    val errorMessage = text("error_message").nullable()
    val labelsJson = text("labels_json").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}