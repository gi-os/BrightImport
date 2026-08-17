package com.gios.brightimport

import com.gios.brightimport.ptp.ObjectInfo
import com.gios.brightimport.ptp.Ptp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parsing this app cannot get wrong.
 *
 * An `ObjectInfo` off by two bytes still parses — it just reports a plausible-looking wrong file
 * size, and the download then stops early and writes a truncated JPEG that looks like a camera
 * fault. So the byte offsets are asserted rather than trusted.
 */
class ObjectInfoTest {

    /** Build the ISO layout by hand, so the test does not share code with the parser. */
    private fun objectInfoBytes(
        format: Int = Ptp.OF_JPEG,
        compressedSize: Int = 8_123_456,
        filename: String = "DSCF5087.JPG",
        date: String = "20150524T011710",
    ): ByteArray {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00010001)          // storage id
        b.putShort(format.toShort())  // object format
        b.putShort(0)                 // protection
        b.putInt(compressedSize)
        b.putShort(Ptp.OF_JPEG.toShort()) // thumb format
        b.putInt(12_345)              // thumb compressed size
        b.putInt(160)                 // thumb width
        b.putInt(120)                 // thumb height
        b.putInt(6240)                // image width
        b.putInt(4160)                // image height
        b.putInt(24)                  // bit depth
        b.putInt(3)                   // parent
        b.putShort(0)                 // association type
        b.putInt(0)                   // association desc
        b.putInt(1)                   // sequence
        check(b.position() == ObjectInfo.VARIABLE_START) {
            "scalar section is ${b.position()} bytes, expected ${ObjectInfo.VARIABLE_START}"
        }
        putPtpString(b, filename)
        putPtpString(b, date)
        putPtpString(b, "")           // date modified
        putPtpString(b, "")           // keywords
        return b.array().copyOf(b.position())
    }

    private fun putPtpString(b: ByteBuffer, s: String) {
        if (s.isEmpty()) {
            b.put(0)
            return
        }
        b.put((s.length + 1).toByte())   // count includes the null terminator
        s.forEach { b.putShort(it.code.toShort()) }
        b.putShort(0)
    }

    @Test
    fun `scalar section is exactly 52 bytes`() {
        // The whole reason Fuji's variant is drop-in compatible for the fields we read.
        val info = ObjectInfo.parse(1, objectInfoBytes())
        assertEquals("DSCF5087.JPG", info.filename)
    }

    @Test
    fun `parses size and name`() {
        val info = ObjectInfo.parse(7, objectInfoBytes(compressedSize = 8_123_456))
        assertEquals(7, info.handle)
        assertEquals(8_123_456, info.compressedSize)
        assertEquals(Ptp.OF_JPEG, info.format)
        assertEquals(6240, info.imageWidth)
        assertEquals("image/jpeg", info.mimeType)
    }

    @Test
    fun `parses the date the camera wrote`() {
        val info = ObjectInfo.parse(1, objectInfoBytes(date = "20150524T011710"))
        val millis = info.takenAtMillis()
        assertTrue("expected a parsed date, got null", millis != null)
        // 2015, not 1970 and not 57000 — the two ways this goes wrong.
        val year = java.util.Calendar.getInstance().apply { timeInMillis = millis!! }
            .get(java.util.Calendar.YEAR)
        assertEquals(2015, year)
    }

    @Test
    fun `an empty date is not an error`() {
        val info = ObjectInfo.parse(1, objectInfoBytes(date = ""))
        assertNull(info.takenAtMillis())
    }

    @Test
    fun `raw files get a raw mime type`() {
        val info = ObjectInfo.parse(1, objectInfoBytes(format = Ptp.OF_RAW, filename = "DSCF5087.RAF"))
        assertEquals("image/x-fuji-raf", info.mimeType)
    }

    @Test
    fun `a zero length string consumes exactly one byte`() {
        // Folders have no date. Reading two bytes here would shift every subsequent field.
        val data = byteArrayOf(0, 3, 'H'.code.toByte(), 0, 'i'.code.toByte(), 0, 0, 0)
        val (first, next) = ObjectInfo.readString(data, 0)
        assertEquals("", first)
        assertEquals(1, next)
        val (second, _) = ObjectInfo.readString(data, next)
        assertEquals("Hi", second)
    }

    @Test
    fun `uint32 array with a lying count does not blow up`() {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(9999) // claims 9999 entries, has two
        b.putInt(1)
        b.putInt(2)
        assertEquals(0, ObjectInfo.parseUInt32Array(b.array()).size)
    }
}
