package net.kigawa.exkes.runtimeagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.kigawa.exkes.runtimeagent.execution.ExecutionConflictException
import net.kigawa.exkes.runtimeagent.execution.ExecutionManager
import net.kigawa.exkes.runtimeagent.execution.ExecutionRecord
import net.kigawa.exkes.runtimeagent.execution.ExecutionSnapshot
import net.kigawa.exkes.runtimeagent.execution.ExecutionStatus
import net.kigawa.exkes.runtimeagent.execution.ExecutionStore
import net.kigawa.exkes.runtimeagent.execution.FileExecutionStore
import net.kigawa.exkes.runtimeagent.execution.InMemoryExecutionStore
import net.kigawa.exkes.runtimeagent.execution.InvalidExecutionRequestException
import net.kigawa.exkes.runtimeagent.execution.LogStream
import net.kigawa.exkes.runtimeagent.execution.ProcessControl
import net.kigawa.exkes.runtimeagent.execution.ProcessIdentity
import net.kigawa.exkes.runtimeagent.execution.StartExecutionRequest
import net.kigawa.exkes.runtimeagent.execution.SystemProcessControl
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.exkes.runtimeagent.execution.ExecutionLog
import java.time.Instant

/** Store whose writes fail for records matching [failWhen]; used for fault injection. */
private class FailingStore(
    private val inner: ExecutionStore,
    private val failWhen: (ExecutionRecord) -> Boolean,
) : ExecutionStore {
    val tokens = mutableMapOf<String, String>()
    override fun save(record: ExecutionRecord) {
        tokens[record.snapshot.id] = record.token
        if (failWhen(record)) throw IOException("injected write failure")
        inner.save(record)
    }

    override fun loadAll(): List<ExecutionRecord> = inner.loadAll()
}

/** Control whose stop can be made to look unconfirmed, to exercise the LOST path. */
private class FakeControl : ProcessControl {
    @Volatile var stuck = true
    override fun identify(process: Process) = ProcessIdentity(process.pid())
    override fun leftovers(identity: ProcessIdentity?, token: String): Set<Long> = if (stuck) setOf(4242L) else emptySet()
    override fun kill(pids: Set<Long>) {}
    override fun awaitGone(pids: Set<Long>, timeoutMillis: Long): Boolean = !stuck
}

class ExecutionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun manager(store: ExecutionStore = InMemoryExecutionStore(), workingDir: String? = null) =
        ExecutionManager(scope, store, workingDir)

    private fun sh(script: String, id: String, timeout: Long? = null, env: Map<String, String> = emptyMap(), dir: String? = null) =
        StartExecutionRequest(id = id, command = listOf("sh", "-c", script), env = env, workingDir = dir, timeoutSeconds = timeout)

    private fun ExecutionManager.await(id: String, vararg want: ExecutionStatus, ms: Long = 10_000): ExecutionSnapshot {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val s = get(id)
            if (s.status in want) return s
            Thread.sleep(20)
        }
        error("timeout waiting for $want, was ${get(id)}")
    }

    private fun ExecutionManager.output(id: String, stream: LogStream) =
        log(id).read(0).chunks.filter { it.stream == stream }.joinToString("") { it.data }

    private fun snapshot(id: String, status: ExecutionStatus) = ExecutionSnapshot(
        id = id,
        command = listOf("sleep", "30"),
        workingDir = null,
        status = status,
        createdAt = Instant.EPOCH.toString(),
    )

    @Test
    fun `successful command reports SUCCEEDED exit code 0 and stdout`() {
        val m = manager()
        m.start(sh("echo hello", "a1"))
        val s = m.await("a1", ExecutionStatus.SUCCEEDED)
        assertEquals(0, s.exitCode)
        assertNotNull(s.startedAt)
        assertNotNull(s.finishedAt)
        assertEquals("hello\n", m.output("a1", LogStream.STDOUT))
    }

    @Test
    fun `non-zero exit is FAILED with the exit code`() {
        val m = manager()
        m.start(sh("echo oops >&2; exit 3", "a2"))
        val s = m.await("a2", ExecutionStatus.FAILED)
        assertEquals(3, s.exitCode)
        assertEquals("oops\n", m.output("a2", LogStream.STDERR))
        assertEquals("", m.output("a2", LogStream.STDOUT))
    }

    @Test
    fun `stdout and stderr chunks have contiguous ordered sequence numbers`() {
        val m = manager()
        m.start(sh("echo one; echo two >&2; echo three", "a3"))
        m.await("a3", ExecutionStatus.SUCCEEDED)
        val seqs = m.log("a3").read(0).chunks.map { it.seq }
        assertEquals(seqs.indices.map { it + 1L }, seqs)
        val after = m.log("a3").read(seqs.first()).chunks
        assertEquals(seqs.drop(1), after.map { it.seq })
    }

    @Test
    fun `working directory and environment are applied`() {
        val dir = Files.createTempDirectory("agent-wd")
        val m = manager()
        m.start(sh("pwd; echo \$FOO", "a4", env = mapOf("FOO" to "bar"), dir = dir.toString()))
        m.await("a4", ExecutionStatus.SUCCEEDED)
        val lines = m.output("a4", LogStream.STDOUT).lines()
        assertEquals(dir.toRealPath().toString(), lines[0])
        assertEquals("bar", lines[1])
    }

    @Test
    fun `timeout kills the process and reports TIMED_OUT`() {
        val m = manager()
        m.start(sh("sleep 30", "a5", timeout = 1))
        val s = m.await("a5", ExecutionStatus.TIMED_OUT)
        assertNotNull(s.finishedAt)
    }

    @Test
    fun `cancel kills a running process and reports CANCELLED`() {
        val m = manager()
        m.start(sh("echo started; sleep 30", "a6"))
        m.await("a6", ExecutionStatus.RUNNING)
        m.cancel("a6")
        val s = m.await("a6", ExecutionStatus.CANCELLED)
        assertNull(s.errorMessage)
    }

    @Test
    fun `cancel after completion is a no-op`() {
        val m = manager()
        m.start(sh("true", "a7"))
        m.await("a7", ExecutionStatus.SUCCEEDED)
        assertEquals(ExecutionStatus.SUCCEEDED, m.cancel("a7").status)
    }

    @Test
    fun `unstartable command is FAILED with a message`() {
        val m = manager()
        m.start(StartExecutionRequest(id = "a8", command = listOf("/nonexistent/binary")))
        val s = m.await("a8", ExecutionStatus.FAILED)
        assertTrue(s.errorMessage!!.contains("failed to start"))
        assertNull(s.exitCode)
    }

    @Test
    fun `invalid requests and conflicting duplicate ids are rejected`() {
        val m = manager()
        assertFailsWith<InvalidExecutionRequestException> { m.start(StartExecutionRequest(command = emptyList())) }
        assertFailsWith<InvalidExecutionRequestException> { m.start(sh("true", "../x")) }
        assertFailsWith<InvalidExecutionRequestException> { m.start(sh("true", "ok", timeout = 0)) }
        m.start(sh("true", "dup"))
        assertFailsWith<ExecutionConflictException> { m.start(sh("echo different", "dup")) }
    }

    @Test
    fun `replaying the same request after a lost response does not run it twice`() {
        val dir = Files.createTempDirectory("agent-idem")
        val marker = dir.resolve("count.txt")
        val m = manager()
        val first = m.start(sh("echo x >> '$marker'", "idem1"))
        val replay = m.start(sh("echo x >> '$marker'", "idem1"))
        assertTrue(first.created)
        assertFalse(replay.created)
        m.await("idem1", ExecutionStatus.SUCCEEDED)
        assertEquals(1, Files.readAllLines(marker).size)
    }

    @Test
    fun `persistence failure before start throws and never spawns the process`() {
        val dir = Files.createTempDirectory("agent-nostart")
        val marker = dir.resolve("started")
        val failing = FailingStore(InMemoryExecutionStore()) { it.snapshot.status == ExecutionStatus.QUEUED }
        val m = manager(failing)
        assertFailsWith<IOException> { m.start(sh("touch '$marker'", "p1")) }
        Thread.sleep(300)
        assertFalse(Files.exists(marker))
        assertFailsWith<net.kigawa.exkes.runtimeagent.execution.ExecutionNotFoundException> { m.get("p1") }
    }

    @Test
    fun `running state that cannot be persisted stops the child and never reports success`() {
        val dir = Files.createTempDirectory("agent-runfail")
        val failing = FailingStore(InMemoryExecutionStore()) { it.snapshot.status == ExecutionStatus.RUNNING }
        val m = manager(failing)
        m.start(sh("echo hi; sleep 30", "p2", dir = dir.toString()))
        val s = m.await("p2", ExecutionStatus.FAILED, ExecutionStatus.LOST)
        assertEquals(ExecutionStatus.FAILED, s.status)
        assertTrue(s.errorMessage!!.contains("could not persist RUNNING"), s.errorMessage)
        val token = failing.tokens.getValue("p2")
        Thread.sleep(200)
        assertTrue(SystemProcessControl.leftovers(null, token).isEmpty(), "child must be stopped")
    }

    @Test
    fun `final state that cannot be persisted is FAILED, not SUCCEEDED`() {
        val failing = FailingStore(InMemoryExecutionStore()) { it.snapshot.status == ExecutionStatus.SUCCEEDED }
        val m = manager(failing)
        m.start(sh("echo ok", "p3"))
        val s = m.await("p3", ExecutionStatus.FAILED, ExecutionStatus.SUCCEEDED)
        assertEquals(ExecutionStatus.FAILED, s.status)
        assertTrue(s.errorMessage!!.contains("could not persist SUCCEEDED"), s.errorMessage)
    }

    @Test
    fun `shutdown fails in-flight executions and kills the process`() {
        val m = manager()
        m.start(sh("sleep 30", "a9"))
        m.await("a9", ExecutionStatus.RUNNING)
        m.shutdown()
        val s = m.get("a9")
        assertEquals(ExecutionStatus.FAILED, s.status)
        assertTrue(s.errorMessage!!.contains("shut down"))
        Thread.sleep(300)
        assertEquals(ExecutionStatus.FAILED, m.get("a9").status)
    }

    @Test
    fun `cancel racing with start never leaves a running process behind`() {
        val m = manager()
        repeat(10) { i ->
            m.start(sh("sleep 30 & sleep 30", "race$i"))
            m.cancel("race$i")
            assertEquals(ExecutionStatus.CANCELLED, m.await("race$i", ExecutionStatus.CANCELLED).status)
        }
    }

    @Test
    fun `restart kills the real process tree of an execution left in flight`() {
        val dir = Files.createTempDirectory("agent-restart")
        val work = Files.createTempDirectory("agent-restart-work")
        val first = manager(FileExecutionStore(dir))
        first.start(sh("sleep 30 & echo \$! > child.pid; wait", "gc1", dir = work.toString()))
        first.await("gc1", ExecutionStatus.RUNNING)
        val pidFile = work.resolve("child.pid")
        val deadline = System.currentTimeMillis() + 5_000
        while (!Files.exists(pidFile) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val grandchild = Files.readString(pidFile).trim().toLong()
        assertTrue(SystemProcessControl.isAlive(grandchild))

        // Simulate a crash: the old manager never runs shutdown(). The new agent must stop the tree first.
        val second = manager(FileExecutionStore(dir))
        second.recover()
        assertEquals(ExecutionStatus.FAILED, second.get("gc1").status)
        assertFalse(SystemProcessControl.isAlive(grandchild), "grandchild must not survive the restart")
    }

    @Test
    fun `restart does not kill an unrelated process whose PID was reused`() {
        val dir = Files.createTempDirectory("agent-reuse")
        val store = FileExecutionStore(dir)
        val other = ProcessBuilder("sleep", "30").start()
        try {
            // Record points at the PID of an unrelated process: start time and command line do not match.
            store.save(
                ExecutionRecord(
                    snapshot = snapshot("reuse1", ExecutionStatus.RUNNING),
                    token = "token-reuse-1",
                    requestDigest = "digest",
                    process = ProcessIdentity(other.pid(), startedAtEpochMillis = 1L, commandLine = "not sleep"),
                ),
            )
            val m = manager(FileExecutionStore(dir))
            m.recover()
            assertEquals(ExecutionStatus.FAILED, m.get("reuse1").status)
            assertTrue(SystemProcessControl.isAlive(other.pid()), "unrelated process must not be killed")
        } finally {
            other.destroyForcibly()
            other.waitFor()
        }
    }

    @Test
    fun `unconfirmed stop makes the execution LOST and refuses new executions until it is confirmed`() {
        val store = InMemoryExecutionStore()
        store.save(
            ExecutionRecord(
                snapshot = snapshot("lost1", ExecutionStatus.RUNNING),
                token = "token-lost-1",
                requestDigest = "digest",
                process = ProcessIdentity(1L),
            ),
        )
        val control = FakeControl()
        val m = ExecutionManager(scope, store, null, control = control)
        m.recover()
        assertEquals(ExecutionStatus.LOST, m.get("lost1").status)
        assertFailsWith<ExecutionConflictException> { m.start(sh("true", "new1")) }

        control.stuck = false
        m.start(sh("true", "new1"))
        assertEquals(ExecutionStatus.FAILED, m.get("lost1").status)
        m.await("new1", ExecutionStatus.SUCCEEDED)
    }

    @Test
    fun `log reports evicted chunks through droppedBefore`() {
        val log = ExecutionLog(maxBytes = 4) { Instant.EPOCH }
        log.append(LogStream.STDOUT, "aaaa")
        log.append(LogStream.STDOUT, "bbbb")
        log.append(LogStream.STDOUT, "cccc")
        val read = log.read(0)
        assertEquals(listOf(3L), read.chunks.map { it.seq })
        assertEquals(2L, read.droppedBefore)
        assertEquals(2L, log.read(1).droppedBefore)
        assertEquals(0L, ExecutionLog(maxBytes = 4) { Instant.EPOCH }.read(0).droppedBefore)
    }
}
