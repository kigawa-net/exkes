package net.kigawa.exkes.runtimeagent.process

import net.kigawa.exkes.runtimeagent.domain.Execution
import net.kigawa.exkes.runtimeagent.repository.ExecutionRepository

class SimpleProcessManager(
    val execution: Execution,
) {

    fun reportSuccess() {
        ExecutionRepository.setStatus(execution.id, "SUCCEEDED", exitCode = 0)
    }

    fun reportFailed(error: String?) {
        ExecutionRepository.setStatus(execution.id, "FAILED", errorMessage = error)
    }
}
