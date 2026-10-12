package net.kigawa.exkes.runtimeagent.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Identity of the root child process. The PID alone is not enough to kill
 * anything after a restart (PIDs are reused), so start time and command line
 * are compared as well.
 */
@Serializable
data class ProcessIdentity(
    val pid: Long,
    val startedAtEpochMillis: Long? = null,
    val commandLine: String? = null,
)

/**
 * Durable record of one execution (metadata, not logs). [token] is injected into the
 * child environment so leftover descendants can be found even after their parent is gone.
 * [requestDigest] lets a replayed POST with the same id be recognised without storing the request.
 */
@Serializable
data class ExecutionRecord(
    val snapshot: ExecutionSnapshot,
    val token: String,
    val requestDigest: String,
    val process: ProcessIdentity? = null,
)

/**
 * Persists execution records. [save] must be durable when it returns normally and must
 * throw when it cannot write; callers rely on that to fail closed.
 */
interface ExecutionStore {
    fun save(record: ExecutionRecord)
    fun loadAll(): List<ExecutionRecord>
}

class InMemoryExecutionStore : ExecutionStore {
    private val map = ConcurrentHashMap<String, ExecutionRecord>()
    override fun save(record: ExecutionRecord) {
        map[record.snapshot.id] = record
    }

    override fun loadAll(): List<ExecutionRecord> = map.values.toList()
}

/**
 * One `<id>.json` per execution. Written as tmp file, fsync, atomic rename, then fsync of
 * the directory, so a successful return survives a power loss.
 * A record that cannot be parsed fails startup instead of being silently ignored, because
 * ignoring it would hide an in-flight process and allow its ID to be reused.
 */
class FileExecutionStore(private val dir: Path) : ExecutionStore {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Files.createDirectories(dir)
    }

    override fun save(record: ExecutionRecord) {
        val id = record.snapshot.id
        val target = dir.resolve("$id.json")
        val tmp = dir.resolve("$id.json.tmp")
        val buf = ByteBuffer.wrap(json.encodeToString(ExecutionRecord.serializer(), record).toByteArray(Charsets.UTF_8))
        FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    }

    override fun loadAll(): List<ExecutionRecord> =
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.toList()
        }.map { file ->
            try {
                json.decodeFromString(ExecutionRecord.serializer(), Files.readString(file))
            } catch (e: Exception) {
                throw IllegalStateException("corrupt execution record: ${file.fileName}", e)
            }
        }
}
