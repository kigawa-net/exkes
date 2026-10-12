package net.kigawa.exkes.api.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.kigawa.exkes.common.config.WorkspaceConfig
import net.kigawa.exkes.common.db.IdempotentResult
import net.kigawa.exkes.common.db.IdempotencyRepository
import net.kigawa.exkes.common.db.WorkspaceListResult
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspaceStatus

class WorkspaceNotFoundException(id: String) : RuntimeException("workspace not found: $id")

class InvalidWorkspaceRequestException(message: String) : RuntimeException(message)

/**
 * Raised when a conditional UPDATE lost its race against a concurrent writer
 * repeatedly, so the caller's intent could not be committed.
 */
class WorkspaceConflictException(id: String) :
    RuntimeException("workspace is being modified concurrently: $id")

/**
 * Application service for /v1/workspaces.
 *
 * Owns validation and is the boundary between the Ktor event loop and the
 * blocking JDBC layer (plan chapter 3.2): every method is `suspend` and runs its
 * work on [Dispatchers.IO]. Exposed's `transaction {}` blocks the calling
 * thread, and Ktor handlers run on the Netty event loop, so without the
 * hop a single request would stall all connections handled by that loop.
 *
 * The service only writes **desired state**: [create] inserts a CREATING row and
 * [requestDeletion] transitions to DELETING. Turning those rows into PVCs is
 * exkes-controller's job (issue #2 "Controller RBAC").
 */
class WorkspaceService(
    private val repository: WorkspaceRepository,
    private val workspaceConfig: WorkspaceConfig,
    private val idempotencyRepository: IdempotencyRepository = IdempotencyRepository(),
) {
    /**
     * Creates a workspace. With [idempotencyKey], a repeated request with the same key
     * and body returns the workspace created first (replayed = true, no new row).
     */
    suspend fun create(
        name: String,
        storageSizeBytes: Long?,
        labels: Map<String, String>?,
        idempotencyKey: String? = null,
    ): IdempotentResult<Workspace> = onIoThread {
        validateName(name)
        val size = storageSizeBytes ?: workspaceConfig.defaultStorageSizeBytes
        if (size <= 0) {
            throw InvalidWorkspaceRequestException("storageSizeBytes must be positive: $size")
        }
        val effectiveLabels = labels ?: emptyMap()
        val createFresh = {
            // Re-thrown as 409 by the routes layer.
            repository.create(
                name = name,
                storageClass = workspaceConfig.storageClass,
                storageSizeBytes = size,
                labels = effectiveLabels,
            )
        }
        if (idempotencyKey == null) {
            IdempotentResult(createFresh(), replayed = false)
        } else {
            idempotencyRepository.execute(
                scope = IDEMPOTENCY_SCOPE,
                key = idempotencyKey,
                requestDigest = IdempotencyRepository.digestOf(
                    buildJsonObject {
                        put("name", name)
                        put("storageSizeBytes", size)
                        putJsonObject("labels") { effectiveLabels.toSortedMap().forEach { (k, v) -> put(k, v) } }
                    },
                ),
                load = { repository.findById(it) },
                create = createFresh,
                idOf = { it.id },
            )
        }
    }

    suspend fun list(status: WorkspaceStatus?, limit: Int, offset: Int, name: String? = null): WorkspaceListResult = onIoThread {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw InvalidWorkspaceRequestException("limit must be between 1 and $MAX_LIMIT: $limit")
        }
        if (offset < 0) {
            throw InvalidWorkspaceRequestException("offset must not be negative: $offset")
        }
        repository.list(status, limit, offset, name)
    }

    /** Returns the workspace; DELETED rows count as gone (404, plan chapter 6.2). */
    suspend fun get(id: String): Workspace = onIoThread {
        val workspace = repository.findById(id)
        if (workspace == null || workspace.status == WorkspaceStatus.DELETED) {
            throw WorkspaceNotFoundException(id)
        }
        workspace
    }

    /**
     * Requests deletion and returns the row after the transition.
     *
     * `transition` is a conditional UPDATE (`WHERE id = ? AND status = ?`), so a
     * concurrent actor can win the race and leave 0 rows updated. Plan chapter 7.1
     * requires re-reading and re-deciding in that case, otherwise the endpoint
     * would answer 202 with a workspace that is still READY. Hence the bounded
     * retry loop; the outcome per attempt is:
     *
     * - DELETING already -> idempotent 202
     * - DELETED or missing -> 404 (via [get])
     * - not deletable     -> 400
     * - still racing       -> re-read and try again, 409 after the last attempt
     *
     * Accepting a DELETE while the row is still CREATING is intentional: the
     * controller finishes it once the PVC settles (plan chapter 6.2).
     */
    suspend fun requestDeletion(id: String): Workspace = onIoThread {
        repeat(MAX_DELETE_ATTEMPTS) {
            val workspace = getBlocking(id)
            when {
                // Already accepted: keep answering 202 (idempotent).
                workspace.status == WorkspaceStatus.DELETING -> return@onIoThread workspace
                !workspace.canTransitionTo(WorkspaceStatus.DELETING) ->
                    throw InvalidWorkspaceRequestException(
                        "workspace in ${workspace.status} cannot be deleted",
                    )
                repository.transition(id, workspace.status, WorkspaceStatus.DELETING) ->
                    return@onIoThread workspace.copy(status = WorkspaceStatus.DELETING)
                // Lost the race: loop re-reads the current state and re-decides.
            }
        }
        throw WorkspaceConflictException(id)
    }

    private fun validateName(name: String) {
        if (!NAME_PATTERN.matches(name)) {
            throw InvalidWorkspaceRequestException(
                "name must match ${NAME_PATTERN.pattern} (1-128 chars): $name",
            )
        }
    }

    /** Same lookup as [get] without a second dispatcher hop inside the retry loop. */
    private fun getBlocking(id: String): Workspace {
        val workspace = repository.findById(id)
        if (workspace == null || workspace.status == WorkspaceStatus.DELETED) {
            throw WorkspaceNotFoundException(id)
        }
        return workspace
    }

    companion object {
        const val MAX_LIMIT = 500

        const val IDEMPOTENCY_SCOPE = "workspaces"

        /** Upper bound on re-read/re-decide rounds when a concurrent writer keeps winning. */
        const val MAX_DELETE_ATTEMPTS = 3

        // Lowercase/uppercase alphanumerics first, then alphanumerics, '.', '_', '-'.
        private val NAME_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")

        /** Blocking JDBC must not run on a Netty event loop thread. */
        private suspend fun <T> onIoThread(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    }
}
