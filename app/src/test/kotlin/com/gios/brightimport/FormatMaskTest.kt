package com.gios.brightimport

import com.gios.brightimport.ptp.FormatMask
import com.gios.brightimport.ptp.Ptp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatMaskTest {

    @Test
    fun `jpeg only by default`() {
        val mask = FormatMask()
        assertTrue(mask.accepts(Ptp.OF_JPEG))
        assertFalse(mask.accepts(Ptp.OF_RAW))
        assertFalse(mask.accepts(Ptp.OF_MOV))
    }

    @Test
    fun `folders are never files`() {
        assertFalse(FormatMask(jpeg = true, raw = true, video = true).accepts(Ptp.OF_ASSOCIATION))
    }

    @Test
    fun `an unknown still is treated as a jpeg`() {
        // Fuji hands out vendor format codes for some in-camera edits. Dropping those silently
        // would look like the importer losing photos.
        assertTrue(FormatMask(jpeg = true).accepts(0x3999))
        assertFalse(FormatMask(jpeg = false, raw = true).accepts(0x3999))
    }
}
