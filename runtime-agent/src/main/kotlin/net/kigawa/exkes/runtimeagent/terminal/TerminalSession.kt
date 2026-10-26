package net.kigawa.exkes.runtimeagent.terminal

import com.pty4j.PtyProcess
import com.pty4j.WinSize
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kigawa.exkes.runtimeagent.execution.ProcessIdentity
import net.kigawa.exkes.runtimeagent.execution.SystemProcessControl
import net.kigawa.exkes.runtimeagent.execution.EXECUTION_TOKEN_ENV
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("TerminalSession")

/**
 * Process-group signalling. The PTY child calls setsid(), so its pid is also its process
 * group id, and `kill(-pid, SIGKILL)` reaches the shell and every job it started.
 */
internal object ProcessGroups {
    private const val SIGKILL = 9

    private interface LibC : Library {
        fun kill(pid: Int, signal: Int): Int
    }

    private val libc: LibC? = try {
        Native.load("c", LibC::class.java)
    } catch (e: Throwable) {
        logger.warn("libc unavailable; only tracked descendants are killed: {}", e.javaClass.name)
        null
    }

    /** Sends SIGKILL to the group. Must only be called while the group leader is not yet reaped. */
    fun killGroup(pgid: Long) {
        libc?.kill(-pgid.toInt(), SIGKILL)
    }
}

/** The PTY-backed shell process and its streams. Blocking calls; use from Dispatchers.IO. */
internal class PtyChild(private val process: PtyProcess) {
    val pid: Long = process.pid()

    /**
     * pid only. pty4j does not support `Process.toHandle()`, so no start time or command line
     * is recorded. That is safe here: the child stays unreaped until [waitFor] returns, so its
     * pid cannot be reused, and its shell inherits the session token for the leftover scan.
     */
    val identity: ProcessIdentity = ProcessIdentity(pid = pid)
    private val output: InputStream = process.inputStream
    private val input: OutputStream = process.outputStream

    fun read(buffer: ByteArray): Int = output.read(buffer)

    fun write(bytes: ByteArray) {
        input.write(bytes)
        input.flush()
    }

    fun resize(cols: Int, rows: Int) {
        process.setWinSize(WinSize(cols, rows))
    }

    fun waitFor(): Int = process.waitFor()
}

/** Everything a session needs to start its shell. Built by [TerminalManager]. */
internal class SpawnSpec(
    val shell: String,
    val cwd: String?,
    val cols: Int,
    val rows: Int,
    val token: String,
)

/**
 * One interactive shell on a PTY, plus its output pump, idle/TTL watchdog and cleanup.
 *
 * Lifecycle: the shell exits or is killed -> the process group and tagged leftovers are
 * killed -> pending output is drained -> `exit` is delivered -> [onFinished] releases the slot.
 * Killing never waits for a client, so a closed socket cannot keep a shell alive.
 */
class TerminalSession internal constructor(
    val id: String,
    private val child: PtyChild,
    private val token: String,
    private val idleTimeoutMillis: Long,
    private val maxTtlMillis: Long,
    private val watchIntervalMillis: Long,
    private val onFinished: (TerminalSession) -> Unit,
) {
    /** pid of the shell (also its process group id). */
    val pid: Long get() = child.pid

    /** Output and terminal messages for the WebSocket pump. Bounded: a slow client applies backpressure. */
    val outbound = Channel<TerminalServerMessage>(OUTBOUND_CAPACITY)

    private val startedAt = System.nanoTime()
    @Volatile
    private var lastActivity = startedAt
    private val terminating = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val endReason = AtomicReference<String?>(null)
    /** Launches the lifecycle. The returned job completes after cleanup and [onFinished]. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) { lifecycle(scope) }

    /** Writes client input to the PTY. Input is activity and resets the idle timer. */
    suspend fun write(text: String) {
        if (finished.get()) return
        lastActivity = System.nanoTime()
        withContext(Dispatchers.IO) {
            try {
                child.write(text.toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                // The PTY is closed because the shell is exiting; the lifecycle reports the exit.
                logger.debug("terminal {} write after close: {}", id, e.javaClass.simpleName)
            }
        }
    }

    suspend fun resize(cols: Int, rows: Int) {
        if (finished.get()) return
        withContext(Dispatchers.IO) {
            try {
                child.resize(cols, rows)
            } catch (e: Exception) {
                logger.debug("terminal {} resize failed: {}", id, e.javaClass.simpleName)
            }
        }
    }

    /** Ends the session with [reason], reported to the client as an `error` before `exit`. */
    suspend fun terminate(reason: String?) {
        if (!terminating.compareAndSet(false, true)) return
        if (reason != null) endReason.set(reason)
        withContext(Dispatchers.IO) { killTree() }
    }

    /**
     * Called when the client socket is gone or the agent is stopping: nobody will drain
     * [outbound] any more, so pending sends are cancelled and the session is killed.
     * Does not suspend, so it is safe from the Netty event loop and from shutdown hooks.
     */
    fun detach() {
        if (finished.get()) return
        outbound.cancel()
        if (terminating.compareAndSet(false, true)) killTree()
    }

    private fun killTree() {
        if (!finished.get()) ProcessGroups.killGroup(child.pid)
        SystemProcessControl.kill(SystemProcessControl.leftovers(child.identity, token))
    }

    private suspend fun lifecycle(scope: CoroutineScope) {
        // Not children of a coroutineScope: a reader stuck in a blocking read must not hold up
        // the slot release below. It is cancelled explicitly if it does not drain in time.
        val reader = scope.launch(Dispatchers.IO) { pumpOutput() }
        val watchdog = scope.launch(Dispatchers.IO) { watch() }
        try {
            val code = withContext(Dispatchers.IO) { child.waitFor() }
            watchdog.cancel()
            withContext(Dispatchers.IO) { killTree() }
            if (withTimeoutOrNull(DRAIN_TIMEOUT_MILLIS) { reader.join() } == null) {
                // Some process still holds the PTY although it was killed. Give up on the rest of its output.
                logger.warn("terminal session {} output did not drain; dropping it", id)
                reader.cancel()
            }
            finished.set(true)
            endReason.get()?.let { deliver(TerminalServerMessage.Error(it)) }
            deliver(TerminalServerMessage.Exit(code))
            logger.info("terminal session {} finished with exit code {}", id, code)
        } catch (e: CancellationException) {
            logger.info("terminal session {} cancelled", id)
            throw e
        } finally {
            watchdog.cancel()
            reader.cancel()
            finished.set(true)
            outbound.close()
            onFinished(this)
        }
    }

    private suspend fun pumpOutput() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val decoder = Utf8StreamDecoder()
        try {
            while (true) {
                val n = child.read(buffer)
                if (n < 0) break
                lastActivity = System.nanoTime()
                val text = decoder.decode(buffer, n)
                if (text.isNotEmpty()) deliver(TerminalServerMessage.Output(text))
            }
        } catch (e: IOException) {
            // EIO after the shell's side of the PTY closed is the normal end of output.
            logger.debug("terminal {} output ended: {}", id, e.javaClass.simpleName)
        }
        val tail = decoder.finish()
        if (tail.isNotEmpty()) deliver(TerminalServerMessage.Output(tail))
    }

    private suspend fun watch() {
        while (true) {
            delay(watchIntervalMillis)
            val now = System.nanoTime()
            when {
                now - startedAt >= maxTtlMillis * NANOS_PER_MILLI ->
                    return terminate("terminal session reached its maximum lifetime")
                now - lastActivity >= idleTimeoutMillis * NANOS_PER_MILLI ->
                    return terminate("terminal session was idle for too long")
            }
        }
    }

    /**
     * Queues a message for the client. Suspends while the client is slow (backpressure).
     * Throws CancellationException after [detach], which ends the calling coroutine.
     */
    private suspend fun deliver(message: TerminalServerMessage) {
        try {
            outbound.send(message)
        } catch (e: ClosedSendChannelException) {
            // Closed normally after the exit message was queued; nothing else to deliver.
        }
    }

    companion object {
        const val OUTBOUND_CAPACITY = 64
        const val READ_BUFFER_BYTES = 8 * 1024
        private const val DRAIN_TIMEOUT_MILLIS = 3_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Starts a shell on a new PTY. Blocking; use from Dispatchers.IO. */
        internal fun spawn(spec: SpawnSpec): PtyChild {
            val env = HashMap(System.getenv())
            env["TERM"] = "xterm-256color"
            env.putIfAbsent("LANG", "C.UTF-8")
            // Tags every descendant so leftovers that left the process group can still be found.
            env[EXECUTION_TOKEN_ENV] = spec.token
            val process = com.pty4j.PtyProcessBuilder(arrayOf(spec.shell))
                .setEnvironment(env)
                .setDirectory(spec.cwd)
                .setInitialColumns(spec.cols)
                .setInitialRows(spec.rows)
                .setConsole(false)
                .setUnixOpenTtyToPreserveOutputAfterTermination(true)
                .start()
            return PtyChild(process)
        }
    }
}
