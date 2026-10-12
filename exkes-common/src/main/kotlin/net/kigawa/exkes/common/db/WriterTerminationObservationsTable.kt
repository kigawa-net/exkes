package net.kigawa.exkes.common.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Mirrors V8. UNKNOWN/CONFLICT observations are never a deletion grant.
 * There is intentionally no VERIFIED status or mutation operation in V8.
 */
object WriterTerminationObservationsTable : Table("writer_termination_observations") {
    val id = varchar("id", 36)
    val workspaceId = varchar("workspace_id", 36)
    val workspacePlacementGeneration = long("workspace_placement_generation")
    val workspacePvcUid = varchar("workspace_pvc_uid", 64)
    val runtimeId = varchar("runtime_id", 36)
    val podUid = varchar("pod_uid", 64).nullable()
    val writerGeneration = long("writer_generation").nullable()
    val evidenceSource = varchar("evidence_source", 32)
    val evidenceKind = varchar("evidence_kind", 32)
    val observationStatus = varchar("observation_status", 16)
    val evidenceDigest = varchar("evidence_digest", 64)
    val observedAt = timestamp("observed_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
