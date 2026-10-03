package net.kigawa.exkes.runtimeagent

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

/**
 * runtime-agent skeleton.
 *
 * OUT OF SCOPE for issue #2: no Runtime Pods exist yet, so nothing runs this
 * module. Runs inside Runtime Pods on the sandbox plane once they do. It must
 * never depend on exkes-common (plan chapter 3): the sandbox side only speaks
 * JSON over HTTP/WebSocket. Process/PTY/Execution management lands here in a
 * follow-up milestone after #1.
 */
fun Application.module() {
    logger.info("runtime-agent is a milestone-3 skeleton; no process management yet")
    routing {
        get("/health") {
            call.respondText("ok")
        }
    }
}
