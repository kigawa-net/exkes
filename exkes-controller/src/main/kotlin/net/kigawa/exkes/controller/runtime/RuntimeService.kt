package net.kigawa.exkes.controller.runtime

import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1DeleteOptions
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Preconditions
import io.kubernetes.client.openapi.models.V1Service
import io.kubernetes.client.openapi.models.V1ServicePort
import io.kubernetes.client.openapi.models.V1ServiceSpec
import net.kigawa.exkes.common.domain.KubernetesRuntimeException
import net.kigawa.exkes.common.domain.Runtime
import org.slf4j.LoggerFactory

/** Port the runtime-agent listens on inside the Runtime Pod (runtime-agent application.conf). */
internal const val RUNTIME_AGENT_PORT = 8080

/** Name of the single Service port that exposes the runtime-agent. */
internal const val RUNTIME_AGENT_PORT_NAME = "agent"

/**
 * Labels exkes sets on every Kubernetes object it creates for [runtime] (Pod and Service).
 * System-owned keys are applied last so user labels cannot override them.
 */
internal fun runtimeSystemLabels(runtime: Runtime): Map<String, String> = mapOf(
    "app.kubernetes.io/managed-by" to "exkes",
    "exkes.runtime-id" to runtime.id,
    "exkes.workspace-id" to runtime.workspaceId,
    "exkes.template-id" to runtime.templateId,
)

/**
 * Selector of the runtime Service. It matches only the Pod of [runtime]: runtime ids are unique,
 * so `exkes.runtime-id` alone already identifies one Runtime; managed-by is added as a guard.
 */
internal fun runtimeServiceSelector(runtime: Runtime): Map<String, String> = mapOf(
    "app.kubernetes.io/managed-by" to "exkes",
    "exkes.runtime-id" to runtime.id,
)

/**
 * Builds the ClusterIP Service named [name] (= Runtime Pod name, `rt-<id>`) that makes the
 * runtime-agent reachable as `<name>.<namespace>.svc.cluster.local:8080` (the terminal gateway's
 * agent URL). The Service has no effect on Pods it does not select.
 */
internal fun buildRuntimeService(runtime: Runtime, name: String, namespace: String): V1Service =
    V1Service()
        .metadata(
            V1ObjectMeta()
                .name(name)
                .namespace(namespace)
                .labels(runtimeSystemLabels(runtime)),
        )
        .spec(
            V1ServiceSpec()
                .type("ClusterIP")
                .selector(runtimeServiceSelector(runtime))
                .ports(
                    listOf(
                        V1ServicePort()
                            .name(RUNTIME_AGENT_PORT_NAME)
                            .protocol("TCP")
                            .port(RUNTIME_AGENT_PORT)
                            .targetPort(IntOrString(RUNTIME_AGENT_PORT)),
                    ),
                ),
        )

/** True when [service] carries the ownership labels exkes sets for [runtimeId]. */
internal fun isServiceOwnedBy(service: V1Service, runtimeId: String): Boolean {
    val labels = service.metadata?.labels ?: return false
    return labels["app.kubernetes.io/managed-by"] == "exkes" && labels["exkes.runtime-id"] == runtimeId
}

/**
 * True when an existing [service] has the type, selector and agent port of [expected].
 * Server-populated fields (clusterIP, uid, ...) are ignored.
 */
internal fun matchesRuntimeService(service: V1Service, expected: V1Service): Boolean {
    val spec = service.spec ?: return false
    val want = expected.spec ?: return false
    if (spec.type != want.type) return false
    if (spec.selector != want.selector) return false
    val ports = spec.ports ?: return false
    if (ports.size != 1) return false
    val port = ports[0]
    val wantPort = want.ports?.singleOrNull() ?: return false
    return port.name == wantPort.name &&
        port.protocol == wantPort.protocol &&
        port.port == wantPort.port &&
        port.targetPort?.isInteger == true &&
        port.targetPort?.intValue == RUNTIME_AGENT_PORT
}

/**
 * Minimal Kubernetes access used by [RuntimeServiceManager]. Kept as an interface so the
 * ensure/delete rules are unit-tested without a cluster; [CoreV1RuntimeServiceStore] is the
 * only production implementation.
 */
internal interface RuntimeServiceStore {
    /** Namespace the Services live in (the Runtime resource namespace). */
    val namespace: String

    /** Returns the Service named [name], or null when it does not exist. */
    fun read(name: String): V1Service?

    /** Creates [service]. Returns false when an object with the same name already exists (409). */
    fun create(service: V1Service): Boolean

    /** Deletes the Service [name] only while its UID is still [uid]. Absent (404) counts as deleted. */
    fun delete(name: String, uid: String)
}

/**
 * Creates and deletes the per-Runtime ClusterIP Service (name = Pod name).
 *
 * Both operations are idempotent and never touch a Service that exkes does not own for the
 * given runtime: an unexpected existing Service is an error on ensure and is left alone on delete.
 */
internal class RuntimeServiceManager(private val store: RuntimeServiceStore) {
    private val logger = LoggerFactory.getLogger(RuntimeServiceManager::class.java)

    /**
     * Ensures the Service for [runtime] exists and matches the expected spec. An existing Service
     * with identical content is reused; a 409 race is resolved by reading the winner and verifying it.
     */
    fun ensure(runtime: Runtime) {
        val name = Runtime.podNameFor(runtime.id)
        val desired = buildRuntimeService(runtime, name, store.namespace)
        val current = store.read(name) ?: run {
            if (store.create(desired)) {
                logger.info("created Service {} for runtime {}", name, runtime.id)
                return
            }
            // Lost a creation race: verify whoever won instead of adopting blindly.
            store.read(name) ?: throw KubernetesRuntimeException("Service $name changed during creation; retry")
        }
        if (!isServiceOwnedBy(current, runtime.id) || !matchesRuntimeService(current, desired)) {
            throw KubernetesRuntimeException("Service $name exists but does not match runtime ${runtime.id}")
        }
    }

    /**
     * Deletes the Service for [runtime]. Returns true when nothing of this runtime's remains
     * (deleted, already absent, or the name belongs to a foreign object that is left untouched).
     */
    fun delete(runtime: Runtime): Boolean {
        val name = Runtime.podNameFor(runtime.id)
        val current = store.read(name) ?: return true
        if (!isServiceOwnedBy(current, runtime.id)) {
            logger.warn("Service {} is not owned by runtime {}; leaving it untouched", name, runtime.id)
            return true
        }
        val uid = current.metadata?.uid?.takeIf { it.isNotBlank() }
            ?: throw KubernetesRuntimeException("Service $name has no UID; deletion blocked")
        store.delete(name, uid)
        logger.info("deleted Service {} of runtime {}", name, runtime.id)
        return true
    }
}

/** Production [RuntimeServiceStore] backed by client-java, scoped to the Runtime resource namespace. */
internal class CoreV1RuntimeServiceStore(
    private val api: CoreV1Api,
    override val namespace: String,
) : RuntimeServiceStore {
    override fun read(name: String): V1Service? = try {
        api.readNamespacedService(name, namespace).execute()
    } catch (e: ApiException) {
        if (e.code == 404) null else throw KubernetesRuntimeException("failed to read Service $name: ${e.message}", e)
    }

    override fun create(service: V1Service): Boolean = try {
        api.createNamespacedService(namespace, service).execute()
        true
    } catch (e: ApiException) {
        if (e.code == 409) false
        else throw KubernetesRuntimeException("failed to create Service ${service.metadata?.name}: ${e.message}", e)
    }

    override fun delete(name: String, uid: String) {
        try {
            api.deleteNamespacedService(name, namespace)
                .body(V1DeleteOptions().preconditions(V1Preconditions().uid(uid)))
                .execute()
        } catch (e: ApiException) {
            // 404: already gone. 409/422: UID precondition mismatch, never treated as success.
            if (e.code != 404) throw KubernetesRuntimeException("failed to delete Service $name: ${e.message}", e)
        }
    }
}
