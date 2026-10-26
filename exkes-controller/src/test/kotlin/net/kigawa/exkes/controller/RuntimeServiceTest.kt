package net.kigawa.exkes.controller

import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Service
import io.kubernetes.client.openapi.models.V1ServicePort
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.common.domain.KubernetesRuntimeException
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus
import net.kigawa.exkes.controller.runtime.RuntimeServiceManager
import net.kigawa.exkes.controller.runtime.RuntimeServiceStore
import net.kigawa.exkes.controller.runtime.buildRuntimeService
import net.kigawa.exkes.controller.runtime.isServiceOwnedBy
import net.kigawa.exkes.controller.runtime.matchesRuntimeService
import net.kigawa.exkes.controller.runtime.runtimeServiceSelector
import net.kigawa.exkes.controller.runtime.runtimeSystemLabels

/** Unit tests for the runtime Service rules, using an in-memory store (no Kubernetes API). */
class RuntimeServiceTest {
    private val namespace = "platform-exkes-stg-sandbox"

    /** Models the Service objects in one namespace, including a UID precondition on delete. */
    private class InMemoryStore(override val namespace: String) : RuntimeServiceStore {
        val objects = mutableMapOf<String, V1Service>()
        var createCalls = 0
        val deletes = mutableListOf<Pair<String, String>>()

        /** Runs once before the next create (simulates a racing actor winning the name). */
        var beforeCreate: (() -> Unit)? = null

        override fun read(name: String): V1Service? = objects[name]

        override fun create(service: V1Service): Boolean {
            createCalls++
            beforeCreate?.let { beforeCreate = null; it() }
            val name = service.metadata!!.name!!
            if (name in objects) return false
            service.metadata!!.uid = "uid-$name-${objects.size}"
            objects[name] = service
            return true
        }

        override fun delete(name: String, uid: String) {
            deletes += name to uid
            val current = objects[name] ?: return
            check(current.metadata?.uid == uid) { "precondition uid mismatch" }
            objects.remove(name)
        }
    }

    private fun runtime(id: String): Runtime = Runtime(
        id = id,
        workspaceId = "ws-1",
        templateId = "tpl-1",
        status = RuntimeStatus.STARTING,
        resourcesJson = null,
        envJson = null,
        ttlSeconds = null,
        podName = null,
        errorMessage = null,
        labelsJson = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** Label set of the Pod exkes creates for [rt]; user labels cannot override system keys. */
    private fun podLabels(rt: Runtime): Map<String, String> = runtimeSystemLabels(rt)

    private fun selects(service: V1Service, labels: Map<String, String>): Boolean =
        service.spec!!.selector!!.all { (k, v) -> labels[k] == v }

    private fun ownedService(name: String, rt: Runtime, uid: String = "uid-$name"): V1Service =
        buildRuntimeService(rt, name, namespace).also { it.metadata!!.uid = uid }

    @Test
    fun `built Service is a ClusterIP that forwards port 8080 to the agent of its own Runtime`() {
        val rt = runtime("abc")
        val service = buildRuntimeService(rt, "rt-abc", namespace)
        assertEquals("rt-abc", service.metadata!!.name)
        assertEquals(namespace, service.metadata!!.namespace)
        assertEquals("ClusterIP", service.spec!!.type)
        assertEquals(mapOf("app.kubernetes.io/managed-by" to "exkes", "exkes.runtime-id" to "abc"), service.spec!!.selector)
        assertEquals(runtimeSystemLabels(rt), service.metadata!!.labels)
        val ports = service.spec!!.ports!!
        assertEquals(1, ports.size)
        assertEquals("agent", ports[0].name)
        assertEquals(8080, ports[0].port)
        assertEquals(8080, ports[0].targetPort!!.intValue)
        assertEquals("TCP", ports[0].protocol)
    }

    @Test
    fun `selector identifies only the Pod of its own Runtime`() {
        val a = runtime("aaa")
        val b = runtime("bbb")
        val serviceA = buildRuntimeService(a, "rt-aaa", namespace)
        assertTrue(selects(serviceA, podLabels(a)))
        assertFalse(selects(serviceA, podLabels(b)))
        assertEquals(runtimeServiceSelector(a), serviceA.spec!!.selector)
        // A user label cannot make Runtime b's Pod match a: system keys are applied after user labels.
        val spoofed = mapOf("exkes.runtime-id" to "aaa") + podLabels(b)
        assertFalse(selects(serviceA, spoofed))
    }

    @Test
    fun `ensure creates the Service when absent and does not duplicate it`() {
        val rt = runtime("r1")
        val store = InMemoryStore(namespace)
        val manager = RuntimeServiceManager(store)
        manager.ensure(rt)
        manager.ensure(rt)
        assertEquals(setOf("rt-r1"), store.objects.keys)
        assertTrue(matchesRuntimeService(store.objects["rt-r1"]!!, buildRuntimeService(rt, "rt-r1", namespace)))
        assertEquals(1, store.createCalls)
    }

    @Test
    fun `ensure reuses a racing Service that exactly matches the runtime`() {
        val rt = runtime("r2")
        val store = InMemoryStore(namespace)
        store.beforeCreate = { store.objects["rt-r2"] = ownedService("rt-r2", rt) }
        RuntimeServiceManager(store).ensure(rt)
        assertEquals(setOf("rt-r2"), store.objects.keys)
        assertEquals(1, store.createCalls)
    }

    @Test
    fun `ensure refuses a Service with the same name owned by another Runtime`() {
        val rt = runtime("r3")
        val other = runtime("other")
        val store = InMemoryStore(namespace)
        store.objects["rt-r3"] = ownedService("rt-r3", other)
        assertFailsWith<KubernetesRuntimeException> { RuntimeServiceManager(store).ensure(rt) }
        assertEquals(other.id, store.objects["rt-r3"]!!.metadata!!.labels!!["exkes.runtime-id"])
        assertEquals(0, store.createCalls)
    }

    @Test
    fun `ensure refuses a Service owned by the Runtime whose selector or port drifted`() {
        val rt = runtime("r4")
        val store = InMemoryStore(namespace)
        val drifted = ownedService("rt-r4", rt)
        drifted.spec!!.selector = mapOf("app.kubernetes.io/managed-by" to "exkes")
        store.objects["rt-r4"] = drifted
        assertFailsWith<KubernetesRuntimeException> { RuntimeServiceManager(store).ensure(rt) }

        val wrongPort = ownedService("rt-r4", rt)
        wrongPort.spec!!.ports = listOf(V1ServicePort().name("agent").protocol("TCP").port(80).targetPort(IntOrString(8080)))
        store.objects["rt-r4"] = wrongPort
        assertFailsWith<KubernetesRuntimeException> { RuntimeServiceManager(store).ensure(rt) }
    }

    @Test
    fun `ownership is decided by exkes labels for the Runtime id only`() {
        val rt = runtime("own")
        val service = buildRuntimeService(rt, "rt-own", namespace)
        assertTrue(isServiceOwnedBy(service, "own"))
        assertFalse(isServiceOwnedBy(service, "someone-else"))
        assertFalse(isServiceOwnedBy(V1Service().metadata(V1ObjectMeta().name("rt-own")), "own"))
        assertFalse(isServiceOwnedBy(V1Service(), "own"))
    }

    @Test
    fun `delete removes only the Runtime's own Service and leaves other Runtimes alone`() {
        val a = runtime("ra")
        val b = runtime("rb")
        val store = InMemoryStore(namespace)
        val manager = RuntimeServiceManager(store)
        manager.ensure(a)
        manager.ensure(b)
        assertTrue(manager.delete(a))
        assertNull(store.objects["rt-ra"])
        assertNotNull(store.objects["rt-rb"])
        assertTrue(isServiceOwnedBy(store.objects["rt-rb"]!!, "rb"))
    }

    @Test
    fun `delete succeeds when the Service is already absent`() {
        val store = InMemoryStore(namespace)
        assertTrue(RuntimeServiceManager(store).delete(runtime("gone")))
        assertTrue(store.deletes.isEmpty())
    }

    @Test
    fun `delete never removes a foreign Service that happens to carry the same name`() {
        val rt = runtime("fr")
        val store = InMemoryStore(namespace)
        store.objects["rt-fr"] = V1Service()
            .metadata(V1ObjectMeta().name("rt-fr").uid("foreign").labels(mapOf("app" to "someone")))
        assertTrue(RuntimeServiceManager(store).delete(rt))
        assertNotNull(store.objects["rt-fr"])
        assertTrue(store.deletes.isEmpty())
    }

    @Test
    fun `delete is conditioned on the UID of the Service it verified`() {
        val rt = runtime("uid")
        val store = InMemoryStore(namespace)
        store.objects["rt-uid"] = ownedService("rt-uid", rt, uid = "verified-uid")
        assertTrue(RuntimeServiceManager(store).delete(rt))
        assertEquals(listOf("rt-uid" to "verified-uid"), store.deletes)
    }

    @Test
    fun `delete refuses an owned Service without a UID`() {
        val rt = runtime("nouid")
        val store = InMemoryStore(namespace)
        store.objects["rt-nouid"] = buildRuntimeService(rt, "rt-nouid", namespace)
        assertFailsWith<KubernetesRuntimeException> { RuntimeServiceManager(store).delete(rt) }
        assertNotNull(store.objects["rt-nouid"])
    }
}
