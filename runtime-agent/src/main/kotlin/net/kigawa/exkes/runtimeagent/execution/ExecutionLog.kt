package net.kigawa.exkes.runtimeagent.execution

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/**
 * Result of reading the log after a cursor. [droppedBefore] is the highest seq that was
 * evicted from the bounded buffer (0 when nothing was evicted). A reader whose cursor is
 * below it has missed chunks, and that gap is not recoverable.
 */
data class LogRead(
    val chunks: List<LogChunk>,
    val finished: Boolean,
    val droppedBefore: Long,
)

/**
 * Ordered, bounded in-memory log of one execution. When [maxBytes] is exceeded the oldest
 * chunks are dropped. Sequence numbers stay contiguous, and [LogRead.droppedBefore] reports
 * the eviction explicitly so a reader can tell a gap from a complete log.
 * [version] changes on every append / close so streamers can await new data without polling.
 */
class ExecutionLog(private val maxBytes: Long, private val clock: () -> Instant) {
    private val chunks = ArrayDeque<LogChunk>()
    private var bytes = 0L
    private var nextSeq = 1L
    private var droppedBefore = 0L
    private var closed = false
    val version = MutableStateFlow(0L)

    @Synchronized
    fun append(stream: LogStream, data: String) {
        if (data.isEmpty()) return
        chunks.addLast(LogChunk(nextSeq++, stream, data, clock().toString()))
        bytes += data.length
        while (bytes > maxBytes && chunks.size > 1) {
            val dropped = chunks.removeFirst()
            bytes -= dropped.data.length
            droppedBefore = dropped.seq
        }
        version.value = version.value + 1
    }

    @Synchronized
    fun close() {
        closed = true
        version.value = version.value + 1
    }

    /** Chunks with seq > [after], whether the log is closed, and the eviction watermark, read atomically. */
    @Synchronized
    fun read(after: Long): LogRead =
        LogRead(chunks.filter { it.seq > after }, closed, droppedBefore)

    @Synchronized
    fun lastSeq(): Long = nextSeq - 1
}
