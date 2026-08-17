package com.gios.brightimport

import com.gios.brightimport.ptp.Ptp
import com.gios.brightimport.ptp.PtpSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The container format, exercised against bytes rather than against a camera.
 *
 * Fuji speaks USB-style PTP over TCP: a 12-byte header where type and code are 16-bit, not the
 * ISO PTP/IP layout where they are 32-bit. Getting that wrong produces packets a camera silently
 * ignores, which is indistinguishable from a camera that has gone to sleep.
 */
class PtpFramingTest {

    private fun container(type: Int, code: Int, transaction: Int, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(Ptp.HEADER + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(Ptp.HEADER + body.size)
        b.putShort(type.toShort())
        b.putShort(code.toShort())
        b.putInt(transaction)
        b.put(body)
        return b.array()
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    @Test
    fun `command packet is twelve bytes plus four per parameter`() {
        val out = ByteArrayOutputStream()
        val session = PtpSession(
            null,
            ByteArrayInputStream(container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 0, ByteArray(0))),
            out,
        )
        session.send(Ptp.OC_GET_OBJECT_INFO, 42)

        val sent = out.toByteArray()
        assertEquals(Ptp.HEADER + 4, sent.size)
        val b = ByteBuffer.wrap(sent).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Ptp.HEADER + 4, b.int)
        assertEquals(Ptp.TYPE_COMMAND, b.short.toInt())
        assertEquals(Ptp.OC_GET_OBJECT_INFO, b.short.toInt())
        assertEquals(0, b.int)   // transaction
        assertEquals(42, b.int)  // the handle
    }

    @Test
    fun `a data phase is collected and returned with the response`() {
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        val stream = container(Ptp.TYPE_DATA, Ptp.OC_GET_THUMB, 1, payload) +
            container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 1, ByteArray(0))

        val session = PtpSession(null, ByteArrayInputStream(stream), ByteArrayOutputStream())
        val result = session.send(Ptp.OC_GET_THUMB, 5)

        assertTrue(result.ok)
        assertArrayEquals(payload, result.payload)
    }

    @Test
    fun `an event on the command socket is skipped, not mistaken for a response`() {
        // Fuji pushes these down the command socket occasionally. Treating one as the response
        // desynchronises the stream and every later operation reads the previous one's tail.
        val stream = container(Ptp.TYPE_EVENT, 0xC001, 1, le32(0)) +
            container(Ptp.TYPE_DATA, Ptp.OC_GET_DEVICE_PROP_VALUE, 1, le32(6)) +
            container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 1, ByteArray(0))

        val session = PtpSession(null, ByteArrayInputStream(stream), ByteArrayOutputStream())
        val result = session.send(Ptp.OC_GET_DEVICE_PROP_VALUE, 0xDF00)

        assertTrue(result.ok)
        assertEquals(6, PtpSession.parsePropValue(result.payload))
    }

    @Test
    fun `transaction id advances once per operation`() {
        val stream = container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 0, ByteArray(0)) +
            container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 1, ByteArray(0))
        val session = PtpSession(null, ByteArrayInputStream(stream), ByteArrayOutputStream())
        assertEquals(0, session.transaction)
        session.send(Ptp.OC_CLOSE_SESSION)
        assertEquals(1, session.transaction)
        session.send(Ptp.OC_CLOSE_SESSION)
        assertEquals(2, session.transaction)
    }

    @Test
    fun `open session resets the transaction id to zero`() {
        // Not a nicety. The camera checks it, and refuses a session opened on anything else.
        val stream = container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 0, ByteArray(0)) +
            container(Ptp.TYPE_RESPONSE, Ptp.RC_OK, 0, ByteArray(0))
        val out = ByteArrayOutputStream()
        val session = PtpSession(null, ByteArrayInputStream(stream), out)
        session.send(Ptp.OC_CLOSE_SESSION)      // pushes transaction to 1
        out.reset()
        session.openSession()

        val sent = out.toByteArray()
        val b = ByteBuffer.wrap(sent).order(ByteOrder.LITTLE_ENDIAN)
        b.int; b.short; b.short
        assertEquals("OpenSession must go out on transaction 0", 0, b.int)
        assertEquals(1, b.int) // session id
    }

    @Test
    fun `an already-open session is not treated as a failure`() {
        val stream = container(Ptp.TYPE_RESPONSE, Ptp.RC_SESSION_ALREADY_OPENED, 0, ByteArray(0))
        val session = PtpSession(null, ByteArrayInputStream(stream), ByteArrayOutputStream())
        session.openSession() // must not throw
    }

    @Test
    fun `property values are read at whatever width the camera used`() {
        assertEquals(6, PtpSession.parsePropValue(byteArrayOf(6)))
        assertEquals(0x2000C, PtpSession.parsePropValue(le32(0x2000C)))
        assertEquals(-1, PtpSession.parsePropValue(ByteArray(0)))
    }
}
