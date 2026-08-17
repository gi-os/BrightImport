package com.gios.brightimport.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/**
 * One file on the camera's card.
 *
 * Fuji does define its own `ObjectInfo` variant with the fields shuffled, but its variable-length
 * section starts at the same byte 52 as the ISO layout, and Fuji's own client asks for the standard
 * one — so that is what this parses. If a body ever comes back with nonsense sizes, the vendor
 * layout is the thing to suspect.
 */
data class ObjectInfo(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val compressedSize: Int,
    val thumbFormat: Int,
    val thumbCompressedSize: Int,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val parentObject: Int,
    val filename: String,
    val dateCreated: String,
) {
    val isFolder: Boolean get() = format == Ptp.OF_ASSOCIATION

    /**
     * `20150524T011710` as epoch milliseconds, or null when the camera left the field empty.
     *
     * Parsed as local time deliberately. PTP allows a trailing `Z` or an offset and Fuji sends
     * neither, so the string is whatever the camera's clock said — treating it as UTC would drag
     * every import several hours out of place in the roll, which is the same class of bug the
     * calendar import hit.
     */
    fun takenAtMillis(): Long? {
        val s = dateCreated
        if (s.length < 15 || s[8] != 'T') return null
        return runCatching {
            val cal = Calendar.getInstance(
                if (s.endsWith("Z")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
            )
            cal.clear()
            cal.set(
                s.substring(0, 4).toInt(),
                s.substring(4, 6).toInt() - 1,
                s.substring(6, 8).toInt(),
                s.substring(9, 11).toInt(),
                s.substring(11, 13).toInt(),
                s.substring(13, 15).toInt(),
            )
            cal.timeInMillis
        }.getOrNull()
    }

    val mimeType: String
        get() = when (format) {
            Ptp.OF_JPEG -> "image/jpeg"
            Ptp.OF_MOV -> "video/quicktime"
            Ptp.OF_RAW -> when {
                filename.endsWith(".RAF", true) -> "image/x-fuji-raf"
                filename.endsWith(".DNG", true) -> "image/x-adobe-dng"
                filename.endsWith(".ARW", true) -> "image/x-sony-arw"
                else -> "application/octet-stream"
            }
            else -> "image/jpeg"
        }

    companion object {
        /** Where the strings begin. Same offset in the ISO layout and in Fuji's variant. */
        const val VARIABLE_START = 52

        fun parse(handle: Int, data: ByteArray): ObjectInfo {
            require(data.size >= VARIABLE_START) { "ObjectInfo payload is only ${data.size} bytes" }
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val storageId = b.int
            val format = b.short.toInt() and 0xFFFF
            b.short // protection status
            val compressedSize = b.int
            val thumbFormat = b.short.toInt() and 0xFFFF
            val thumbCompressedSize = b.int
            val thumbWidth = b.int
            val thumbHeight = b.int
            val imageWidth = b.int
            val imageHeight = b.int
            b.int // bit depth
            val parentObject = b.int
            b.short // association type
            b.int // association description
            b.int // sequence number

            var offset = VARIABLE_START
            val (filename, afterName) = readString(data, offset)
            offset = afterName
            val (dateCreated, _) = readString(data, offset)

            return ObjectInfo(
                handle = handle,
                storageId = storageId,
                format = format,
                compressedSize = compressedSize,
                thumbFormat = thumbFormat,
                thumbCompressedSize = thumbCompressedSize,
                thumbWidth = thumbWidth,
                thumbHeight = thumbHeight,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                parentObject = parentObject,
                filename = filename,
                dateCreated = dateCreated,
            )
        }

        /**
         * A PTP string: one byte of character count *including* the null terminator, then that
         * many UTF-16LE characters. A count of zero is an empty string one byte long, not a
         * malformed one — folders routinely have no date.
         */
        fun readString(data: ByteArray, offset: Int): Pair<String, Int> {
            if (offset >= data.size) return "" to offset
            val count = data[offset].toInt() and 0xFF
            if (count == 0) return "" to offset + 1
            val bytes = count * 2
            val end = (offset + 1 + bytes).coerceAtMost(data.size)
            val sb = StringBuilder()
            var i = offset + 1
            while (i + 1 < end) {
                val ch = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)
                if (ch == 0) break
                sb.append(ch.toChar())
                i += 2
            }
            return sb.toString() to offset + 1 + bytes
        }

        /** A PTP array of u32: a count, then that many values. */
        fun parseUInt32Array(data: ByteArray): IntArray {
            if (data.size < 4) return IntArray(0)
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val n = b.int
            if (n < 0 || n > (data.size - 4) / 4) return IntArray(0)
            return IntArray(n) { b.int }
        }
    }
}
