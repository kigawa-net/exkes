package net.kigawa.exkes.runtimeagent.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Owns every execution in this Runtime: spawns the processes, drives the
 * state machine QUEUED -> STARTING -> RUNNING -> terminal, and enforces
 * timeout / cancel. All state changes for one execution go through its
 * [Entry] monitor, so cancel / timeout / exit cannot overwrite each other.
 */
class ExecutionManager(
    private val scope: CoroutineScope,
    private val store: ExecutionStore,
    private val defaultWorkingDir: String? = null,
    private val maxLogBytes: Long = 16L * 1024 * 1024,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val logger = LoggerFactory.getLogger(ExecutionManager::class.java)

    private class Entry(var snapshot: ExecutionSnapshot, val log: ExecutionLog) {
        @Volatile var process: Process? = null
        @Volatile var job: Job? = null
        /** Why the process is being killed, decided before the kill so the exit path reports it. */
        @Volatile var killReason: ExecutionStatus? = null
        @Volatile var cancelRequested = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Executions left non-terminal by a previous agent process can never finish.
     * Load them as FAILED (issue #8: in-flight Executions of an abnormally
     * terminated Runtime become FAILED) and keep terminal ones queryable.
     */
    fun recover() {
        for (snap in store.loadAll()) {
            val recovered = if (snap.status.isTerminal) snap else snap.copy(
                status = ExecutionStatus.FAILED,
                errorMessage = "runtime-agent restarted while the execution was ${snap.status}",
                finishedAt = clock().toString(),
            ).also { store.save(it) }
            entries.putIfAbsent(snap.id, Entry(recovered, ExecutionLog(maxLogBytes, clock).also { it.close() }))
        }
    }

    fun start(request: StartExecutionRequest): ExecutionSnapshot {
        if (request.command.isEmpty() || request.command[0].isBlank()) {
            throw InvalidExecutionRequestException("command must not be empty")
        }
        if (request.timeoutSeconds != null && request.timeoutSeconds <= 0) {
            throw InvalidExecutionRequestException("timeoutSeconds must be positive")
        }
        val id = request.id ?: UUID.randomUUID().toString()
        if (!ID_PATTERN.matches(id)) throw InvalidExecutionRequestException("invalid execution id")
        val workingDir = request.workingDir ?: defaultWorkingDir
        val snapshot = ExecutionSnapshot(
            id = id,
            command = request.command,
            workingDir = workingDir,
            status = ExecutionStatus.QUEUED,
            timeoutSeconds = request.timeoutSeconds,
            createdAt = clock().toString(),
        )
        val entry = Entry(snapshot, ExecutionLog(maxLogBytes, clock))
        if (entries.putIfAbsent(id, entry) != null) throw ExecutionConflictException("execution already exists: $id")
        store.save(snapshot)
        entry.job = scope.launch(Dispatchers.IO) { run(entry, request.env) }
        return snapshot
    }

    fun get(id: String): ExecutionSnapshot = entry(id).let { synchronized(it) { it.snapshot.copy(lastLogSeq = it.log.lastSeq()) } }

    fun list(): List<ExecutionSnapshot> =
        entries.values.map { e -> synchronized(e) { e.snapshot.copy(lastLogSeq = e.log.lastSeq()) } }
            .sortedBy { it.createdAt }

    fun log(id: String): ExecutionLog = entry(id).log

    /** Request cancellation. No-op for executions that already finished. */
    fun cancel(id: String): ExecutionSnapshot {
        val e = entry(id)
        synchronized(e) {
            if (e.snapshot.status.isTerminal) return get(id)
            e.cancelRequested = true
            if (e.killReason == null) e.killReason = ExecutionStatus.CANCELLED
            e.process?.let { killTree(it) }
        }
        return get(id)
    }

    /**
     * Agent is going away (SIGTERM / Pod deletion): kill every process and mark
     * what was in flight as FAILED so the state is never left as RUNNING.
     */
    fun shutdown() {
        for (e in entries.values) {
            synchronized(e) {
                if (e.snapshot.status.isTerminal) return@synchronized
                e.killReason = ExecutionStatus.FAILED
                e.process?.let { killTree(it) }
                transition(
                    e, ExecutionStatus.FAILED,
                    errorMessage = "runtime-agent shut down while the execution was ${e.snapshot.status}",
                )
                e.log.close()
            }
        }
    }

    private fun entry(id: String): Entry = entries[id] ?: throw ExecutionNotFoundException(id)

    /** Must hold the entry monitor. Returns false when the transition is illegal (already terminal etc.). */
    private fun transition(
        e: Entry,
        to: ExecutionStatus,
        exitCode: Int? = null,
        errorMessage: String? = null,
    ): Boolean = synchronized(e) {
        val from = e.snapshot.status
        if (!ExecutionStatus.canTransition(from, to)) return false
        val now = clock().toString()
        e.snapshot = e.snapshot.copy(
            status = to,
            exitCode = exitCode ?: e.snapshot.exitCode,
            errorMessage = errorMessage ?: e.snapshot.errorMessage,
            startedAt = if (to == ExecutionStatus.RUNNING) now else e.snapshot.startedAt,
            finishedAt = if (to.isTerminal) now else null,
        )
        try {
            store.save(e.snapshot)
        } catch (t: Throwable) {
            logger.warn("failed to persist execution {}", e.snapshot.id, t)
        }
        true
    }

    private suspend fun run(e: Entry, env: Map<String, String>) {
        val id = e.snapshot.id
        try {
            val process = synchronized(e) {
                if (e.cancelRequested) {
                    transition(e, ExecutionStatus.CANCELLED)
                    return
                }
                transition(e, ExecutionStatus.STARTING)
                val pb = ProcessBuilder(e.snapshot.command)
                e.snapshot.workingDir?.let { pb.directory(File(it)) }
                pb.environment().putAll(env)
                val p = try {
                    pb.start()
                } catch (t: Exception) {
                    transition(e, ExecutionStatus.FAILED, errorMessage = "failed to start process: ${t.message}")
                    return
                }
                e.process = p
                transition(e, ExecutionStatus.RUNNING)
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
                killTree(process)
                runInterruptible { process.waitFor() }
            }
            withContext(Dispatchers.IO) { readers.forEach { it.join() } }
            val code = process.exitValue()
            synchronized(e) {
                when (e.killReason) {
                    ExecutionStatus.CANCELLED -> transition(e, ExecutionStatus.CANCELLED, code)
                    ExecutionStatus.TIMED_OUT ->
                        transition(e, ExecutionStatus.TIMED_OUT, code, "timed out after ${timeout}s")
                    ExecutionStatus.FAILED -> Unit // shutdown() already recorded it
                    else ->
                        if (code == 0) transition(e, ExecutionStatus.SUCCEEDED, code)
                        else transition(e, ExecutionStatus.FAILED, code, "process exited with code $code")
                }
            }
        } catch (t: Throwable) {
            logger.error("execution {} crashed in agent", id, t)
            synchronized(e) {
                e.process?.let { killTree(it) }
                transition(e, ExecutionStatus.FAILED, errorMessage = "agent error: ${t.message}")
            }
            if (t is kotlinx.coroutines.CancellationException) throw t
        } finally {
            e.log.close()
        }
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

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
    }
}
