package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table

/**
 * Exposed definition of `execution_labels` (db/migration/V5). Normalized copy of the
 * execution labels, used only for `label=k=v` filtering in the list API.
 */
object ExecutionLabelsTable : Table("execution_labels") {
    val executionId = varchar("execution_id", 36)
    val labelKey = varchar("label_key", 63)
    val labelValue = varchar("label_value", 256)

    override val primaryKey = PrimaryKey(executionId, labelKey)
}
