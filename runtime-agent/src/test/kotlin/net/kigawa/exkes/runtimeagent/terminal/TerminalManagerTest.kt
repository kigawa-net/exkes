package net.kigawa.exkes.runtimeagent.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.kigawa.exkes.runtimeagent.execution.SystemProcessControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class TerminalManagerTest {
    private fun request() = TerminalRequest(cols = 80, rows = 24, cwd = null, shell = null)

    private suspend fun awaitDead(pid: Long) {
        for (attempt in 0 until 200) {
            if (!SystemProcessControl.isAlive(pid)) return
            delay(50)
        }
        fail("process $pid survived")
    }

    @Test
    fun `open refuses sessions beyond the cap and a finished session frees its slot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = TerminalManager(scope, TerminalConfig(maxSessions = 2))
            val first = manager.open(request())
            manager.open(request())
            assertFailsWith<TerminalLimitException> { manager.open(request()) }

            first.detach()
            for (attempt in 0 until 200) {
                if (manager.activeSessions == 1) break
                delay(25)
            }
            assertEquals(1, manager.activeSessions)
            manager.open(request())
            assertEquals(2, manager.activeSessions)
            manager.shutdown()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `shutdown kills every shell that is still running`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = TerminalManager(scope, TerminalConfig(maxSessions = 3))
            val pids = listOf(manager.open(request()).pid, manager.open(request()).pid)
            pids.forEach { assertTrue(SystemProcessControl.isAlive(it)) }

            manager.shutdown()
            pids.forEach { awaitDead(it) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `query validation rejects bad sizes, relative paths and missing shells`() {
        fun parse(vararg pairs: Pair<String, String>): String? = try {
            val map = pairs.toMap()
            TerminalRequest.fromQuery { map[it] }
            null
        } catch (e: InvalidTerminalRequestException) {
            e.message
        }

        assertEquals(null, parse())
        assertEquals(null, parse("cols" to "1000", "rows" to "1000"))
        assertEquals("cols and rows must be between 1 and 1000", parse("cols" to "1001"))
        assertEquals("cols and rows must be between 1 and 1000", parse("rows" to "0"))
        assertEquals("cwd must be an absolute path", parse("cwd" to "tmp"))
        assertEquals("cwd is not a directory", parse("cwd" to "/nonexistent-dir-for-test"))
        assertEquals("shell is not an executable file", parse("shell" to "/nonexistent-shell"))
    }
}
