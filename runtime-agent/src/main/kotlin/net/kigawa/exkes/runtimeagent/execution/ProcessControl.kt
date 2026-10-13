package net.kigawa.exkes.runtimeagent.execution

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Environment variable carrying a random per-execution token, used to find leftover processes. */
const val EXECUTION_TOKEN_ENV = "EXKES_EXECUTION_TOKEN"

/**
 * Process inspection and termination for executions. Injected so the manager can be tested
 * with a control that cannot confirm a stop.
 */
interface ProcessControl {
    /** Identity recorded right after spawn. */
    fun identify(process: Process): ProcessIdentity

    /**
     * Live processes belonging to an execution: the root (only while [identity] still matches
     * PID, start time and command line), its descendants, and every process whose environment
     * carries [token]. Zombies are not returned.
     */
    fun leftovers(identity: ProcessIdentity?, token: String): Set<Long>

    fun kill(pids: Set<Long>)

    /** True when none of [pids] is alive (a zombie counts as gone) before [timeoutMillis] elapses. */
    fun awaitGone(pids: Set<Long>, timeoutMillis: Long): Boolean
}

/** Linux implementation. Liveness and the environment scan use /proc. Without /proc only the identity check works. */
object SystemProcessControl : ProcessControl {
    private val proc: Path = Path.of("/proc")
    private const val START_TOLERANCE_MS = 1_000L
    private const val POLL_MS = 50L

    override fun identify(process: Process): ProcessIdentity {
        val handle = process.toHandle()
        val info = handle.info()
        return ProcessIdentity(
            pid = handle.pid(),
            startedAtEpochMillis = info.startInstant().map { it.toEpochMilli() }.orElse(null),
            commandLine = info.commandLine().orElse(null),
        )
    }

    override fun leftovers(identity: ProcessIdentity?, token: String): Set<Long> {
        val found = mutableSetOf<Long>()
        if (identity != null && rootMatches(identity)) {
            found += identity.pid
            ProcessHandle.of(identity.pid).ifPresent { root ->
                root.descendants().forEach { found += it.pid() }
            }
        }
        found += tokenHolders(token)
        return found.filterTo(mutableSetOf()) { isAlive(it) }
    }

    override fun kill(pids: Set<Long>) {
        for (pid in pids) {
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    override fun awaitGone(pids: Set<Long>, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (pids.any { isAlive(it) }) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    /** False for a missing process and for a zombie (exited, not yet reaped). */
    fun isAlive(pid: Long): Boolean {
        if (!Files.isDirectory(proc)) {
            return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }
        val text = try {
            Files.readString(proc.resolve("$pid").resolve("stat"))
        } catch (e: IOException) {
            return false
        }
        // Format: "pid (comm) state ..." where comm may contain spaces or parentheses.
        val state = text.substring(text.lastIndexOf(')') + 2).firstOrNull() ?: return false
        return state != 'Z' && state != 'X'
    }

    private fun rootMatches(identity: ProcessIdentity): Boolean {
        val expectedStart = identity.startedAtEpochMillis ?: return false
        val expectedCommand = identity.commandLine ?: return false
        val handle = ProcessHandle.of(identity.pid).orElse(null) ?: return false
        val info = handle.info()
        val start = info.startInstant().map { it.toEpochMilli() }.orElse(null) ?: return false
        val command = info.commandLine().orElse(null) ?: return false
        return abs(start - expectedStart) <= START_TOLERANCE_MS && command == expectedCommand
    }

    private fun tokenHolders(token: String): Set<Long> {
        val expected = "$EXECUTION_TOKEN_ENV=$token"
        val self = ProcessHandle.current().pid()
        val pids = mutableSetOf<Long>()
        val dirs = try {
            Files.list(proc).use { it.toList() }
        } catch (e: IOException) {
            return pids
        }
        for (dir in dirs) {
            val pid = dir.fileName.toString().toLongOrNull() ?: continue
            if (pid == self) continue
            val env = try {
                Files.readAllBytes(dir.resolve("environ"))
            } catch (e: IOException) {
                continue
            }
            if (String(env, Charsets.ISO_8859_1).split('\u0000').contains(expected)) pids += pid
        }
        return pids
    }
}
