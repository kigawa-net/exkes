package net.kigawa.exkes.runtimeagent.terminal

import java.nio.charset.StandardCharsets

/**
 * Turns a stream of PTY bytes into text without splitting multi-byte UTF-8 characters.
 *
 * A read can end in the middle of a character. Those trailing bytes are held back and
 * prepended to the next chunk. Malformed bytes are replaced with U+FFFD by the JDK decoder.
 * Not thread-safe: one instance per output stream.
 */
internal class Utf8StreamDecoder {
    private var pending = ByteArray(0)

    fun decode(chunk: ByteArray, length: Int): String {
        val all = if (pending.isEmpty()) chunk.copyOf(length) else pending + chunk.copyOf(length)
        val complete = completeLength(all)
        pending = all.copyOfRange(complete, all.size)
        return String(all, 0, complete, StandardCharsets.UTF_8)
    }

    /** Flushes held-back bytes at end of stream. A truncated character becomes U+FFFD. */
    fun finish(): String {
        val rest = pending
        pending = ByteArray(0)
        return if (rest.isEmpty()) "" else String(rest, StandardCharsets.UTF_8)
    }

    /** Length of the prefix that does not end inside a multi-byte character. */
    private fun completeLength(bytes: ByteArray): Int {
        val end = bytes.size
        // A UTF-8 character is at most 4 bytes, so only the last 3 bytes can be an unfinished lead.
        for (back in 1..minOf(3, end)) {
            val b = bytes[end - back].toInt() and 0xFF
            when {
                b and 0xC0 == 0x80 -> continue // continuation byte: keep looking for its lead
                b < 0xC0 -> return end // ASCII (or a stray byte): nothing pending
                else -> {
                    val needed = when {
                        b >= 0xF0 -> 4
                        b >= 0xE0 -> 3
                        else -> 2
                    }
                    return if (needed > back) end - back else end
                }
            }
        }
        return end
    }
}
