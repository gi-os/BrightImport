package com.gios.brightimport.ptp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the camera said back: a response code, its parameters, and any data phase that preceded it. */
class PtpResult(
    val code: Int,
    val params: IntArray,
    val payload: ByteArray,
) {
    val ok: Boolean get() = code == Ptp.RC_OK
    fun require(): PtpResult = if (ok) this else throw PtpException(code)
}

/**
 * One PTP conversation over one TCP socket.
 *
 * Everything is blocking and single-threaded on purpose. A Fuji camera cannot service two
 * transactions at once — interleave them and it stops answering entirely rather than erroring —
 * so callers serialise through this object and run it on a background dispatcher.
 */
class PtpSession(
    /**
     * Null in tests only. The socket is here purely to move the read timeout per operation —
     * everything else goes through the streams, which is what lets the framing be exercised on
     * the JVM against a recorded byte stream instead of against a camera.
     */
    private val socket: Socket?,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    /**
     * Transaction ID, incremented after every completed operation.
     *
     * `OpenSession` resets it to zero, which is not a nicety: the camera checks it, and a session
     * opened on any other transaction ID is refused.
     */
    var transaction: Int = 0

    var sessionId: Int = 0
        private set

    /**
     * The last data payload the camera sent, kept so a caller can read it without a copy.
     * [send] returns a slice of it; do not hold onto it across calls.
     */
    private var scratch = ByteArray(0)

    // ---------------------------------------------------------------- low level

    private fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = input.read(out, read, n - read)
            if (got < 0) throw EOFException("camera closed the connection after $read of $n bytes")
            read += got
        }
        return out
    }

    /** Write a whole packet. Fuji is happier with one write per packet than with a trickle. */
    fun writeRaw(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /**
     * Read one container. The length field is the first four bytes and covers itself, which is
     * what lets us frame a stream that has no other delimiter.
     */
    private fun readContainer(): ByteArray {
        val head = readFully(4)
        val length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
        // Eight, not twelve: Fuji's handshake failure and goodbye packets are shorter than a
        // bulk container, and rejecting them here would turn a clear refusal into a framing error.
        if (length < 8 || length > MAX_PACKET) {
            throw PtpException(0, "implausible packet length $length — the stream is out of frame")
        }
        val rest = readFully(length - 4)
        return head + rest
    }

    /** Raw read used only by the vendor handshake, which is not a container. */
    fun readRawContainer(): ByteArray = readContainer()

    // ---------------------------------------------------------------- operations

    /**
     * Run one operation and return its result.
     *
     * @param dataOut a data phase to send to the camera, or null for a read or a bare command.
     * @param timeoutSec how long to wait for the response. Three seconds covers almost everything;
     *   setting `ClientState` is the exception, because the camera does not answer that one until
     *   a human has pressed OK on its screen.
     */
    fun send(
        code: Int,
        vararg params: Int,
        dataOut: ByteArray? = null,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    ): PtpResult {
        socket?.soTimeout = timeoutSec * 1000

        writeRaw(commandPacket(code, params))
        if (dataOut != null) writeRaw(dataPacket(code, dataOut))

        var payload = ByteArray(0)
        while (true) {
            val packet = readContainer()
            val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val length = buf.int
            val type = buf.short.toInt() and 0xFFFF
            val pcode = buf.short.toInt() and 0xFFFF
            buf.int // transaction

            when (type) {
                Ptp.TYPE_DATA -> {
                    payload = packet.copyOfRange(Ptp.HEADER, length)
                }
                Ptp.TYPE_RESPONSE -> {
                    val n = (length - Ptp.HEADER) / 4
                    val rparams = IntArray(n) { buf.int }
                    transaction++
                    return PtpResult(pcode, rparams, payload)
                }
                Ptp.TYPE_EVENT -> {
                    // Fuji pushes events down the command socket occasionally. Skip them; the
                    // real event stream is polled through EventsList.
                }
                else -> throw PtpException(0, "unexpected container type $type")
            }
        }
    }

    /** [send] that throws unless the camera said OK. */
    fun require(code: Int, vararg params: Int, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): PtpResult =
        send(code, *params, timeoutSec = timeoutSec).require()

    fun openSession(): PtpResult {
        sessionId++
        transaction = 0
        val r = send(Ptp.OC_OPEN_SESSION, sessionId)
        if (!r.ok && r.code != Ptp.RC_SESSION_ALREADY_OPENED) throw PtpException(r.code)
        return r
    }

    fun closeSession(): PtpResult = send(Ptp.OC_CLOSE_SESSION)

    fun getPropValue(prop: Int, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): ByteArray =
        require(Ptp.OC_GET_DEVICE_PROP_VALUE, prop, timeoutSec = timeoutSec).payload

    /** Property values are little-endian integers of whatever width the camera felt like. */
    fun getPropInt(prop: Int): Int = parsePropValue(getPropValue(prop))

    fun setPropValue(prop: Int, data: ByteArray, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): PtpResult =
        send(Ptp.OC_SET_DEVICE_PROP_VALUE, prop, dataOut = data, timeoutSec = timeoutSec)

    fun setPropInt16(prop: Int, value: Int, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): PtpResult =
        setPropValue(prop, le16(value), timeoutSec)

    fun setPropInt32(prop: Int, value: Int, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): PtpResult =
        setPropValue(prop, le32(value), timeoutSec)

    override fun close() {
        runCatching { socket?.close() }
    }

    // ---------------------------------------------------------------- packets

    private fun commandPacket(code: Int, params: IntArray): ByteArray {
        val size = Ptp.HEADER + 4 * params.size
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(size)
        b.putShort(Ptp.TYPE_COMMAND.toShort())
        b.putShort(code.toShort())
        b.putInt(transaction)
        params.forEach { b.putInt(it) }
        return b.array()
    }

    private fun dataPacket(code: Int, data: ByteArray): ByteArray {
        val size = Ptp.HEADER + data.size
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(size)
        b.putShort(Ptp.TYPE_DATA.toShort())
        b.putShort(code.toShort())
        b.putInt(transaction)
        b.put(data)
        return b.array()
    }

    companion object {
        /**
         * Fuji cameras are slow. Three seconds is what Fuji's own client waits, and shortening it
         * turns a busy camera into a disconnect.
         */
        const val DEFAULT_TIMEOUT_SEC = 3

        /** Long enough for an operation that blocks on the user pressing OK on the camera. */
        const val USER_CONFIRM_TIMEOUT_SEC = 255

        /** A guard against a desynchronised stream turning into a 2 GB allocation. */
        const val MAX_PACKET = 32 * 1024 * 1024

        fun connect(host: String, port: Int, connectTimeoutSec: Int = 5): PtpSession {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutSec * 1000)
            socket.soTimeout = DEFAULT_TIMEOUT_SEC * 1000
            return PtpSession(socket, socket.getInputStream().buffered(64 * 1024), socket.getOutputStream())
        }

        fun le16(v: Int): ByteArray =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

        fun le32(v: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

        /**
         * Read a property value of unknown width.
         *
         * The camera does not tell us the type in the reply, only in the device info blob, so the
         * width of the payload is the type. A short read is not an error — some Fuji properties
         * legitimately come back as a single byte.
         */
        fun parsePropValue(data: ByteArray): Int {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return when {
                data.size >= 4 -> b.int
                data.size >= 2 -> b.short.toInt() and 0xFFFF
                data.size >= 1 -> data[0].toInt() and 0xFF
                else -> -1
            }
        }
    }
}
