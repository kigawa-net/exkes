package net.kigawa.exkes.common

import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.UnresolvedWriterObservationStatus
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.WriterEvidenceKind
import net.kigawa.exkes.common.db.WriterEvidenceSource
import net.kigawa.exkes.common.db.WriterTerminationObservationRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.WorkspaceStatus

class WriterTerminationObservationRepositoryTest {
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var runtimes: RuntimeRepository
    private lateinit var observations: WriterTerminationObservationRepository
    private val namespace = "platform-exkes-stg-sandbox"
    private val templateId = "writer-evidence-test"

    @BeforeTest
    fun setup() {
        val ds = createDataSource(DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:writer_observations_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            user = "sa",
            password = "",
        ))
        runMigrations(ds)
        connectDatabase(ds)
        workspaces = WorkspaceRepository()
        runtimes = RuntimeRepository()
        observations = WriterTerminationObservationRepository()
        RuntimeTemplateRepository().create(
            id = templateId, name = "Writer test", description = null,
            image = "example/runtime:test", defaultResourcesJson = null,
            defaultEnvJson = null, defaultTtlSeconds = null, labelsJson = null,
        )
    }

    private fun workspace() = workspaces.create(
        "writer-evidence-${System.nanoTime()}", "rook-cephfs", 1024L, emptyMap(),
    )

    private fun boundRuntime(): net.kigawa.exkes.common.domain.Runtime {
        val ws = workspace()
        assertTrue(workspaces.reservePlacementForCreating(ws.id, namespace))
        assertTrue(workspaces.setPvcName(ws.id, "ws-${ws.id}"))
        assertTrue(workspaces.recordVerifiedPvcUid(ws.id, namespace, "test-pvc-uid"))
        assertTrue(workspaces.transition(ws.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        return runtimes.createBoundToWorkspace(ws.id, templateId, null, null, null, null)
    }

    @Test
    fun `unknown evidence snapshots Runtime immutable PVC generation even while Workspace is deleting`() {
        val runtime = boundRuntime()
        assertTrue(workspaces.transition(runtime.workspaceId, WorkspaceStatus.READY, WorkspaceStatus.DELETING))
        val observation = observations.recordUnresolved(
            runtimeId = runtime.id,
            source = WriterEvidenceSource.KUBERNETES_CONTROLLER,
            kind = WriterEvidenceKind.POD_UID,
            status = UnresolvedWriterObservationStatus.UNKNOWN,
            evidenceDigest = "a".repeat(64),
            observedAt = Instant.now(),
            podUid = "unverified-pod-uid",
        )
        assertEquals(runtime.workspaceId, observation.workspaceId)
        assertEquals(runtime.workspacePlacementGeneration, observation.workspacePlacementGeneration)
        assertEquals(runtime.workspacePvcUid, observation.workspacePvcUid)
        assertEquals(UnresolvedWriterObservationStatus.UNKNOWN, observations.findById(observation.id)?.status)
        assertEquals(listOf(observation.id), observations.listForWorkspace(runtime.workspaceId).map { it.id })
    }

    @Test
    fun `legacy runtime without PVC generation cannot mint evidence`() {
        val ws = workspace()
        val legacyRuntime = runtimes.create(ws.id, templateId, null, null, null, null)
        assertFailsWith<IllegalArgumentException> {
            observations.recordUnresolved(
                legacyRuntime.id, WriterEvidenceSource.RUNTIME_AGENT,
                WriterEvidenceKind.PROCESS_EXIT, UnresolvedWriterObservationStatus.UNKNOWN,
                "b".repeat(64), Instant.now(),
            )
        }
        assertTrue(observations.listForWorkspace(ws.id).isEmpty())
    }

    @Test
    fun `digest must be canonical SHA256 not raw provider or secret output`() {
        val runtime = boundRuntime()
        assertFailsWith<IllegalArgumentException> {
            observations.recordUnresolved(
                runtime.id, WriterEvidenceSource.PROVIDER_RECEIPT,
                WriterEvidenceKind.EXTERNAL_OPERATION, UnresolvedWriterObservationStatus.CONFLICT,
                "Bearer secret-value", Instant.now(),
            )
        }
        assertTrue(observations.listForWorkspace(runtime.workspaceId).isEmpty())
    }

    @Test
    fun `replayed observation cannot overwrite immutable diagnostic record`() {
        val runtime = boundRuntime()
        val args = listOf("c".repeat(64), "c".repeat(64))
        val recorded = observations.recordUnresolved(
            runtime.id, WriterEvidenceSource.KUBERNETES_CONTROLLER,
            WriterEvidenceKind.POD_UID, UnresolvedWriterObservationStatus.UNKNOWN,
            args[0], Instant.now(),
        )
        assertFailsWith<Exception> {
            observations.recordUnresolved(
                runtime.id, WriterEvidenceSource.KUBERNETES_CONTROLLER,
                WriterEvidenceKind.POD_UID, UnresolvedWriterObservationStatus.CONFLICT,
                args[1], Instant.now(),
            )
        }
        assertEquals(UnresolvedWriterObservationStatus.UNKNOWN, observations.findById(recorded.id)?.status)
        assertEquals(1, observations.listForWorkspace(runtime.workspaceId).size)
    }
}
