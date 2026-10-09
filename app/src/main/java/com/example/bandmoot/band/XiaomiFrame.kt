package com.example.bandmoot.band

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xiaomi BLE packet framing, ported from Gadgetbridge (AGPL-3.0-or-later)
 * `service/devices/xiaomi/XiaomiCharacteristic.java` (write path + onCharacteristicChanged).
 *
 * Rewritten as dependency-free pure functions (no GB framework, no BtLEQueue):
 * the caller (XiaomiLink) owns GATT writes, notify registration, and sequencing.
 *
 * Wire format (all multi-byte ints little-endian):
 * - single command: [00 00][02][enc: 1=encrypted, 2=plain][idx u16 if enc][payload]
 *   band answers [00 00 03 result] (PAYLOAD_ACK, result 0 = ok)
 * - chunked start:  [00 00][00][enc: 1|0][numChunks u16]
 *   band answers [00 00 01 01] (start-ack) -> phone sends chunks [idx u16, 1-based][bytes]
 *   band answers [00 00 01 00] (end-ack); resend request [00 00 01 05][idx u16 ...]
 * - incoming chunked from band mirrors the same scheme; our acks live in [Ack].
 */
object XiaomiFrame {

    val PAYLOAD_ACK = byteArrayOf(0, 0, 3, 0)
    val CHUNK_START_ACK = byteArrayOf(0, 0, 1, 1)
    val CHUNK_END_ACK = byteArrayOf(0, 0, 1, 0)

    /** Default max write = MTU 247 - 3 ATT overhead (XiaomiCharacteristic). */
    fun maxWriteSize(mtu: Int): Int = mtu - 3

    /** Encrypted payloads go chunked when payload + 6 header bytes exceed the MTU window. */
    fun shouldWriteChunked(payloadSize: Int, encrypted: Boolean, maxWrite: Int): Boolean {
        if (!encrypted) return true // non-encrypted are always chunked
        return payloadSize + 6 > maxWrite
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

    /** Prepends the running encrypted index for chunked sends (XiaomiCharacteristic.sendNext). */
    fun prependEncIndex(payload: ByteArray, encIndex: Int): ByteArray =
        ByteBuffer.allocate(2 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(encIndex.toShort())
            .put(payload)
            .array()

    fun chunkCount(payloadSize: Int, maxWrite: Int): Int =
        Math.ceil(payloadSize / (maxWrite - 2).toDouble()).toInt().coerceAtLeast(1)

    sealed interface Incoming {
        /** Band started a chunked transfer: [count] chunks will follow. Reply CHUNK_START_ACK. */
        data class ChunkedStart(val encrypted: Boolean, val count: Int) : Incoming
        /** One chunk of an incoming transfer (1-based [index]). */
        data class Chunk(val index: Int, val bytes: ByteArray) : Incoming
        /** Single (non-chunked) command payload. Reply PAYLOAD_ACK first. */
        data class Single(val encrypted: Boolean, val payload: ByteArray) : Incoming
        /** Ack for our single write (result 0 = ok). */
        data class Ack(val result: Int) : Incoming
        object ChunkAckStart : Incoming // band ready: send all chunks now
        object ChunkAckEnd : Incoming // band got everything: payload done
        object ChunkNack : Incoming // band rejected: drop payload
        /** Band asks us to resend listed 1-based chunk indices. */
        data class ChunkResend(val indices: ShortArray) : Incoming
        /** Band reports missing incoming chunks (we are the sender side here). */
        data class MissingChunks(val indices: ShortArray) : Incoming
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

    /** Reassembles band->phone chunked transfers (mirror of reconstructPayloadFromChunks). */
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

        fun missing(maxPerRequest: Int): ShortArray {
            val out = mutableListOf<Short>()
            for (i in 1..expected) if (!chunks.containsKey(i)) out.add(i.toShort())
            return out.take(maxPerRequest).toShortArray()
        }
    }
}
