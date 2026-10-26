package net.kigawa.exkes.runtimeagent.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private val logger = LoggerFactory.getLogger("TerminalManager")

/**
 * Settings for terminal sessions (`agent.terminal.*`, all optional).
 *
 * [watchIntervalMillis] is how often the idle/TTL watchdog looks; it is only lowered by tests.
 */
data class TerminalConfig(
    val maxSessions: Int = 4,
    val idleTimeoutMillis: Long = 15 * 60 * 1000L,
    val maxTtlMillis: Long = 8 * 60 * 60 * 1000L,
    val defaultShell: String = "/bin/sh",
    val defaultCwd: String? = null,
    val watchIntervalMillis: Long = 1_000L,
) {
    init {
        require(maxSessions >= 1) { "maxSessions must be at least 1" }
        require(idleTimeoutMillis > 0 && maxTtlMillis > 0) { "timeouts must be positive" }
        require(watchIntervalMillis > 0) { "watchIntervalMillis must be positive" }
    }
}

/** A validated request for a new shell. */
data class TerminalRequest(
    val cols: Int,
    val rows: Int,
    val cwd: String?,
    val shell: String?,
) {
    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val MAX_DIMENSION = 1000

        /** Checks a terminal size. Used for the query parameters and for `resize` messages. */
        fun checkSize(cols: Int, rows: Int) {
            if (cols !in 1..MAX_DIMENSION || rows !in 1..MAX_DIMENSION) {
                throw InvalidTerminalRequestException("cols and rows must be between 1 and $MAX_DIMENSION")
            }
        }

        /**
         * Builds a request from query parameters. `cwd` must be an existing absolute directory and
         * `shell` an absolute executable file. Anything else is rejected with a message that does not
         * echo the value back.
         */
        fun fromQuery(query: (String) -> String?): TerminalRequest {
            val cols = parseInt(query("cols"), "cols") ?: DEFAULT_COLS
            val rows = parseInt(query("rows"), "rows") ?: DEFAULT_ROWS
            checkSize(cols, rows)
            val cwd = query("cwd")?.also {
                val path = absolutePath(it, "cwd")
                if (!Files.isDirectory(path)) throw InvalidTerminalRequestException("cwd is not a directory")
            }
            val shell = query("shell")?.also {
                val path = absolutePath(it, "shell")
                if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                    throw InvalidTerminalRequestException("shell is not an executable file")
                }
            }
            return TerminalRequest(cols, rows, cwd, shell)
        }

        private fun parseInt(raw: String?, name: String): Int? {
            if (raw == null) return null
            return raw.toIntOrNull() ?: throw InvalidTerminalRequestException("$name must be an integer")
        }

        private fun absolutePath(raw: String, name: String): Path {
            if (raw.isEmpty() || raw.contains('\u0000')) throw InvalidTerminalRequestException("$name is invalid")
            val path = try {
                Path.of(raw)
            } catch (e: java.nio.file.InvalidPathException) {
                throw InvalidTerminalRequestException("$name is invalid")
            }
            if (!path.isAbsolute) throw InvalidTerminalRequestException("$name must be an absolute path")
            return path
        }
    }
}

class InvalidTerminalRequestException(message: String) : RuntimeException(message)

class TerminalLimitException(maxSessions: Int) :
    RuntimeException("too many terminal sessions (limit $maxSessions)")

/**
 * Owns the terminal sessions of this agent. Concurrency is capped by a semaphore that is
 * acquired before a shell is spawned and released when its session finishes.
 */
class TerminalManager(
    private val scope: CoroutineScope,
    private val config: TerminalConfig = TerminalConfig(),
) {
    private val permits = Semaphore(config.maxSessions)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    val activeSessions: Int get() = sessions.size

    /**
     * Starts a shell. Throws [TerminalLimitException] when the cap is reached.
     * Spawning forks a process, so it runs on Dispatchers.IO.
     */
    suspend fun open(request: TerminalRequest): TerminalSession {
        if (!permits.tryAcquire()) throw TerminalLimitException(config.maxSessions)
        try {
            val id = UUID.randomUUID().toString()
            val token = UUID.randomUUID().toString()
            val spec = SpawnSpec(
                shell = request.shell ?: config.defaultShell,
                cwd = request.cwd ?: config.defaultCwd,
                cols = request.cols,
                rows = request.rows,
                token = token,
            )
            val child = withContext(Dispatchers.IO) { TerminalSession.spawn(spec) }
            val session = TerminalSession(
                id = id,
                child = child,
                token = token,
                idleTimeoutMillis = config.idleTimeoutMillis,
                maxTtlMillis = config.maxTtlMillis,
                watchIntervalMillis = config.watchIntervalMillis,
                onFinished = { finished ->
                    sessions.remove(finished.id)
                    permits.release()
                },
            )
            sessions[id] = session
            session.start(scope)
            // Logged without any input or output: keystrokes may contain secrets.
            logger.info("terminal session {} started shell={}", id, spec.shell)
            return session
        } catch (e: Throwable) {
            permits.release()
            throw e
        }
    }

    /** Kills every session. Called from the ApplicationStopping hook, before the scope is cancelled. */
    fun shutdown() {
        for (session in sessions.values.toList()) session.detach()
    }
}
