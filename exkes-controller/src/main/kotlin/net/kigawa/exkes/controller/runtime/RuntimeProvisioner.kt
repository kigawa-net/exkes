package net.kigawa.exkes.controller.runtime

import io.kubernetes.client.openapi.models.V1Pod
import net.kigawa.exkes.common.domain.Runtime
import net.kigawa.exkes.common.domain.RuntimeStatus

/**
 * Interface for creating/managing Runtime Pods.
 * Implementations can target Kubernetes, Docker, Firecracker, etc.
 * (plan chapter 7.2 / issue #1 "Runtime Provider").
 */
interface RuntimeProvisioner {

    /** Creates the Pod for a Runtime in CREATING state. Returns the Pod name. */
    fun createRuntime(runtime: Runtime): String

    /** Starts an existing Pod for a Runtime in STARTING state. */
    fun startRuntime(runtime: Runtime)

    /** Stops (deletes) the Pod for a Runtime in STOPPING state. Returns true when confirmed gone. */
    fun stopRuntime(runtime: Runtime): Boolean

    /** Deletes the Pod for a Runtime in DELETING state. Returns true when confirmed gone. */
    fun deleteRuntime(runtime: Runtime): Boolean

    /** Reads the current Pod status and maps it to a RuntimeStatus. */
    fun getRuntimeStatus(runtime: Runtime): RuntimeStatus

    /** Streams logs from the Runtime's Pod. */
    fun streamRuntimeLogs(runtime: Runtime): String

    /** The Pod name derived from the runtime ID (deterministic). */
    fun resolvePodName(runtime: Runtime): String = Runtime.podNameFor(runtime.id)
}