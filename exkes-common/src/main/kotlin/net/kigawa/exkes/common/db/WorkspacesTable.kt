package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed definition of the `workspaces` table.
 * The authoritative DDL is db/migration/V1__workspaces.sql (plan chapter 5.2);
 * this object mirrors it for type-safe queries.
 */
object WorkspacesTable : Table("workspaces") {
    val id = varchar("id", 36)
    val name = varchar("name", 128)
    val status = varchar("status", 32)
    val storageClass = varchar("storage_class", 64)
    val storageSize = long("storage_size")
    val pvcName = varchar("pvc_name", 128).nullable()
    val errorMessage = text("error_message").nullable()
    val labelsJson = text("labels_json").nullable()
    val archivedAt = timestamp("archived_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
