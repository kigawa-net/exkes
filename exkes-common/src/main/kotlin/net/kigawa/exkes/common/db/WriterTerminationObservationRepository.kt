package net.kigawa.exkes.common.db

import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Only stores diagnostic, NON-AUTHORITATIVE observations. No method in this
 * repository may attest an old Writer is gone or enable PVC deletion.
 * Verified receipts require authenticated producers and generation fencing (#60).
 */
enum class WriterEvidenceSource {
    KUBERNETES_CONTROLLER,
    RUNTIME_AGENT,
    PROVIDER_RECEIPT,
}

enum class WriterEvidenceKind {
    POD_UID,
    PROCESS_EXIT,
    EXTERNAL_OPERATION,
}

enum class UnresolvedWriterObservationStatus {
    UNKNOWN,
    CONFLICT,
}

data class UnresolvedWriterObservation(
    val id: String,
    val workspaceId: String,
    val workspacePlacementGeneration: Long,
    val workspacePvcUid: String,
    val runtimeId: String,
    val podUid: String?,
    val writerGeneration: Long?,
    val source: WriterEvidenceSource,
    val kind: WriterEvidenceKind,
    val status: UnresolvedWriterObservationStatus,
    /** SHA-256 of a canonical observation; never raw provider content/secrets. */
    val evidenceDigest: String,
    val observedAt: Instant,
    val createdAt: Instant,
)

class WriterTerminationObservationRepository {
    /**
     * Record only UNKNOWN/CONFLICT evidence from the controller's internal path.
     * A Runtime with no immutable V7 PVC identity cannot supply evidence.
     * Stale or forged external producer payloads are NOT accepted as VERIFIED.
     *
     * Duplicate digests are not authoritative updates. The caller may retry
     * after a UNIQUE violation but cannot change the first stored observation.
     */
    fun recordUnresolved(
        runtimeId: String,
        source: WriterEvidenceSource,
        kind: WriterEvidenceKind,
        status: UnresolvedWriterObservationStatus,
        evidenceDigest: String,
        observedAt: Instant,
        podUid: String? = null,
        writerGeneration: Long? = null,
    ): UnresolvedWriterObservation = transaction {
        require(Regex("[0-9a-f]{64}").matches(evidenceDigest)) {
            "evidenceDigest must be a lower-case SHA-256 hex digest, never raw evidence"
        }
        require(podUid == null || (podUid.isNotBlank() && podUid.length <= 64))
        require(writerGeneration == null || writerGeneration >= 0L)
        val runtime = RuntimeRepository().findById(runtimeId)
            ?: throw IllegalArgumentException("runtime not found")
        val generation = runtime.workspacePlacementGeneration
            ?: throw IllegalArgumentException("legacy Runtime has no immutable Workspace generation")
        val pvcUid = runtime.workspacePvcUid?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("legacy Runtime has no immutable PVC UID")
        // The runtime's immutable V7 identity is used even when the Workspace
        // is currently DELETING. Never derive it from mutable controller config.
        require(!runtime.workspacePvcNamespace.isNullOrBlank())
        require(!runtime.workspacePvcName.isNullOrBlank())

        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        WriterTerminationObservationsTable.insert {
            it[WriterTerminationObservationsTable.id] = id
            it[WriterTerminationObservationsTable.workspaceId] = runtime.workspaceId
            it[WriterTerminationObservationsTable.workspacePlacementGeneration] = generation
            it[WriterTerminationObservationsTable.workspacePvcUid] = pvcUid
            it[WriterTerminationObservationsTable.runtimeId] = runtimeId
            it[WriterTerminationObservationsTable.podUid] = podUid
            it[WriterTerminationObservationsTable.writerGeneration] = writerGeneration
            it[WriterTerminationObservationsTable.evidenceSource] = source.name
            it[WriterTerminationObservationsTable.evidenceKind] = kind.name
            it[WriterTerminationObservationsTable.observationStatus] = status.name
            it[WriterTerminationObservationsTable.evidenceDigest] = evidenceDigest
            it[WriterTerminationObservationsTable.observedAt] = observedAt
            it[WriterTerminationObservationsTable.createdAt] = now
        }
        requireNotNull(findById(id))
    }

    fun findById(id: String): UnresolvedWriterObservation? = transaction {
        WriterTerminationObservationsTable.selectAll()
            .where { WriterTerminationObservationsTable.id eq id }
            .singleOrNull()
            ?.toObservation()
    }

    fun listForWorkspace(workspaceId: String, limit: Int = 100): List<UnresolvedWriterObservation> = transaction {
        require(limit in 1..500)
        WriterTerminationObservationsTable.selectAll()
            .where { WriterTerminationObservationsTable.workspaceId eq workspaceId }
            .orderBy(WriterTerminationObservationsTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toObservation() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toObservation() = UnresolvedWriterObservation(
        id = this[WriterTerminationObservationsTable.id],
        workspaceId = this[WriterTerminationObservationsTable.workspaceId],
        workspacePlacementGeneration = this[WriterTerminationObservationsTable.workspacePlacementGeneration],
        workspacePvcUid = this[WriterTerminationObservationsTable.workspacePvcUid],
        runtimeId = this[WriterTerminationObservationsTable.runtimeId],
        podUid = this[WriterTerminationObservationsTable.podUid],
        writerGeneration = this[WriterTerminationObservationsTable.writerGeneration],
        source = WriterEvidenceSource.valueOf(this[WriterTerminationObservationsTable.evidenceSource]),
        kind = WriterEvidenceKind.valueOf(this[WriterTerminationObservationsTable.evidenceKind]),
        status = UnresolvedWriterObservationStatus.valueOf(this[WriterTerminationObservationsTable.observationStatus]),
        evidenceDigest = this[WriterTerminationObservationsTable.evidenceDigest],
        observedAt = this[WriterTerminationObservationsTable.observedAt],
        createdAt = this[WriterTerminationObservationsTable.createdAt],
    )
}
