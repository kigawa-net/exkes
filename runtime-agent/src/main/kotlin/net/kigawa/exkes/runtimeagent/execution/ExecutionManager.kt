package net.kigawa.exkes.runtimeagent.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Result of [ExecutionManager.start]. [created] is false when an identical request was replayed. */
data class StartResult(val snapshot: ExecutionSnapshot, val created: Boolean)

/**
 * Owns every execution in this Runtime: spawns the processes, drives the state machine
 * QUEUED -> STARTING -> RUNNING -> terminal, and enforces timeout / cancel.
 *
 * Durability rules (fail closed):
 * - The QUEUED record is written before the execution becomes visible, and the POST fails
 *   if it cannot be written. Nothing is spawned in that case.
 * - STARTING is written before spawn. If it cannot be written, nothing is spawned.
 * - RUNNING (with the process identity) is written right after spawn. If it cannot be
 *   written, the child is stopped and the execution is never reported as successful.
 * - When a final state cannot be written, the in-memory state becomes FAILED rather than
 *   the success it would have been.
 * - A process tree that cannot be confirmed stopped is LOST, not terminal. While any
 *   execution is LOST, new executions are refused.
 */
class ExecutionManager(
    private val scope: CoroutineScope,
    private val store: ExecutionStore,
    private val defaultWorkingDir: String? = null,
    private val maxLogBytes: Long = 16L * 1024 * 1024,
    private val clock: () -> Instant = { Instant.now() },
    private val control: ProcessControl = SystemProcessControl,
) {
    private val logger = LoggerFactory.getLogger(ExecutionManager::class.java)

    private class Entry(
        var snapshot: ExecutionSnapshot,
        val log: ExecutionLog,
        val token: String,
        val requestDigest: String,
        var identity: ProcessIdentity? = null,
    ) {
        @Volatile var process: Process? = null
        /** Why the process is being killed, decided before the kill so the exit path reports it. */
        @Volatile var killReason: ExecutionStatus? = null
        @Volatile var cancelRequested = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val startLock = Any()

    /**
     * Loads records written by a previous agent process. Every non-terminal execution
     * is reconciled: leftover processes are stopped and confirmed, then the execution
     * becomes FAILED, or LOST when the stop cannot be confirmed.
     */
    fun recover() {
        for (rec in store.loadAll()) {
            val entry = Entry(rec.snapshot, ExecutionLog(maxLogBytes, clock), rec.token, rec.requestDigest, rec.process)
            if (rec.snapshot.status.isTerminal) entry.log.close()
            entries[rec.snapshot.id] = entry
        }
        for (e in entries.values.toList()) {
            if (!synchronized(e) { e.snapshot.status.isTerminal }) reconcile(e)
        }
    }

    fun start(request: StartExecutionRequest): StartResult {
        if (request.command.isEmpty() || request.command[0].isBlank()) {
            throw InvalidExecutionRequestException("command must not be empty")
        }
        if (request.timeoutSeconds != null && request.timeoutSeconds <= 0) {
            throw InvalidExecutionRequestException("timeoutSeconds must be positive")
        }
        val id = request.id ?: UUID.randomUUID().toString()
        if (!ID_PATTERN.matches(id)) throw InvalidExecutionRequestException("invalid execution id")
        val workingDir = request.workingDir ?: defaultWorkingDir
        val digest = digestOf(request.command, workingDir, request.env, request.timeoutSeconds)
        refuseWhileUnresolved()
        synchronized(startLock) {
            entries[id]?.let { existing ->
                // Same id and same request after a lost response: report the current state, do not run again.
                if (existing.requestDigest != digest) {
                    throw ExecutionConflictException("execution already exists with a different request: $id")
                }
                return StartResult(get(id), created = false)
            }
            val snapshot = ExecutionSnapshot(
                id = id,
                command = request.command,
                workingDir = workingDir,
                status = ExecutionStatus.QUEUED,
                timeoutSeconds = request.timeoutSeconds,
                createdAt = clock().toString(),
            )
            val entry = Entry(snapshot, ExecutionLog(maxLogBytes, clock), UUID.randomUUID().toString(), digest)
            // Durable before the execution is visible. A failure throws here, so nothing is spawned.
            store.save(recordOf(entry))
            entries[id] = entry
            scope.launch(Dispatchers.IO) { run(entry, request.env) }
            return StartResult(snapshot, created = true)
        }
    }

    fun get(id: String): ExecutionSnapshot = entry(id).let { synchronized(it) { it.snapshot.copy(lastLogSeq = it.log.lastSeq()) } }

    fun list(): List<ExecutionSnapshot> =
        entries.values.map { e -> synchronized(e) { e.snapshot.copy(lastLogSeq = e.log.lastSeq()) } }
            .sortedBy { it.createdAt }

    fun log(id: String): ExecutionLog = entry(id).log

    /** Request cancellation. No-op for executions that already finished. */
    fun cancel(id: String): ExecutionSnapshot {
        val e = entry(id)
        if (synchronized(e) { e.snapshot.status == ExecutionStatus.LOST }) reconcile(e)
        synchronized(e) {
            if (e.snapshot.status.isTerminal) return get(id)
            e.cancelRequested = true
            if (e.killReason == null) e.killReason = ExecutionStatus.CANCELLED
            e.process?.let { killTree(it) }
        }
        return get(id)
    }

    /**
     * Agent is going away (SIGTERM / Pod deletion): stop every process and record what was
     * in flight as FAILED, or LOST when the stop cannot be confirmed in time.
     */
    fun shutdown() {
        for (e in entries.values.toList()) {
            val (from, identity, process) = synchronized(e) {
                if (e.snapshot.status.isTerminal) null else {
                    e.killReason = ExecutionStatus.FAILED
                    Triple(e.snapshot.status, e.identity, e.process)
                }
            } ?: continue
            val stopped = stopAll(identity, e.token, process, SHUTDOWN_TIMEOUT_MS)
            synchronized(e) {
                if (!e.snapshot.status.isTerminal) {
                    settle(
                        e,
                        if (stopped) ExecutionStatus.FAILED else ExecutionStatus.LOST,
                        errorMessage = "runtime-agent shut down while the execution was $from",
                    )
                }
            }
        }
    }

    private fun entry(id: String): Entry = entries[id] ?: throw ExecutionNotFoundException(id)

    private fun refuseWhileUnresolved() {
        val lost = entries.values.filter { synchronized(it) { it.snapshot.status == ExecutionStatus.LOST } }
        if (lost.isEmpty()) return
        lost.forEach { reconcile(it) }
        if (lost.any { synchronized(it) { it.snapshot.status == ExecutionStatus.LOST } }) {
            throw ExecutionConflictException(
                "previous execution(s) could not be confirmed stopped; new executions are refused until they are",
            )
        }
    }

    /** Confirms that no process of a non-terminal execution survives, then moves it to FAILED, or keeps it LOST. */
    private fun reconcile(e: Entry) {
        val (from, identity) = synchronized(e) { e.snapshot.status to e.identity }
        if (from.isTerminal) return
        val stopped = stopAll(identity, e.token, null, STOP_TIMEOUT_MS)
        synchronized(e) {
            if (e.snapshot.status.isTerminal) return
            if (stopped) {
                settle(e, ExecutionStatus.FAILED, errorMessage = "runtime-agent restarted while the execution was $from")
            } else {
                settle(e, ExecutionStatus.LOST, errorMessage = "could not confirm that processes of the execution were stopped (was $from)")
            }
        }
    }

    private fun recordOf(e: Entry) = ExecutionRecord(e.snapshot, e.token, e.requestDigest, e.identity)

    /** Must hold the entry monitor. Writes [to] to memory, then persists it. Returns false when the write failed. */
    private fun setStatus(
        e: Entry,
        to: ExecutionStatus,
        exitCode: Int? = null,
        errorMessage: String? = null,
        identity: ProcessIdentity? = null,
    ): Boolean {
        applyStatus(e, to, exitCode, errorMessage, identity)
        return try {
            store.save(recordOf(e))
            true
        } catch (t: Exception) {
            logger.error("failed to persist execution {} in {}", e.snapshot.id, to, t)
            false
        }
    }

    /**
     * Must hold the entry monitor. Moves to [to] if the transition is legal. If the record cannot
     * be written, falls back to FAILED (or LOST), so an unconfirmed stop or a success is never final.
     */
    private fun settle(e: Entry, to: ExecutionStatus, exitCode: Int? = null, errorMessage: String? = null) {
        val from = e.snapshot.status
        if (!ExecutionStatus.canTransition(from, to)) {
            logger.warn("ignored transition {} -> {} for execution {}", from, to, e.snapshot.id)
            return
        }
        if (setStatus(e, to, exitCode, errorMessage)) return
        if (to == ExecutionStatus.LOST) {
            applyStatus(e, ExecutionStatus.LOST, exitCode, errorMessage, null)
        } else {
            applyStatus(e, ExecutionStatus.FAILED, exitCode, "could not persist $to state", null)
        }
        try {
            store.save(recordOf(e))
        } catch (t: Exception) {
            logger.error("failed to persist fallback state of execution {}", e.snapshot.id, t)
        }
    }

    private fun applyStatus(
        e: Entry,
        to: ExecutionStatus,
        exitCode: Int?,
        errorMessage: String?,
        identity: ProcessIdentity?,
    ) {
        val now = clock().toString()
        e.snapshot = e.snapshot.copy(
            status = to,
            exitCode = exitCode ?: e.snapshot.exitCode,
            errorMessage = errorMessage ?: e.snapshot.errorMessage,
            startedAt = if (to == ExecutionStatus.RUNNING) now else e.snapshot.startedAt,
            finishedAt = if (to.isTerminal) now else null,
        )
        if (identity != null) e.identity = identity
        if (to.isTerminal) e.log.close()
    }

    private suspend fun run(e: Entry, env: Map<String, String>) {
        val id = e.snapshot.id
        try {
            val process = synchronized(e) {
                if (e.snapshot.status.isTerminal) return
                if (e.cancelRequested) {
                    settle(e, ExecutionStatus.CANCELLED)
                    return
                }
                if (!setStatus(e, ExecutionStatus.STARTING)) {
                    settle(e, ExecutionStatus.FAILED, errorMessage = "could not persist STARTING state; process not started")
                    return
                }
                val pb = ProcessBuilder(e.snapshot.command)
                e.snapshot.workingDir?.let { pb.directory(File(it)) }
                pb.environment().putAll(env)
                pb.environment()[EXECUTION_TOKEN_ENV] = e.token
                val p = try {
                    pb.start()
                } catch (t: Exception) {
                    settle(e, ExecutionStatus.FAILED, errorMessage = "failed to start process: ${t.message}")
                    return
                }
                e.process = p
                val identity = control.identify(p)
                if (!setStatus(e, ExecutionStatus.RUNNING, identity = identity)) {
                    // The child runs, but its start cannot be recorded. Stop it and never report success.
                    val stopped = stopAll(identity, e.token, p, STOP_TIMEOUT_MS)
                    settle(
                        e,
                        if (stopped) ExecutionStatus.FAILED else ExecutionStatus.LOST,
                        errorMessage = "could not persist RUNNING state; process was stopped",
                    )
                    return
                }
                // A cancel that arrived while we were starting.
                if (e.cancelRequested) killTree(p)
                p
            }
            val readers = listOf(
                scope.launch(Dispatchers.IO) { pump(process.inputStream, LogStream.STDOUT, e.log) },
                scope.launch(Dispatchers.IO) { pump(process.errorStream, LogStream.STDERR, e.log) },
            )
            val timeout = e.snapshot.timeoutSeconds
            val exited = runInterruptible {
                if (timeout == null) { process.waitFor(); true } else process.waitFor(timeout, TimeUnit.SECONDS)
            }
            if (!exited) {
                synchronized(e) { if (e.killReason == null) e.killReason = ExecutionStatus.TIMED_OUT }
            }
            // Stop the root and every leftover (including background children that outlive the root) before the outcome is decided.
            val stopped = stopAll(e.identity, e.token, process, STOP_TIMEOUT_MS)
            withTimeoutOrNull(READER_DRAIN_MS) { readers.joinAll() }
            val code = process.takeIf { !it.isAlive }?.exitValue()
            synchronized(e) {
                if (e.snapshot.status.isTerminal) return
                when {
                    e.killReason == ExecutionStatus.FAILED -> Unit // shutdown() owns the final state
                    !stopped || code == null -> settle(e, ExecutionStatus.LOST, code, "could not confirm that the process tree stopped")
                    e.killReason == ExecutionStatus.CANCELLED -> settle(e, ExecutionStatus.CANCELLED, code)
                    e.killReason == ExecutionStatus.TIMED_OUT ->
                        settle(e, ExecutionStatus.TIMED_OUT, code, "timed out after ${timeout}s")
                    code == 0 -> settle(e, ExecutionStatus.SUCCEEDED, code)
                    else -> settle(e, ExecutionStatus.FAILED, code, "process exited with code $code")
                }
            }
        } catch (t: Throwable) {
            logger.error("execution {} crashed in agent", id, t)
            val (identity, process) = synchronized(e) { e.identity to e.process }
            val stopped = stopAll(identity, e.token, process, STOP_TIMEOUT_MS)
            synchronized(e) {
                if (!e.snapshot.status.isTerminal) {
                    settle(
                        e,
                        if (stopped) ExecutionStatus.FAILED else ExecutionStatus.LOST,
                        errorMessage = "agent error: ${t.message}",
                    )
                }
            }
            if (t is CancellationException) throw t
        }
    }

    /**
     * Kills the live root (if any), every verified or token-tagged leftover, and waits until they are gone.
     * Returns true only when all of them are confirmed stopped. Waits without holding the entry monitor.
     */
    private fun stopAll(identity: ProcessIdentity?, token: String, live: Process?, timeoutMillis: Long): Boolean {
        if (live != null && live.isAlive) killTree(live)
        val pids = control.leftovers(identity, token)
        control.kill(pids)
        val liveStopped = live?.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) ?: true
        return liveStopped && control.awaitGone(pids, timeoutMillis)
    }

    private fun pump(stream: InputStream, kind: LogStream, log: ExecutionLog) {
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val buf = CharArray(8192)
        try {
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                log.append(kind, String(buf, 0, n))
            }
        } catch (_: java.io.IOException) {
            // stream closed because the process was killed
        }
    }

    private fun killTree(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    @Serializable
    private data class DigestInput(
        val command: List<String>,
        val workingDir: String?,
        val env: List<Pair<String, String>>,
        val timeoutSeconds: Long?,
    )

    private fun digestOf(command: List<String>, workingDir: String?, env: Map<String, String>, timeoutSeconds: Long?): String {
        val canonical = Json.encodeToString(
            DigestInput.serializer(),
            DigestInput(command, workingDir, env.toList().sortedBy { it.first }, timeoutSeconds),
        )
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
        private const val STOP_TIMEOUT_MS = 10_000L
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
        private const val READER_DRAIN_MS = 5_000L
    }
}
