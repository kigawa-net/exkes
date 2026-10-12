package net.kigawa.exkes.controller

import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.Workspace
import net.kigawa.exkes.common.domain.WorkspaceStatus
import net.kigawa.exkes.common.domain.WorkspacePlacementState
import net.kigawa.exkes.controller.workspace.PvcState
import net.kigawa.exkes.controller.workspace.ProvisionOutcome
import net.kigawa.exkes.controller.workspace.WorkspaceProvisioner
import net.kigawa.exkes.controller.workspace.WorkspaceReconciler
import net.kigawa.exkes.controller.workspace.resolvePvcName

/**
 * State machine tests for WorkspaceReconciler with a fake provisioner
 * (plan chapter 7.1 / 11.1).
 *
 * The reconciler lives in exkes-controller, so these tests came with it: the
 * controller is the only module that owns Kubernetes access (issue #2
 * "Controller RBAC").
 */
class WorkspaceReconcilerTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: WorkspaceRepository
    private lateinit var runtimes: RuntimeRepository

    /**
     * Configurable fake so tests drive CREATING/DELETING transitions.
     *
     * deletePvc reproduces the two-phase protocol of the real
     * KubernetesWorkspaceProvisioner: the first call requests the deletion and
     * answers false, a later call observes the object gone and answers true.
     * The PVC name is resolved exactly like the real implementation, via
     * [resolvePvcName], so the `pvcName ?: ws-<id>` fallback is covered here too.
     */
    private class FakeProvisioner : WorkspaceProvisioner {
        var state: PvcState = PvcState.BOUND
        var failuresRemaining: Int = 0

        /** Cycles that still have to answer "deletion requested, not confirmed". */
        var deleteConfirmationsRemaining: Int = 0
        val deleteRequestedNames = mutableListOf<String>()

        /** Every workspace `ensurePvc` was asked about, in call order. */
        val ensurePvcCalls = mutableListOf<String>()

        override fun ensurePvc(workspace: Workspace): ProvisionOutcome {
            ensurePvcCalls += workspace.id
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw RuntimeException("boom")
            }
            return ProvisionOutcome(workspace.resolvePvcName(), state)
        }

        override fun deletePvc(workspace: Workspace): Boolean {
            deleteRequestedNames += workspace.resolvePvcName()
            if (deleteConfirmationsRemaining > 0) {
                deleteConfirmationsRemaining--
                return false
            }
            return true
        }
    }

    @BeforeTest
    fun setup() {
        dataSource = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_reconciler_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(dataSource)
        connectDatabase(dataSource)
        repository = WorkspaceRepository()
        runtimes = RuntimeRepository()
    }

    private fun createWorkspace(nameSuffix: String = System.nanoTime().toString()): Workspace =
        repository.create(
            name = "reconciler-$nameSuffix",
            storageClass = "rook-cephfs",
            storageSizeBytes = 1024L * 1024 * 1024,
            labels = emptyMap(),
        )

    private fun newReconciler(
        provisioner: WorkspaceProvisioner,
        maxAttempts: Int = 3,
        creatingTimeoutSeconds: Long = 0,
        clock: () -> Instant = Instant::now,
    ) = WorkspaceReconciler(
        repository = repository,
        provisioner = provisioner,
        runtimeRepository = runtimes,
        intervalSeconds = 1,
        maxAttempts = maxAttempts,
        // 0 (disabled) by default so the pre-existing cases keep exercising the
        // attempt-counter path; the timeout has its own tests below.
        creatingTimeoutSeconds = creatingTimeoutSeconds,
        clock = clock,
    )

    @Test
    fun `new workspace records namespace and PVC UID before it becomes READY`() {
        val workspace = createWorkspace("placement-${System.nanoTime()}")
        var calls = 0
        val provisioner = object : WorkspaceProvisioner {
            override fun ensurePvc(workspace: Workspace): ProvisionOutcome {
                calls++
                assertEquals("platform-exkes-stg-sandbox", workspace.pvcNamespace)
                return ProvisionOutcome(
                    workspace.resolvePvcName(),
                    if (calls == 1) PvcState.PENDING else PvcState.BOUND,
                    pvcUid = "verified-pvc-uid",
                    createdByThisAttempt = calls == 1,
                )
            }

            override fun deletePvc(workspace: Workspace): Boolean = error("not used")
        }
        val reconciler = WorkspaceReconciler(
            repository = repository,
            provisioner = provisioner,
            runtimeRepository = runtimes,
            resourceNamespace = "platform-exkes-stg-sandbox",
            creatingTimeoutSeconds = 0,
        )
        reconciler.reconcileOnce()
        val reserved = repository.findById(workspace.id)!!
        assertEquals(WorkspaceStatus.CREATING, reserved.status)
        assertEquals(WorkspacePlacementState.VERIFIED, reserved.placementState)
        assertEquals("platform-exkes-stg-sandbox", reserved.pvcNamespace)
        assertEquals("verified-pvc-uid", reserved.pvcUid)
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.READY, repository.findById(workspace.id)!!.status)
    }

    @Test
    fun `PVC cannot mark a new workspace READY without trusted UID evidence`() {
        val workspace = createWorkspace("untrusted-${System.nanoTime()}")
        val provisioner = object : WorkspaceProvisioner {
            override fun ensurePvc(workspace: Workspace): ProvisionOutcome =
                ProvisionOutcome(workspace.resolvePvcName(), PvcState.BOUND, pvcUid = "untrusted", createdByThisAttempt = false)
            override fun deletePvc(workspace: Workspace): Boolean = error("not used")
        }
        val reconciler = WorkspaceReconciler(
            repository = repository,
            provisioner = provisioner,
            runtimeRepository = runtimes,
            resourceNamespace = "platform-exkes-stg-sandbox",
            maxAttempts = 3,
            creatingTimeoutSeconds = 0,
        )
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)
        assertEquals(WorkspacePlacementState.RESERVED, repository.findById(workspace.id)!!.placementState)
        reconciler.reconcileOnce()
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.ERROR, repository.findById(workspace.id)!!.status)
    }

    @Test
    fun `creating workspace becomes ready when pvc is bound`() {
        val workspace = createWorkspace()
        val reconciler = newReconciler(FakeProvisioner())

        reconciler.reconcileOnce()

        val reloaded = repository.findById(workspace.id)
        assertNotNull(reloaded)
        assertEquals(WorkspaceStatus.READY, reloaded.status)
        assertEquals("ws-${workspace.id}", reloaded.pvcName)
    }

    @Test
    fun `creating workspace stays creating while pvc is pending`() {
        val workspace = createWorkspace()
        val provisioner = FakeProvisioner().apply { state = PvcState.PENDING }
        val reconciler = newReconciler(provisioner)

        reconciler.reconcileOnce()

        val reloaded = repository.findById(workspace.id)
        assertNotNull(reloaded)
        assertEquals(WorkspaceStatus.CREATING, reloaded.status)
        assertEquals("ws-${workspace.id}", reloaded.pvcName)
    }

    @Test
    fun `provisioning failures are retried before moving to error`() {
        val workspace = createWorkspace()
        val provisioner = FakeProvisioner().apply { failuresRemaining = 2 }
        val reconciler = newReconciler(provisioner, maxAttempts = 3)

        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)

        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)

        // Third attempt succeeds, so the workspace never reaches ERROR.
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.READY, repository.findById(workspace.id)!!.status)
    }

    @Test
    fun `provisioning failure moves workspace to error after max attempts`() {
        val workspace = createWorkspace()
        val provisioner = FakeProvisioner().apply { failuresRemaining = Int.MAX_VALUE }
        val reconciler = newReconciler(provisioner, maxAttempts = 3)

        repeat(3) { reconciler.reconcileOnce() }

        val reloaded = repository.findById(workspace.id)
        assertNotNull(reloaded)
        assertEquals(WorkspaceStatus.ERROR, reloaded.status)
        assertEquals("boom", reloaded.errorMessage)
    }

    @Test
    fun `deleting Workspace with a Runtime holds PVC deletion until writer proof exists`() {
        val workspace = createWorkspace("runtime-block-${System.nanoTime()}")
        val runtime = runtimes.create(workspace.id, "runtime-template", null, null, null, null)
        assertNotNull(runtime.id)
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))

        val provisioner = FakeProvisioner()
        val reconciler = newReconciler(provisioner)
        repeat(4) { reconciler.reconcileOnce() }

        assertEquals(WorkspaceStatus.DELETING, repository.findById(workspace.id)!!.status)
        assertTrue(provisioner.deleteRequestedNames.isEmpty())
        assertTrue(runtimes.hasRuntimeHistory(workspace.id))
    }

    @Test
    fun `DELETED Runtime tombstone is not a writer termination receipt`() {
        val workspace = createWorkspace("old-runtime-${System.nanoTime()}")
        val runtime = runtimes.create(workspace.id, "runtime-template", null, null, null, null)
        assertTrue(runtimes.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.DELETING))
        assertTrue(runtimes.transition(runtime.id, RuntimeStatus.DELETING, RuntimeStatus.DELETED))
        // Public list hides DELETED rows, but they must still block cleanup.
        assertEquals(0L, runtimes.list(workspace.id, null, 10, 0).total)
        assertTrue(runtimes.hasRuntimeHistory(workspace.id))
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))

        val provisioner = FakeProvisioner()
        repeat(2) { newReconciler(provisioner).reconcileOnce() }

        assertEquals(WorkspaceStatus.DELETING, repository.findById(workspace.id)!!.status)
        assertTrue(provisioner.deleteRequestedNames.isEmpty())
    }

    @Test
    fun `deletion is allowed for a Workspace that has no Runtime history`() {
        val workspace = createWorkspace("never-attached-${System.nanoTime()}")
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))
        val provisioner = FakeProvisioner()
        newReconciler(provisioner).reconcileOnce()
        assertEquals(WorkspaceStatus.DELETED, repository.findById(workspace.id)!!.status)
        assertEquals(listOf("ws-${workspace.id}"), provisioner.deleteRequestedNames)
        assertTrue(!runtimes.hasRuntimeHistory(workspace.id))
    }

    @Test
    fun `deleting workspace becomes deleted when pvc is gone`() {
        val workspace = createWorkspace()
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))

        val reconciler = newReconciler(FakeProvisioner())
        reconciler.reconcileOnce()

        assertEquals(WorkspaceStatus.DELETED, repository.findById(workspace.id)!!.status)
    }

    @Test
    fun `deleting workspace stays deleting while pvc removal is pending`() {
        val workspace = createWorkspace()
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))

        val reconciler = newReconciler(FakeProvisioner().apply { deleteConfirmationsRemaining = 1 })
        reconciler.reconcileOnce()

        assertEquals(WorkspaceStatus.DELETING, repository.findById(workspace.id)!!.status)
    }

    /**
     * pvc deletion is a two-phase protocol: the request cycle answers false, the
     * confirming cycle answers true, so DELETING -> DELETED takes two cycles
     * just like with the real provisioner.
     */
    @Test
    fun `pvc deletion takes two cycles before the workspace becomes deleted`() {
        val workspace = createWorkspace()
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.READY, WorkspaceStatus.DELETING))

        val provisioner = FakeProvisioner().apply { deleteConfirmationsRemaining = 1 }
        val reconciler = newReconciler(provisioner)

        // Cycle 1: delete requested, not confirmed yet.
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.DELETING, repository.findById(workspace.id)!!.status)

        // Cycle 2: the PVC is gone (404), so the workspace may be retired.
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.DELETED, repository.findById(workspace.id)!!.status)

        assertEquals(2, provisioner.deleteRequestedNames.size)
        assertTrue(provisioner.deleteRequestedNames.all { it == "ws-${workspace.id}" })
    }

    /**
     * DELETE is accepted while the workspace is still CREATING (plan chapter
     * 6.2). pvc_name is not persisted yet in that case, so deletion has to
     * resolve the deterministic `ws-<id>` name instead of leaking the PVC.
     */
    @Test
    fun `delete while creating drives the workspace to deleted via the pvc name fallback`() {
        val workspace = createWorkspace()
        assertEquals(null, workspace.pvcName, "CREATING rows must not have a pvc_name yet")
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))

        val provisioner = FakeProvisioner().apply { deleteConfirmationsRemaining = 1 }
        val reconciler = newReconciler(provisioner)

        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.DELETING, repository.findById(workspace.id)!!.status)

        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.DELETED, repository.findById(workspace.id)!!.status)

        assertEquals(listOf("ws-${workspace.id}", "ws-${workspace.id}"), provisioner.deleteRequestedNames)
    }

    /**
     * The failure counters are in-memory, so they must be dropped as soon as a
     * workspace leaves the CREATING set; otherwise the map grows forever.
     * Restart tolerance (persisting the counter in a schema column) is a
     * follow-up.
     */
    @Test
    fun `failure counters are dropped once a workspace leaves the creating set`() {
        val workspace = createWorkspace()
        val reconciler = newReconciler(
            FakeProvisioner().apply {
                failuresRemaining = 1
                // The PVC removal below never confirms.
                deleteConfirmationsRemaining = Int.MAX_VALUE
            },
            maxAttempts = 3,
        )

        // One failed attempt leaves a pending counter behind.
        reconciler.reconcileOnce()
        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)
        assertEquals(1, reconciler.trackedAttempts())

        // A DELETE takes it out of the CREATING set, and the PVC removal below
        // never confirms, so nothing else would ever drop the counter.
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))
        reconciler.reconcileOnce()

        assertEquals(WorkspaceStatus.DELETING, repository.findById(workspace.id)!!.status)
        assertEquals(0, reconciler.trackedAttempts())
    }

    @Test
    fun `pvc name is not written for a workspace that already left creating`() {
        val workspace = createWorkspace()
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))

        // setPvcName is guarded by status='CREATING'.
        assertEquals(false, repository.setPvcName(workspace.id, "ws-${workspace.id}"))
        assertEquals(null, repository.findById(workspace.id)!!.pvcName)
    }

    @Test
    fun `state machine rejects illegal transitions`() {
        val workspace = createWorkspace()

        // CREATING cannot jump to DELETED; the repository refuses to update.
        assertTrue(!WorkspaceStatus.canTransition(WorkspaceStatus.CREATING, WorkspaceStatus.DELETED))
        assertTrue(!WorkspaceStatus.canTransition(WorkspaceStatus.DELETED, WorkspaceStatus.READY))
        assertTrue(WorkspaceStatus.canTransition(WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        assertTrue(WorkspaceStatus.canTransition(WorkspaceStatus.READY, WorkspaceStatus.DELETING))
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        // Conditional update: the same transition cannot be replayed.
        assertTrue(!repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
    }

    @Test
    fun `transition throws when the state machine table is violated`() {
        val workspace = createWorkspace()

        // The guard fires before any SQL runs, so the row is untouched.
        assertFailsWith<IllegalArgumentException> {
            repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETED)
        }
        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)

        assertFailsWith<IllegalArgumentException> {
            repository.transition(workspace.id, WorkspaceStatus.DELETED, WorkspaceStatus.READY)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.transition(workspace.id, WorkspaceStatus.READY, WorkspaceStatus.READY)
        }

        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)
    }

    @Test
    fun `list hides deleted workspaces`() {
        val keep = createWorkspace()
        val drop = createWorkspace()
        assertTrue(repository.transition(drop.id, WorkspaceStatus.CREATING, WorkspaceStatus.DELETING))
        assertTrue(repository.transition(drop.id, WorkspaceStatus.DELETING, WorkspaceStatus.DELETED))

        val all = repository.list(status = null, limit = 100, offset = 0)
        assertEquals(listOf(keep.id), all.items.map { it.id })
        assertEquals(1, all.total)

        // An explicit DELETED filter cannot resurrect the tombstone either.
        val deleted = repository.list(status = WorkspaceStatus.DELETED, limit = 100, offset = 0)
        assertEquals(emptyList(), deleted.items)
        assertEquals(0, deleted.total)
    }

    /**
     * A PVC that is accepted but never Bound raises no exception, so
     * maxAttempts never runs out and the row would sit in CREATING forever.
     * The age is taken from `created_at` so a controller restart cannot hand the
     * workspace a fresh grace period.
     */
    @Test
    fun `a pvc that never binds moves the workspace to error once the creating timeout elapses`() {
        val workspace = createWorkspace()
        val provisioner = FakeProvisioner().apply { state = PvcState.PENDING }
        // A row created now, observed 10 minutes later.
        val reconciler = newReconciler(
            provisioner,
            creatingTimeoutSeconds = 300,
            clock = { Instant.now() + Duration.ofMinutes(10) },
        )

        reconciler.reconcileOnce()

        val reloaded = repository.findById(workspace.id)
        assertNotNull(reloaded)
        assertEquals(WorkspaceStatus.ERROR, reloaded.status)
        assertTrue(
            reloaded.errorMessage!!.contains("was not bound within 300s"),
            "the timeout reason must be recorded, got ${reloaded.errorMessage}",
        )
        assertEquals(0, reconciler.trackedAttempts(), "no failure counter is involved in the timeout path")
    }

    /** A timed-out workspace must not keep hitting the Kubernetes API. */
    @Test
    fun `a timed out workspace is no longer provisioned`() {
        val workspace = createWorkspace()
        val provisioner = FakeProvisioner().apply { state = PvcState.PENDING }
        val reconciler = newReconciler(
            provisioner,
            creatingTimeoutSeconds = 60,
            clock = { Instant.now() + Duration.ofMinutes(10) },
        )

        reconciler.reconcileOnce()

        assertEquals(emptyList(), provisioner.ensurePvcCalls, "ensurePvc must be skipped for a timed out row")
    }

    /** A workspace younger than the timeout keeps being reconciled normally. */
    @Test
    fun `a workspace younger than the creating timeout keeps reconciling`() {
        val workspace = createWorkspace()
        val provisioner = FakeProvisioner().apply { state = PvcState.PENDING }
        val reconciler = newReconciler(provisioner, creatingTimeoutSeconds = 3600)

        reconciler.reconcileOnce()

        assertEquals(WorkspaceStatus.CREATING, repository.findById(workspace.id)!!.status)
        assertEquals(listOf(workspace.id), provisioner.ensurePvcCalls)
    }

    /** The row is already READY / DELETED by the time the clock advances: no double transition. */
    @Test
    fun `the creating timeout does not resurrect a workspace that already left creating`() {
        val workspace = createWorkspace()
        assertTrue(repository.transition(workspace.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))

        val reconciler = newReconciler(
            FakeProvisioner(),
            creatingTimeoutSeconds = 1,
            clock = { Instant.now() + Duration.ofHours(1) },
        )

        reconciler.reconcileOnce()

        val reloaded = repository.findById(workspace.id)
        assertEquals(WorkspaceStatus.READY, reloaded!!.status)
        assertNull(reloaded.errorMessage)
    }
}