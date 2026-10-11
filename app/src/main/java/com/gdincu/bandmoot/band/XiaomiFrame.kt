package com.gdincu.bandmoot.band

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xiaomi BLE packet framing.
 *
 * Wire format (little-endian):
 * - single: [00 00][02][enc: 1|2][idx u16 if enc][payload], ack [00 00 03 result]
 * - chunked start: [00 00][00][enc: 1|0][count u16], ack [00 00 01 01],
 *   chunks [idx u16][bytes], end-ack [00 00 01 00], resend [00 00 01 05][idx...]
 */
object XiaomiFrame {

    val PAYLOAD_ACK = byteArrayOf(0, 0, 3, 0)
    val CHUNK_START_ACK = byteArrayOf(0, 0, 1, 1)
    val CHUNK_END_ACK = byteArrayOf(0, 0, 1, 0)

    /** Default max write = MTU - 3 ATT overhead. */
    fun maxWriteSize(mtu: Int): Int = mtu - 3

    /** Single write when the framed payload fits, chunked otherwise. */
    fun shouldWriteChunked(payloadSize: Int, encrypted: Boolean, maxWrite: Int): Boolean {
        val header = if (encrypted) 6 else 4
        return payloadSize + header > maxWrite
    }

    fun encodeSingle(payload: ByteArray, encrypted: Boolean, encIndex: Int): ByteArray {
        val header = if (encrypted) 6 else 4
        val buf = ByteBuffer.allocate(header + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0)
        buf.put(2) // single command
        buf.put(if (encrypted) 1 else 2)
        if (encrypted) buf.putShort(encIndex.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun encodeChunkedStart(numChunks: Int, encrypted: Boolean): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(0) // chunked start
            .put(if (encrypted) 1 else 0)
            .putShort(numChunks.toShort())
            .array()

    fun encodeChunk(oneBasedIndex: Int, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(2 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(oneBasedIndex.toShort())
            .put(bytes)
            .array()

    /** Prepends the running encrypted index for chunked sends. */
    fun prependEncIndex(payload: ByteArray, encIndex: Int): ByteArray =
        ByteBuffer.allocate(2 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(encIndex.toShort())
            .put(payload)
            .array()

    fun chunkCount(payloadSize: Int, maxWrite: Int): Int =
        Math.ceil(payloadSize / (maxWrite - 2).toDouble()).toInt().coerceAtLeast(1)

    sealed interface Incoming {
        /** Band started a chunked transfer: [count] chunks will follow. */
        data class ChunkedStart(val encrypted: Boolean, val count: Int) : Incoming
        /** One chunk of an incoming transfer (1-based [index]). */
        data class Chunk(val index: Int, val bytes: ByteArray) : Incoming
        /** Single (non-chunked) command payload. */
        data class Single(val encrypted: Boolean, val payload: ByteArray) : Incoming
        /** Ack for our single write (result 0 = ok). */
        data class Ack(val result: Int) : Incoming
        object ChunkAckStart : Incoming
        object ChunkAckEnd : Incoming
        object ChunkNack : Incoming
        /** Band asks us to resend listed 1-based chunk indices. */
        data class ChunkResend(val indices: ShortArray) : Incoming
        data class Unknown(val raw: ByteArray) : Incoming
    }

    fun decode(value: ByteArray): Incoming {
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val chunk = buf.short.toInt() and 0xFFFF
        if (chunk != 0) {
            val rest = ByteArray(buf.remaining())
            buf.get(rest)
            return Incoming.Chunk(chunk, rest)
        }
        if (!buf.hasRemaining()) return Incoming.Unknown(value)
        return when (buf.get().toInt() and 0xFF) {
            0 -> { // chunked start request
                if (!buf.hasRemaining()) return Incoming.Unknown(value)
                val enc = buf.get().toInt() and 0xFF
                if (!buf.hasRemaining()) return Incoming.Unknown(value)
                Incoming.ChunkedStart(encrypted = enc == 1, count = buf.short.toInt() and 0xFFFF)
            }
            1 -> { // chunked ack
                if (!buf.hasRemaining()) return Incoming.Unknown(value)
                val sub = buf.get().toInt() and 0xFF
                val remaining = ByteArray(buf.remaining())
                buf.get(remaining)
                when (sub) {
                    0 -> Incoming.ChunkAckEnd
                    1 -> Incoming.ChunkAckStart
                    2 -> Incoming.ChunkNack
                    5 -> {
                        val bb = ByteBuffer.wrap(remaining).order(ByteOrder.LITTLE_ENDIAN)
                        val out = ShortArray(remaining.size / 2) { bb.short }
                        Incoming.ChunkResend(out)
                    }
                    else -> Incoming.Unknown(value)
                }
            }
            2 -> { // single command
                if (!buf.hasRemaining()) return Incoming.Unknown(value)
                val enc = buf.get().toInt() and 0xFF
                val rest = ByteArray(buf.remaining())
                buf.get(rest)
                Incoming.Single(encrypted = enc == 1, payload = rest)
            }
            3 -> { // ack, [result]
                Incoming.Ack(if (buf.hasRemaining()) buf.get().toInt() and 0xFF else -1)
            }
            else -> Incoming.Unknown(value)
        }
    }

    /** Reassembles band->phone chunked transfers. */
    class Reassembler {
        private var expected = 0
        private val chunks = sortedMapOf<Int, ByteArray>()

        /** @return complete payload once all [expected] chunks arrived, else null. */
        fun feed(start: Incoming.ChunkedStart): Unit {
            expected = start.count
            chunks.clear()
        }

        fun feed(chunk: Incoming.Chunk): ByteArray? {
            if (expected <= 0 || chunk.index > expected) return null
            chunks[chunk.index] = chunk.bytes
            if (chunks.size != expected) return null
            val out = ByteArrayOutputStream()
            for (i in 1..expected) {
                val b = chunks[i] ?: return null
                out.write(b)
            }
            expected = 0
            chunks.clear()
            return out.toByteArray()
        }
    }
}
