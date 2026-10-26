package net.kigawa.exkes.runtimeagent

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import net.kigawa.exkes.runtimeagent.execution.InMemoryExecutionStore
import net.kigawa.exkes.runtimeagent.execution.SystemProcessControl
import net.kigawa.exkes.runtimeagent.terminal.TerminalClientMessage
import net.kigawa.exkes.runtimeagent.terminal.TerminalConfig
import net.kigawa.exkes.runtimeagent.terminal.TerminalServerMessage
import net.kigawa.exkes.runtimeagent.terminal.terminalJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Typed view of one terminal WebSocket: sends client messages and reads server messages. */
private class Terminal(private val ws: DefaultClientWebSocketSession) {
    /** Everything the shell has printed so far. */
    val transcript = StringBuilder()

    suspend fun send(message: TerminalClientMessage) {
        ws.send(Frame.Text(terminalJson.encodeToString(TerminalClientMessage.serializer(), message)))
    }

    suspend fun input(text: String) = send(TerminalClientMessage.Input(text))

    suspend fun sendRaw(text: String) = ws.send(Frame.Text(text))

    suspend fun receive(): TerminalServerMessage = withTimeout(TIMEOUT_MS) {
        val frame = ws.incoming.receive()
        check(frame is Frame.Text) { "unexpected frame: ${frame.frameType}" }
        terminalJson.decodeFromString(TerminalServerMessage.serializer(), frame.readText())
    }

    /**
     * Reads until [pattern] matches the transcript. Returns null if an `error` message arrives
     * first. Patterns use results that the shell prints, not the typed echo of the command.
     */
    suspend fun waitMatch(pattern: Regex): MatchResult? {
        while (true) {
            pattern.find(transcript)?.let { return it }
            when (val message = receive()) {
                is TerminalServerMessage.Output -> transcript.append(message.data)
                is TerminalServerMessage.Error -> return null
                is TerminalServerMessage.Exit -> fail("shell exited ${message.code} before '$pattern'")
            }
        }
    }

    suspend fun waitFor(literal: String): MatchResult =
        waitMatch(Regex(Regex.escape(literal))) ?: fail("error received before '$literal'")

    /** Reads until the `error` message and returns it. Output received meanwhile is kept. */
    suspend fun receiveError(): TerminalServerMessage.Error {
        while (true) {
            when (val message = receive()) {
                is TerminalServerMessage.Output -> transcript.append(message.data)
                is TerminalServerMessage.Error -> return message
                is TerminalServerMessage.Exit -> fail("shell exited ${message.code}, expected an error")
            }
        }
    }

    /** Reads until the `exit` message and returns its code. Output received meanwhile is kept. */
    suspend fun receiveExit(): Int {
        while (true) {
            when (val message = receive()) {
                is TerminalServerMessage.Output -> transcript.append(message.data)
                is TerminalServerMessage.Exit -> return message.code
                is TerminalServerMessage.Error -> fail("unexpected error: ${message.message}")
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}

class TerminalRoutesTest {
    private companion object {
        const val SLOT_RELEASE_TIMEOUT_NANOS = 30_000_000_000L
    }

    private fun ApplicationTestBuilder.terminalClient() = createClient { install(WebSockets) }

    /** Waits until the process is gone. Zombies count as gone (see SystemProcessControl.isAlive). */
    private suspend fun awaitDead(pid: Long) {
        for (attempt in 0 until 200) {
            if (!SystemProcessControl.isAlive(pid)) return
            delay(50)
        }
        fail("process $pid survived the session")
    }

    @Test
    fun `input is echoed and shell output comes back as UTF-8`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        terminalClient().webSocket("/terminal") {
            val terminal = Terminal(this)
            // The typed command shows "$((1+2))", so "hello-3" can only come from the shell's output.
            terminal.input("echo hello-\$((1+2)) and あいう\n")
            terminal.waitFor("hello-3 and あいう")
        }
    }

    @Test
    fun `initial size comes from the query and resize messages update the PTY`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        terminalClient().webSocket("/terminal?cols=100&rows=30") {
            val terminal = Terminal(this)
            terminal.input("stty size\n")
            terminal.waitFor("30 100")

            terminal.send(TerminalClientMessage.Resize(cols = 120, rows = 40))
            terminal.input("stty size\n")
            terminal.waitFor("40 120")
        }
    }

    @Test
    fun `shell exit code is delivered as an exit message`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        terminalClient().webSocket("/terminal") {
            val terminal = Terminal(this)
            terminal.input("exit 7\n")
            assertEquals(7, terminal.receiveExit())
        }
    }

    @Test
    fun `closing the socket kills the shell and its background jobs`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        var shellPid = -1L
        var jobPid = -1L
        terminalClient().webSocket("/terminal") {
            val terminal = Terminal(this)
            terminal.input("sleep 1000 & echo JOB:\$!; echo SHELL:\$\$\n")
            val shell = terminal.waitMatch(Regex("SHELL:(\\d+)")) ?: fail("no shell pid")
            shellPid = shell.groupValues[1].toLong()
            jobPid = Regex("JOB:(\\d+)").find(terminal.transcript)?.groupValues?.get(1)?.toLong()
                ?: fail("no job pid")
            assertTrue(SystemProcessControl.isAlive(shellPid))
            assertTrue(SystemProcessControl.isAlive(jobPid))
        }
        // The client has left. The teardown must happen without any further request.
        awaitDead(shellPid)
        awaitDead(jobPid)
    }

    @Test
    fun `close message ends the shell`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        var shellPid = -1L
        terminalClient().webSocket("/terminal") {
            val terminal = Terminal(this)
            terminal.input("echo PID:\$\$\n")
            shellPid = terminal.waitMatch(Regex("PID:(\\d+)"))!!.groupValues[1].toLong()
            terminal.send(TerminalClientMessage.Close)
        }
        awaitDead(shellPid)
    }

    @Test
    fun `second session is rejected when the cap is reached, and the slot is reused after release`() = testApplication {
        application { agentModule(InMemoryExecutionStore(), terminal = TerminalConfig(maxSessions = 1)) }
        val client = terminalClient()
        client.webSocket("/terminal") {
            val first = Terminal(this)
            first.input("echo r\$((40+2))\n")
            first.waitFor("r42")

            client.webSocket("/terminal") {
                val error = Terminal(this).receiveError()
                assertContains(error.message, "too many terminal sessions")
            }
        }

        // The first socket is gone. Its slot comes back once the shell has been torn down.
        // A rejected attempt ends its socket right away, so every step of an attempt tolerates
        // the socket closing underneath it; attempts are retried until a generous deadline.
        var reopened = false
        val deadline = System.nanoTime() + SLOT_RELEASE_TIMEOUT_NANOS
        while (!reopened && System.nanoTime() < deadline) {
            reopened = try {
                var ok = false
                client.webSocket("/terminal") {
                    val terminal = Terminal(this)
                    ok = try {
                        terminal.input("echo r\$((40+2))\n")
                        terminal.waitMatch(Regex("r42")) != null
                    } catch (e: Exception) {
                        false
                    }
                }
                ok
            } catch (e: Exception) {
                false
            }
            if (!reopened) delay(100)
        }
        assertTrue(reopened, "the session slot was never released")
    }

    @Test
    fun `idle session is ended with an error message`() = testApplication {
        application {
            agentModule(
                InMemoryExecutionStore(),
                terminal = TerminalConfig(idleTimeoutMillis = 800, watchIntervalMillis = 100),
            )
        }
        terminalClient().webSocket("/terminal") {
            val error = Terminal(this).receiveError()
            assertContains(error.message, "idle")
        }
    }

    @Test
    fun `session past its maximum lifetime is ended even while active`() = testApplication {
        application {
            agentModule(
                InMemoryExecutionStore(),
                terminal = TerminalConfig(maxTtlMillis = 800, idleTimeoutMillis = 60_000, watchIntervalMillis = 100),
            )
        }
        terminalClient().webSocket("/terminal") {
            val terminal = Terminal(this)
            terminal.input("echo busy\n")
            val error = terminal.receiveError()
            assertContains(error.message, "maximum lifetime")
        }
    }

    @Test
    fun `invalid query parameters are rejected with an error and a policy close code`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        val client = terminalClient()
        val paths = listOf("/terminal?cols=0", "/terminal?rows=abc", "/terminal?cwd=relative", "/terminal?shell=/nonexistent/sh")
        for (path in paths) {
            client.webSocket(path) {
                assertIs<TerminalServerMessage.Error>(Terminal(this).receive(), path)
                val reason = withTimeout(Terminal.TIMEOUT_MS) { closeReason.await() }
                assertEquals(1008, reason?.code?.toInt(), path)
            }
        }
    }

    @Test
    fun `malformed client messages are reported without ending the session`() = testApplication {
        application { agentModule(InMemoryExecutionStore()) }
        terminalClient().webSocket("/terminal") {
            val terminal = Terminal(this)
            // The shell's prompt may arrive before any error, so skip output while waiting for each error.
            terminal.sendRaw("""{"type":"no-such-type"}""")
            terminal.receiveError()
            terminal.sendRaw("""{"type":"resize","cols":0,"rows":10}""")
            terminal.receiveError()
            terminal.input("echo r\$((40+2))\n")
            terminal.waitFor("r42")
        }
    }
}
