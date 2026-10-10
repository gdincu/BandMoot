package com.example.bandmoot.band

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Notification icon upload for the band (used for the Komoot logo).
 *
 * Call-flow and wire format ported from Gadgetbridge (AGPL-3.0-or-later):
 * - `service/devices/xiaomi/XiaomiBitmapUtils.java` (pixel formats)
 * - `service/devices/xiaomi/services/XiaomiDataUploadService.java` (upload body, part framing)
 *
 * Upload = data-upload type 50:
 * - body  = [00][type][md5 16][size u32][icon bytes][crc32 u32]  (all little-endian)
 * - parts = [totalParts u16][partIndex u16, 1-based][slice of body]
 * Each part goes out as one encrypted message on characteristic 0x0055 (see XiaomiLink).
 */
object XiaomiNotificationIcon {
    const val TYPE_NOTIFICATION_ICON = 50

    /** Used when the band's DataUploadAck omits chunkSize (Gadgetbridge default). */
    const val DEFAULT_CHUNK_SIZE = 2048

    // Pixel formats the band can request (NotificationIconRequest.pixelFormat).
    const val PIXEL_RGB_565_LE = 0
    const val PIXEL_RGB_565_BE = 1
    const val PIXEL_XRGB_8888_LE = 2
    const val PIXEL_ARGB_8888_LE = 3
    const val PIXEL_ARGB_8565_LE = 7
    const val PIXEL_ABGR_8565_LE = 8

    /** Prepared upload: [md5] and [size] go into DataUploadRequest, [body] is sent in parts. */
    class Upload(val md5: ByteArray, val size: Int, val body: ByteArray)

    /** Encodes row-major Android ARGB pixels (0xAARRGGBB) in [pixelFormat]; null if unknown. */
    fun encodePixels(argb: IntArray, pixelFormat: Int): ByteArray? = when (pixelFormat) {
        PIXEL_RGB_565_LE -> rgb565(argb, ByteOrder.LITTLE_ENDIAN)
        PIXEL_RGB_565_BE -> rgb565(argb, ByteOrder.BIG_ENDIAN)
        PIXEL_XRGB_8888_LE, PIXEL_ARGB_8888_LE -> argb8888(argb)
        PIXEL_ARGB_8565_LE -> argb8565(argb, swapRedBlue = false)
        PIXEL_ABGR_8565_LE -> argb8565(argb, swapRedBlue = true)
        else -> null
    }

    /** Builds the upload for already-scaled [argb] pixels; null if [pixelFormat] is unknown. */
    fun prepare(argb: IntArray, pixelFormat: Int): Upload? {
        val data = encodePixels(argb, pixelFormat) ?: return null
        val md5 = MessageDigest.getInstance("MD5").digest(data)
        val head = ByteBuffer.allocate(2 + md5.size + 4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0.toByte())
            .put(TYPE_NOTIFICATION_ICON.toByte())
            .put(md5)
            .putInt(data.size)
            .put(data)
            .array()
        val crc = CRC32().apply { update(head, 0, head.size) }.value.toInt()
        val body = ByteBuffer.allocate(head.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(head)
            .putInt(crc)
            .array()
        return Upload(md5, data.size, body)
    }

    /** Splits [body] into parts of at most [chunkSize] bytes, each part starting with its 4-byte header. */
    fun parts(body: ByteArray, chunkSize: Int): List<ByteArray> {
        val sliceSize = chunkSize - 4
        require(sliceSize > 0) { "chunk size too small: $chunkSize" }
        val total = (body.size + sliceSize - 1) / sliceSize
        return List(total) { i ->
            val from = i * sliceSize
            val len = minOf(sliceSize, body.size - from)
            ByteBuffer.allocate(4 + len).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(total.toShort())
                .putShort((i + 1).toShort())
                .put(body, from, len)
                .array()
        }
    }

    /** Top 5/6/5 bits of R/G/B packed into 16 bits (Android ARGB: R = bits 16-23, G = 8-15, B = 0-7). */
    private fun rgb565Bits(p: Int, swapRedBlue: Boolean): Int {
        val r = (p shr 19) and 0x1F
        val g = (p shr 10) and 0x3F
        val b = (p shr 3) and 0x1F
        return if (swapRedBlue) (b shl 11) or (g shl 5) or r else (r shl 11) or (g shl 5) or b
    }

    private fun rgb565(argb: IntArray, order: ByteOrder): ByteArray {
        val buf = ByteBuffer.allocate(argb.size * 2).order(order)
        for (p in argb) buf.putShort(rgb565Bits(p, swapRedBlue = false).toShort())
        return buf.array()
    }

    /** 3 bytes per pixel: RGB565 little-endian, then alpha. */
    private fun argb8565(argb: IntArray, swapRedBlue: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(argb.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (p in argb) {
            buf.putShort(rgb565Bits(p, swapRedBlue).toShort())
            buf.put((p ushr 24).toByte())
        }
        return buf.array()
    }

    /** 4 bytes per pixel: the ARGB int written little-endian, as Gadgetbridge does. */
    private fun argb8888(argb: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(argb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (p in argb) buf.putInt(p)
        return buf.array()
    }
}
