package net.kigawa.exkes.controller

import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.common.config.DatabaseConfig
import net.kigawa.exkes.common.db.RuntimeRepository
import net.kigawa.exkes.common.db.RuntimeTemplateRepository
import net.kigawa.exkes.common.db.WorkspaceRepository
import net.kigawa.exkes.common.db.connectDatabase
import net.kigawa.exkes.common.db.createDataSource
import net.kigawa.exkes.common.db.runMigrations
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.common.domain.WorkspaceStatus
import net.kigawa.exkes.controller.runtime.RuntimeProvisioner
import net.kigawa.exkes.controller.runtime.RuntimeReconciler
import net.kigawa.exkes.controller.runtime.RuntimePvcUnverifiedException
import net.kigawa.exkes.controller.runtime.isPodOwnedBy
import net.kigawa.exkes.controller.runtime.mapPodToRuntimeStatus

/** State machine tests for RuntimeReconciler with a fake provisioner (no real Kubernetes). */
class RuntimeReconcilerTest {
    private lateinit var runtimes: RuntimeRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var workspaceId: String
    private val templateId = "tpl-test"
    private val verifiedNamespace = "platform-exkes-stg-sandbox"
    private var now: Instant = Instant.now()

    /** Models the set of Pods that exist; stop/delete remove the Pod immediately. */
    private class FakeProvisioner : RuntimeProvisioner {
        val pods = mutableSetOf<String>()
        var podStatus: RuntimeStatus = RuntimeStatus.RUNNING
        var createFailures = 0
        var livePvcUnavailable = false
        val createCalls = mutableListOf<String>()

        /** Runs once at the start of the next startRuntime call (simulates a racing actor). */
        var beforeStart: (() -> Unit)? = null

        /** Runs once at the start of the next getRuntimeStatus call (simulates a racing writer). */
        var beforeStatus: (() -> Unit)? = null

        override fun createRuntime(runtime: Runtime): String {
            if (livePvcUnavailable) throw RuntimePvcUnverifiedException("live PVC UID unavailable")
            if (createFailures > 0) {
                createFailures--
                throw RuntimeException("boom")
            }
            createCalls += runtime.id
            pods += resolvePodName(runtime)
            return resolvePodName(runtime)
        }

        override fun startRuntime(runtime: Runtime) {
            if (livePvcUnavailable) throw RuntimePvcUnverifiedException("live PVC UID unavailable")
            beforeStart?.let { beforeStart = null; it() }
            if (resolvePodName(runtime) !in pods) createRuntime(runtime)
        }

        override fun stopRuntime(runtime: Runtime): Boolean {
            pods.remove(resolvePodName(runtime))
            return true
        }

        override fun deleteRuntime(runtime: Runtime): Boolean = stopRuntime(runtime)

        override fun getRuntimeStatus(runtime: Runtime): RuntimeStatus {
            beforeStatus?.let { beforeStatus = null; it() }
            return if (resolvePodName(runtime) !in pods) RuntimeStatus.FAILED else podStatus
        }

        override fun streamRuntimeLogs(runtime: Runtime): String = ""

        /** Service name -> owning runtime id; models the Services exkes created (Service name = Pod name). */
        val services = mutableMapOf<String, String>()
        var serviceCreateFailures = 0
        var serviceDeleteFailures = 0

        override fun ensureService(runtime: Runtime) {
            if (serviceCreateFailures > 0) {
                serviceCreateFailures--
                throw RuntimeException("service boom")
            }
            services.putIfAbsent(resolvePodName(runtime), runtime.id)
        }

        /** Deletes only a Service owned by [runtime]; a foreign Service is left untouched. */
        override fun deleteService(runtime: Runtime): Boolean {
            if (serviceDeleteFailures > 0) {
                serviceDeleteFailures--
                throw RuntimeException("service delete boom")
            }
            val name = resolvePodName(runtime)
            if (services[name] == runtime.id) services.remove(name)
            return true
        }
    }

    private val provisioner = FakeProvisioner()
    private lateinit var reconciler: RuntimeReconciler

    @BeforeTest
    fun setup() {
        val ds = createDataSource(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:exkes_rt_reconciler_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        runMigrations(ds)
        connectDatabase(ds)
        runtimes = RuntimeRepository()
        workspaces = WorkspaceRepository()
        RuntimeTemplateRepository().create(templateId, "t", null, "img", null, null, null, null)
        val ws = workspaces.create("ws-${System.nanoTime()}", "sc", 1024L, emptyMap())
        assertTrue(workspaces.reservePlacementForCreating(ws.id, verifiedNamespace))
        assertTrue(workspaces.setPvcName(ws.id, "ws-${ws.id}"))
        assertTrue(workspaces.recordVerifiedPvcUid(ws.id, verifiedNamespace, "uid-${ws.id}"))
        workspaces.transition(ws.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY)
        workspaceId = ws.id
        reconciler = RuntimeReconciler(runtimes, workspaces, provisioner, resourceNamespace = verifiedNamespace, maxAttempts = 2, clock = { now })
    }

    private fun newRuntime(ttl: Long? = null): Runtime =
        runtimes.create(workspaceId, templateId, null, null, ttl, null)

    private fun status(id: String) = runtimes.findById(id)!!.status

    @Test
    fun `legacy READY Workspace without PVC identity does not create a Runtime Pod`() {
        val legacy = workspaces.create("legacy-${System.nanoTime()}", "sc", 1024L, emptyMap())
        assertTrue(workspaces.transition(legacy.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        val runtime = runtimes.create(legacy.id, templateId, null, null, null, null)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(runtime.id))
        assertTrue(provisioner.createCalls.isEmpty())
    }

    @Test
    fun `Workspace verified in another Namespace does not create a Runtime Pod`() {
        val foreign = workspaces.create("foreign-${System.nanoTime()}", "sc", 1024L, emptyMap())
        assertTrue(workspaces.reservePlacementForCreating(foreign.id, "platform-exkes-main-sandbox"))
        assertTrue(workspaces.setPvcName(foreign.id, "ws-${foreign.id}"))
        assertTrue(workspaces.recordVerifiedPvcUid(foreign.id, "platform-exkes-main-sandbox", "uid-${foreign.id}"))
        assertTrue(workspaces.transition(foreign.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY))
        val runtime = runtimes.create(foreign.id, templateId, null, null, null, null)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(runtime.id))
        assertTrue(provisioner.createCalls.isEmpty())
    }

    @Test
    fun `missing PVC identity holds creating intent instead of falsely terminating a Pod`() {
        val runtime = newRuntime()
        provisioner.livePvcUnavailable = true
        repeat(5) { reconciler.reconcileOnce() }
        assertEquals(RuntimeStatus.CREATING, status(runtime.id))
        assertTrue(provisioner.createCalls.isEmpty())
        provisioner.livePvcUnavailable = false
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STARTING, status(runtime.id))
    }

    @Test
    fun `missing PVC identity holds starting intent without marking previous Pod terminated`() {
        val runtime = newRuntime()
        assertTrue(runtimes.transition(runtime.id, RuntimeStatus.CREATING, RuntimeStatus.STARTING))
        provisioner.livePvcUnavailable = true
        repeat(5) { reconciler.reconcileOnce() }
        assertEquals(RuntimeStatus.STARTING, status(runtime.id))
        provisioner.livePvcUnavailable = false
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(runtime.id))
    }

    @Test
    fun `creating runtime gets a pod and walks to running`() {
        val rt = newRuntime()
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
        assertEquals(Runtime.podNameFor(rt.id), runtimes.findById(rt.id)!!.podName)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
    }

    @Test
    fun `starting stays starting until pod is ready`() {
        val rt = newRuntime()
        provisioner.podStatus = RuntimeStatus.STARTING
        repeat(3) { reconciler.reconcileOnce() }
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
    }

    @Test
    fun `creating fails after max attempts`() {
        val rt = newRuntime()
        provisioner.createFailures = 5
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.CREATING, status(rt.id))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
        assertNotNull(runtimes.findById(rt.id)!!.errorMessage)
    }

    @Test
    fun `creating fails when workspace is not attachable`() {
        val ws = workspaces.create("ws-bad-${System.nanoTime()}", "sc", 1024L, emptyMap())
        val rt = runtimes.create(ws.id, templateId, null, null, null, null)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
        assertTrue(provisioner.createCalls.isEmpty())
    }

    @Test
    fun `stop deletes the pod and keeps the workspace`() {
        val rt = newRuntime()
        repeat(2) { reconciler.reconcileOnce() }
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPED, status(rt.id))
        assertTrue(provisioner.pods.isEmpty())
        assertEquals(WorkspaceStatus.READY, workspaces.findById(workspaceId)!!.status)
    }

    @Test
    fun `restart after stop recreates the pod and returns to running`() {
        val rt = newRuntime()
        repeat(2) { reconciler.reconcileOnce() }
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        assertTrue(provisioner.pods.isEmpty())

        runtimes.transition(rt.id, RuntimeStatus.STOPPED, RuntimeStatus.STARTING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertEquals(2, provisioner.createCalls.size)
    }

    @Test
    fun `restart fails when workspace is no longer attachable`() {
        val rt = newRuntime()
        repeat(2) { reconciler.reconcileOnce() }
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        workspaces.transition(workspaceId, WorkspaceStatus.READY, WorkspaceStatus.DELETING)
        runtimes.transition(rt.id, RuntimeStatus.STOPPED, RuntimeStatus.STARTING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
    }

    @Test
    fun `running runtime past ttl is expired then deleted`() {
        val rt = newRuntime(ttl = 60)
        repeat(2) { reconciler.reconcileOnce() }
        now = Instant.now().plus(Duration.ofSeconds(120))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.EXPIRED, status(rt.id))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DELETING, status(rt.id))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DELETED, status(rt.id))
        assertTrue(provisioner.pods.isEmpty())
    }

    @Test
    fun `stopped runtime past ttl is cleaned up`() {
        val rt = newRuntime(ttl = 60)
        repeat(2) { reconciler.reconcileOnce() }
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        now = Instant.now().plus(Duration.ofSeconds(120))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DELETING, status(rt.id))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DELETED, status(rt.id))
    }

    @Test
    fun `pod loss while running marks runtime failed`() {
        val rt = newRuntime()
        repeat(2) { reconciler.reconcileOnce() }
        provisioner.pods.clear()
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
        assertNotNull(runtimes.findById(rt.id)!!.errorMessage)
    }

    @Test
    fun `pod that terminated while running marks runtime failed`() {
        val rt = newRuntime()
        repeat(2) { reconciler.reconcileOnce() }
        provisioner.podStatus = RuntimeStatus.STOPPED
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
    }

    @Test
    fun `a new reconciler resumes from db state after restart`() {
        val rt = newRuntime()
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
        val restarted = RuntimeReconciler(runtimes, workspaces, provisioner, resourceNamespace = verifiedNamespace, clock = { now })
        restarted.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.DELETING)
        restarted.reconcileOnce()
        assertEquals(RuntimeStatus.DELETED, status(rt.id))
    }

    @Test
    fun `pod phase mapping requires ready condition for running`() {
        fun pod(phase: String, ready: Boolean? = null) = V1Pod().status(
            V1PodStatus().phase(phase).apply {
                if (ready != null) conditions(listOf(V1PodCondition().type("Ready").status(if (ready) "True" else "False")))
            },
        )
        assertEquals(RuntimeStatus.STARTING, mapPodToRuntimeStatus(pod("Pending")))
        assertEquals(RuntimeStatus.STARTING, mapPodToRuntimeStatus(pod("Running", ready = false)))
        assertEquals(RuntimeStatus.RUNNING, mapPodToRuntimeStatus(pod("Running", ready = true)))
        assertEquals(RuntimeStatus.FAILED, mapPodToRuntimeStatus(pod("Failed")))
        assertEquals(RuntimeStatus.STOPPED, mapPodToRuntimeStatus(pod("Succeeded")))
        assertEquals(RuntimeStatus.FAILED, mapPodToRuntimeStatus(V1Pod()))
    }

    private fun runToRunning(rt: Runtime) = repeat(2) { reconciler.reconcileOnce() }.also {
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
    }

    @Test
    fun `not ready pod within grace period keeps runtime running`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        now = now.plusSeconds(10)
        reconciler.reconcileOnce()
        now = now.plusSeconds(10)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
    }

    @Test
    fun `not ready pod past grace period moves running to degraded and back on ready`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce() // NotReady first observed
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        now = now.plusSeconds(31)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))
        assertEquals(1, reconciler.trackedNotReady())

        // Still NotReady: stays DEGRADED
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))

        // Ready again: recovers to RUNNING and the NotReady timer is cleared
        provisioner.podStatus = RuntimeStatus.RUNNING
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertEquals(0, reconciler.trackedNotReady())
    }

    @Test
    fun `ready observation opens admission when running and not ready closes it at once`() {
        val rt = newRuntime()
        runToRunning(rt)
        assertNotNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)

        provisioner.podStatus = RuntimeStatus.STARTING
        now = now.plusSeconds(1)
        reconciler.reconcileOnce() // first NotReady observation, well inside the grace period
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `Kubernetes PVC observation error revokes Runtime Ready without faking process death`() {
        val rt = newRuntime()
        runToRunning(rt)
        assertNotNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
        provisioner.beforeStatus = { throw IllegalStateException("live PVC UID cannot be verified") }
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
        // A successful later observation can only reopen admission after live PVC verification.
        reconciler.reconcileOnce()
        assertNotNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `Workspace deleting clears fresh Ready observation of a running Pod`() {
        val rt = newRuntime()
        runToRunning(rt)
        assertNotNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
        assertTrue(workspaces.transition(workspaceId, WorkspaceStatus.READY, WorkspaceStatus.DELETING))

        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)

        // Even if the Pod reports Ready again, an unusable Workspace cannot
        // reopen admission.
        provisioner.podStatus = RuntimeStatus.RUNNING
        reconciler.reconcileOnce()
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `TTL cleanup is not prevented by an invalid Workspace placement`() {
        val rt = newRuntime(ttl = 10)
        runToRunning(rt)
        assertTrue(workspaces.transition(workspaceId, WorkspaceStatus.READY, WorkspaceStatus.DELETING))
        now = rt.createdAt.plusSeconds(11)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.EXPIRED, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `degraded Runtime cannot recover with stale Workspace placement`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce()
        now = now.plusSeconds(31)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))

        assertTrue(workspaces.transition(workspaceId, WorkspaceStatus.READY, WorkspaceStatus.DELETING))
        provisioner.podStatus = RuntimeStatus.RUNNING
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `admission stays closed after controller restart while the pod is still not ready`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce()
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)

        // Restart: the in-memory NotReady timer is gone, the DB keeps admission closed.
        val restarted = RuntimeReconciler(runtimes, workspaces, provisioner, resourceNamespace = verifiedNamespace, maxAttempts = 2, clock = { now })
        now = now.plusSeconds(10)
        restarted.reconcileOnce()
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
    }

    @Test
    fun `admission stays closed in degraded and reopens only when the pod is ready again`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce()
        now = now.plusSeconds(31)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)

        provisioner.podStatus = RuntimeStatus.RUNNING
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertNotNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `starting to running records the ready observation in the same cycle`() {
        val rt = newRuntime()
        reconciler.reconcileOnce()
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertNotNull(runtimes.findById(rt.id)!!.lastReadyObservedAt)
    }

    @Test
    fun `a ready blip restarts the grace period`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce()
        now = now.plusSeconds(20)
        provisioner.podStatus = RuntimeStatus.RUNNING
        reconciler.reconcileOnce()
        provisioner.podStatus = RuntimeStatus.STARTING
        now = now.plusSeconds(5)
        reconciler.reconcileOnce() // new NotReady period starts here
        now = now.plusSeconds(20) // 20s into the new period, 25s since the first
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
    }

    @Test
    fun `degraded runtime whose pod disappears fails`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce()
        now = now.plusSeconds(31)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))
        provisioner.pods.clear()
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
        assertNotNull(runtimes.findById(rt.id)!!.errorMessage)
    }

    @Test
    fun `degraded runtime past ttl expires`() {
        val rt = newRuntime(ttl = 60)
        runToRunning(rt)
        provisioner.podStatus = RuntimeStatus.STARTING
        reconciler.reconcileOnce()
        now = now.plusSeconds(31)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DEGRADED, status(rt.id))
        now = now.plusSeconds(60)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.EXPIRED, status(rt.id))
    }

    @Test
    fun `pod loss that loses the race to a stop does not overwrite stopping`() {
        val rt = newRuntime()
        runToRunning(rt)
        // Between the reconciler's snapshot and its write, the API requests a stop
        // and the Pod disappears. The conditional UPDATE (WHERE status = RUNNING) must lose.
        provisioner.beforeStatus = {
            assertTrue(runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING))
            provisioner.pods.clear()
        }
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPING, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.errorMessage)
    }

    @Test
    fun `restart whose pod creation keeps failing reaches failed`() {
        val rt = newRuntime()
        runToRunning(rt)
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPED, status(rt.id))
        runtimes.transition(rt.id, RuntimeStatus.STOPPED, RuntimeStatus.STARTING)
        provisioner.createFailures = 5
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
        assertNotNull(runtimes.findById(rt.id)!!.errorMessage)
    }

    @Test
    fun `restart racing with another controller creates exactly one pod`() {
        val rt = newRuntime()
        runToRunning(rt)
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        runtimes.transition(rt.id, RuntimeStatus.STOPPED, RuntimeStatus.STARTING)

        // The second controller completes the whole restart while this one is between
        // its snapshot and its own Pod creation. This controller must not create a second
        // Pod, must not fail the runtime, and its STARTING -> RUNNING write must be a no-op.
        val other = RuntimeReconciler(runtimes, workspaces, provisioner, resourceNamespace = verifiedNamespace, clock = { now })
        provisioner.beforeStart = { other.reconcileOnce() }
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertEquals(2, provisioner.createCalls.size)
        assertEquals(1, provisioner.pods.size)
    }

    @Test
    fun `pod ownership check only accepts pods labelled for the runtime`() {
        fun pod(vararg labels: Pair<String, String>?) = V1Pod().metadata(
            V1ObjectMeta().labels(labels.filterNotNull().toMap()),
        )
        val own = pod("app.kubernetes.io/managed-by" to "exkes", "exkes.runtime-id" to "rt-1")
        assertTrue(isPodOwnedBy(own, "rt-1"))
        assertFalse(isPodOwnedBy(own, "rt-2"))
        assertFalse(isPodOwnedBy(pod("exkes.runtime-id" to "rt-1"), "rt-1"))
        assertFalse(isPodOwnedBy(pod("app.kubernetes.io/managed-by" to "someone-else", "exkes.runtime-id" to "rt-1"), "rt-1"))
        assertFalse(isPodOwnedBy(V1Pod(), "rt-1"))
    }

    @Test
    fun `creating runtime gets a Service named after its Pod that is kept while running`() {
        val rt = newRuntime()
        reconciler.reconcileOnce()
        val podName = Runtime.podNameFor(rt.id)
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
        assertEquals(podName, runtimes.findById(rt.id)!!.podName)
        assertEquals(rt.id, provisioner.services[podName])
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.RUNNING, status(rt.id))
        assertEquals(rt.id, provisioner.services[podName])
    }

    @Test
    fun `starting cycles ensure the Service without creating a duplicate`() {
        val rt = newRuntime()
        provisioner.podStatus = RuntimeStatus.STARTING
        repeat(3) { reconciler.reconcileOnce() }
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
        assertEquals(mapOf(Runtime.podNameFor(rt.id) to rt.id), provisioner.services)
    }

    @Test
    fun `Service failure keeps the runtime creating and the retry adopts the same Pod`() {
        val rt = newRuntime()
        provisioner.serviceCreateFailures = 1
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.CREATING, status(rt.id))
        assertNull(runtimes.findById(rt.id)!!.podName)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STARTING, status(rt.id))
        assertEquals(1, provisioner.pods.size)
        assertEquals(rt.id, provisioner.services[Runtime.podNameFor(rt.id)])
    }

    @Test
    fun `stop removes the Service together with the Pod`() {
        val rt = newRuntime()
        runToRunning(rt)
        assertEquals(1, provisioner.services.size)
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPED, status(rt.id))
        assertTrue(provisioner.pods.isEmpty())
        assertTrue(provisioner.services.isEmpty())
    }

    @Test
    fun `stopping waits until the Service deletion is confirmed`() {
        val rt = newRuntime()
        runToRunning(rt)
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        provisioner.serviceDeleteFailures = 1
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPING, status(rt.id))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPED, status(rt.id))
        assertTrue(provisioner.services.isEmpty())
    }

    @Test
    fun `stopping succeeds when the Service is already absent`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.services.clear()
        runtimes.transition(rt.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPED, status(rt.id))
    }

    @Test
    fun `failed runtime whose Pod vanished still has its orphan Service removed on delete`() {
        val rt = newRuntime()
        runToRunning(rt)
        provisioner.pods.clear()
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.FAILED, status(rt.id))
        assertEquals(1, provisioner.services.size)
        assertTrue(runtimes.transition(rt.id, RuntimeStatus.FAILED, RuntimeStatus.DELETING))
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.DELETED, status(rt.id))
        assertTrue(provisioner.services.isEmpty())
    }

    @Test
    fun `stopping one runtime leaves another runtime's Service and Pod untouched`() {
        val stopped = newRuntime()
        val kept = newRuntime()
        runToRunning(stopped)
        runToRunning(kept)
        runtimes.transition(stopped.id, RuntimeStatus.RUNNING, RuntimeStatus.STOPPING)
        reconciler.reconcileOnce()
        assertEquals(RuntimeStatus.STOPPED, status(stopped.id))
        assertEquals(RuntimeStatus.RUNNING, status(kept.id))
        assertEquals(mapOf(Runtime.podNameFor(kept.id) to kept.id), provisioner.services)
        assertEquals(setOf(Runtime.podNameFor(kept.id)), provisioner.pods)
    }
}
