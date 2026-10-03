package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed definition of the `runtime_templates` table.
 * The authoritative DDL is db/migration/V2__runtimes.sql (plan chapter 5.2);
 * this object mirrors it for type-safe queries.
 */
object RuntimeTemplatesTable : Table("runtime_templates") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val description = text("description").nullable()
    val image = varchar("image", 256)
    val defaultResourcesJson = text("default_resources_json").nullable()
    val defaultEnvJson = text("default_env_json").nullable()
    val defaultTtlSeconds = long("default_ttl_seconds").nullable()
    val labelsJson = text("labels_json").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}