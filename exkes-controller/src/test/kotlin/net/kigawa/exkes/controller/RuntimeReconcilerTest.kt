package net.kigawa.exkes.controller

import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodCondition
import io.kubernetes.client.openapi.models.V1PodStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import net.kigawa.exkes.controller.runtime.mapPodToRuntimeStatus

/** State machine tests for RuntimeReconciler with a fake provisioner (no real Kubernetes). */
class RuntimeReconcilerTest {
    private lateinit var runtimes: RuntimeRepository
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var workspaceId: String
    private val templateId = "tpl-test"
    private var now: Instant = Instant.now()

    /** Models the set of Pods that exist; stop/delete remove the Pod immediately. */
    private class FakeProvisioner : RuntimeProvisioner {
        val pods = mutableSetOf<String>()
        var podStatus: RuntimeStatus = RuntimeStatus.RUNNING
        var createFailures = 0
        val createCalls = mutableListOf<String>()

        override fun createRuntime(runtime: Runtime): String {
            if (createFailures > 0) {
                createFailures--
                throw RuntimeException("boom")
            }
            createCalls += runtime.id
            pods += resolvePodName(runtime)
            return resolvePodName(runtime)
        }

        override fun startRuntime(runtime: Runtime) {
            if (resolvePodName(runtime) !in pods) createRuntime(runtime)
        }

        override fun stopRuntime(runtime: Runtime): Boolean {
            pods.remove(resolvePodName(runtime))
            return true
        }

        override fun deleteRuntime(runtime: Runtime): Boolean = stopRuntime(runtime)

        override fun getRuntimeStatus(runtime: Runtime): RuntimeStatus =
            if (resolvePodName(runtime) !in pods) RuntimeStatus.FAILED else podStatus

        override fun streamRuntimeLogs(runtime: Runtime): String = ""
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
        workspaces.transition(ws.id, WorkspaceStatus.CREATING, WorkspaceStatus.READY)
        workspaceId = ws.id
        reconciler = RuntimeReconciler(runtimes, workspaces, provisioner, maxAttempts = 2, clock = { now })
    }

    private fun newRuntime(ttl: Long? = null): Runtime =
        runtimes.create(workspaceId, templateId, null, null, ttl, null)

    private fun status(id: String) = runtimes.findById(id)!!.status

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
        val restarted = RuntimeReconciler(runtimes, workspaces, provisioner, clock = { now })
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
}
