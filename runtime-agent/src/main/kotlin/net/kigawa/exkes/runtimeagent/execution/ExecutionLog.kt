package net.kigawa.exkes.runtimeagent.execution

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * Ordered, bounded in-memory log of one execution. When [maxBytes] is exceeded
 * the oldest chunks are dropped; sequence numbers stay contiguous so a reader
 * can detect the gap. [version] changes on every append / close so streamers can
 * await new data without polling.
 */
class ExecutionLog(private val maxBytes: Long, private val clock: () -> Instant) {
    private val chunks = ArrayDeque<LogChunk>()
    private var bytes = 0L
    private var nextSeq = 1L
    private var closed = false
    val version = MutableStateFlow(0L)

    @Synchronized
    fun append(stream: LogStream, data: String) {
        if (data.isEmpty()) return
        chunks.addLast(LogChunk(nextSeq++, stream, data, clock().toString()))
        bytes += data.length
        while (bytes > maxBytes && chunks.size > 1) bytes -= chunks.removeFirst().data.length
        version.value = version.value + 1
    }

    @Synchronized
    fun close() {
        closed = true
        version.value = version.value + 1
    }

    /** Chunks with seq > [after], plus whether the log is closed (no more will come). */
    @Synchronized
    fun read(after: Long): Pair<List<LogChunk>, Boolean> =
        chunks.filter { it.seq > after } to closed

    @Synchronized
    fun lastSeq(): Long = nextSeq - 1
}
