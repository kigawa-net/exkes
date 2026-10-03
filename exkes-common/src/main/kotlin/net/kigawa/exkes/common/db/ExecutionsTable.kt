package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed definition of the `executions` table.
 * The authoritative DDL is db/migration/V3__executions.sql (plan chapter 5.2);
 * this object mirrors it for type-safe queries.
 */
object ExecutionsTable : Table("executions") {
    val id = varchar("id", 36)
    val runtimeId = varchar("runtime_id", 36)
    val type = varchar("type", 32)
    val status = varchar("status", 32)
    val commandJson = text("command_json").nullable()
    val agentConfigJson = text("agent_config_json").nullable()
    val exitCode = integer("exit_code").nullable()
    val errorMessage = text("error_message").nullable()
    val logObjectKey = varchar("log_object_key", 256).nullable()
    val artifactObjectKeysJson = text("artifact_object_keys_json").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val startedAt = timestamp("started_at").nullable()
    val finishedAt = timestamp("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}