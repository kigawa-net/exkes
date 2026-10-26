package net.kigawa.exkes.runtimeagent.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8StreamDecoderTest {
    private fun decodeInChunks(bytes: ByteArray, chunkSize: Int): String {
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(chunkSize, bytes.size - offset)
            out.append(decoder.decode(bytes.copyOfRange(offset, offset + n), n))
            offset += n
        }
        out.append(decoder.finish())
        return out.toString()
    }

    @Test
    fun `multi-byte characters split across reads are reassembled`() {
        val text = "aあb😀c" // Hiragana (3 bytes) and an emoji (4 bytes)
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (chunkSize in 1..bytes.size) {
            assertEquals(text, decodeInChunks(bytes, chunkSize), "chunk size $chunkSize")
        }
    }

    @Test
    fun `a truncated character at end of stream becomes U+FFFD`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(byteArrayOf(0xE3.toByte(), 0x81.toByte()), 2))
        assertEquals("�", decoder.finish())
    }

    @Test
    fun `invalid bytes are replaced without losing the following text`() {
        val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0x62)
        assertEquals("a�b", decodeInChunks(bytes, 3))
    }

    @Test
    fun `only the requested length of a reused buffer is decoded`() {
        val decoder = Utf8StreamDecoder()
        val buffer = "xyz".toByteArray(Charsets.UTF_8).copyOf(8)
        assertEquals("xy", decoder.decode(buffer, 2))
    }
}
