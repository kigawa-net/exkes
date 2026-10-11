package net.kigawa.exkes.runtimeagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.kigawa.exkes.runtimeagent.execution.ExecutionConflictException
import net.kigawa.exkes.runtimeagent.execution.ExecutionManager
import net.kigawa.exkes.runtimeagent.execution.ExecutionSnapshot
import net.kigawa.exkes.runtimeagent.execution.ExecutionStatus
import net.kigawa.exkes.runtimeagent.execution.ExecutionStore
import net.kigawa.exkes.runtimeagent.execution.FileExecutionStore
import net.kigawa.exkes.runtimeagent.execution.InMemoryExecutionStore
import net.kigawa.exkes.runtimeagent.execution.InvalidExecutionRequestException
import net.kigawa.exkes.runtimeagent.execution.LogStream
import net.kigawa.exkes.runtimeagent.execution.StartExecutionRequest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        log(id).read(0).first.filter { it.stream == stream }.joinToString("") { it.data }

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
        val seqs = m.log("a3").read(0).first.map { it.seq }
        assertEquals(seqs.indices.map { it + 1L }, seqs)
        val after = m.log("a3").read(seqs.first()).first
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
    fun `invalid requests and duplicate ids are rejected`() {
        val m = manager()
        assertFailsWith<InvalidExecutionRequestException> { m.start(StartExecutionRequest(command = emptyList())) }
        assertFailsWith<InvalidExecutionRequestException> { m.start(sh("true", "../x")) }
        assertFailsWith<InvalidExecutionRequestException> { m.start(sh("true", "ok", timeout = 0)) }
        m.start(sh("true", "dup"))
        assertFailsWith<ExecutionConflictException> { m.start(sh("true", "dup")) }
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
    fun `restart marks executions left in flight as FAILED and keeps finished ones`() {
        val dir = Files.createTempDirectory("agent-state")
        val first = manager(FileExecutionStore(dir))
        first.start(sh("echo done", "done1"))
        first.await("done1", ExecutionStatus.SUCCEEDED)
        first.start(sh("sleep 30", "live1"))
        first.await("live1", ExecutionStatus.RUNNING)
        // Simulate a crash: the old manager never gets to run shutdown().
        val second = manager(FileExecutionStore(dir))
        second.recover()
        assertEquals(ExecutionStatus.SUCCEEDED, second.get("done1").status)
        val live = second.get("live1")
        assertEquals(ExecutionStatus.FAILED, live.status)
        assertTrue(live.errorMessage!!.contains("restarted"))
        first.shutdown()
    }
}
