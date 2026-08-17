package com.gios.brightimport.fuji

import android.util.Log
import com.gios.brightimport.ptp.ObjectInfo
import com.gios.brightimport.ptp.Ptp
import com.gios.brightimport.ptp.PtpException
import com.gios.brightimport.ptp.PtpSession
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the camera told us about itself while we were setting up. */
data class FujiDevice(
    val name: String,
    var cameraState: Int = Fuji.State.WAIT_FOR_ACCESS,
    var selectedImgsMode: Int = -1,
    var objectCount: Int = -1,
    var getObjectVersion: Int = -1,
    var remoteImageViewVersion: Int = -1,
    var imageGetVersion: Int = -1,
    var remoteVersion: Int = -1,
)

/** Progress out of a long transfer, so the UI can show something honest. */
fun interface TransferProgress {
    fun onBytes(done: Long, total: Long)
}

/**
 * A conversation with a Fujifilm camera over its own Wi-Fi access point.
 *
 * This is a Kotlin port of the state machine in petabyt's `fuji.c` (Apache-2.0). The ordering in
 * [setup] is not stylistic: the camera drops the connection, or worse corrupts every subsequent
 * packet, if these calls happen out of sequence. In particular [configInitMode] must run before
 * [configVersion], which is a comment in the original and a bug in every reimplementation that
 * ignores it.
 *
 * The user has to press OK on the camera body twice — once to admit us, once to accept the client
 * mode — and both of those are blocking waits with no timeout worth setting.
 */
class FujiSession(
    private val ptp: PtpSession,
    private val host: String = Fuji.CAMERA_IP,
    private val clientName: String = "BrightImport",
) : AutoCloseable {

    lateinit var device: FujiDevice
        private set

    var transport: Fuji.Transport = Fuji.Transport.WirelessComm

    /** Kept open for the whole session: the camera closes the command socket if these go away. */
    private var eventSocket: Socket? = null
    private var videoSocket: Socket? = null

    private var openCaptureTransaction = 0

    /** Called with each human-readable step, for the connect screen. */
    var onStatus: (String) -> Unit = {}

    // ---------------------------------------------------------------- setup

    /**
     * Run the whole handshake. Returns once the camera is in a state where its card can be listed.
     */
    fun setup() {
        onStatus("Waiting on the camera — press OK")
        val name = initRequest()
        device = FujiDevice(name = name)
        onStatus("Connected to $name")

        if (transport == Fuji.Transport.WirelessComm) {
            // Fuji cameras need at least 50 ms after init before they will answer anything.
            // Skipping this reads as a camera that accepted the connection and then died.
            Thread.sleep(50)
        }

        ptp.openSession()

        onStatus("Press OK to allow access")
        waitForAccess()

        // Must come first. Out of order this does not fail loudly — it quietly corrupts the
        // packets of every file operation that follows.
        configInitMode()

        if (device.cameraState == Fuji.State.MULTIPLE_TRANSFER) {
            // The user chose photos on the body and is pushing them at us. Different protocol.
            return
        }

        onStatus("Setting up image viewer")
        configVersion()
        configDeviceInfo()

        if (device.remoteVersion != -1 && device.cameraState == Fuji.State.REMOTE_ACCESS) {
            setupRemoteMode()
            configImageViewer()
        }
        onStatus("Ready")
    }

    /**
     * The one non-standard packet in the whole protocol: 82 bytes of length, type, a constant
     * masquerading as a version, a fixed GUID, and our name in UTF-16 for the camera to display.
     */
    private fun initRequest(): String {
        val packet = ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(82)
        packet.putInt(Fuji.INIT_COMMAND_REQ)
        packet.putInt(Fuji.PROTOCOL_VERSION)
        packet.putInt(0x5D48A5AD)
        packet.putInt(0x0B7FB287)
        packet.putInt(0xD0DED5D3.toInt())
        packet.putInt(0x0)
        val name = clientName.take(26)
        for (c in name) packet.putShort(c.code.toShort())
        // Remaining bytes are already zero, which is the null terminator and the padding.

        // One retry, then one more after a beat. A camera that has just been switched into
        // transfer mode will ignore the first packet outright rather than refuse it.
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                ptp.writeRaw(packet.array())
                val response = ptp.readRawContainer()
                val b = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
                b.int // length
                val type = b.int
                if (type == Fuji.INIT_FAIL) throw PtpException(0, "camera refused the connection")
                if (type != Fuji.INIT_COMMAND_ACK) {
                    throw PtpException(0, "unexpected handshake type $type")
                }
                return readUtf16(response, 16)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "init attempt ${attempt + 1} failed", e)
                Thread.sleep(200)
            }
        }
        throw last ?: PtpException(0, "could not initialise the connection")
    }

    /**
     * Poll until the camera stops saying WAIT_FOR_ACCESS.
     *
     * There is no event for this. The user presses OK on the body, and the only way to learn that
     * is to keep asking.
     */
    private fun waitForAccess() {
        device.cameraState = Fuji.State.WAIT_FOR_ACCESS
        device.objectCount = -1
        device.selectedImgsMode = -1

        while (true) {
            pollEvents()
            if (device.cameraState != Fuji.State.WAIT_FOR_ACCESS) {
                if (device.selectedImgsMode != -1) return // multiple-transfer sends no count
                if (device.objectCount == -1) {
                    throw PtpException(0, "camera granted access but never said how many photos")
                }
                return
            }
            Thread.sleep(100)
        }
    }

    /**
     * Read the four version properties, work out which client mode this body wants, and ask for it.
     *
     * The `SetDevicePropValue` for ClientState is the second OK press: on newer bodies the camera
     * puts a Yes/No dialog on screen and does not answer the request until it is dismissed, so this
     * one call gets a four-minute timeout rather than three seconds.
     */
    private fun configInitMode() {
        device.getObjectVersion = ptp.getPropInt(Fuji.DPC_GET_OBJECT_VERSION)
        device.remoteImageViewVersion = ptp.getPropInt(Fuji.DPC_REMOTE_GET_OBJECT_VERSION)
        device.imageGetVersion = ptp.getPropInt(Fuji.DPC_IMAGE_GET_VERSION)
        device.remoteVersion = ptp.getPropInt(Fuji.DPC_REMOTE_VERSION)

        val mode = when {
            device.remoteVersion != -1 || device.cameraState == Fuji.State.REMOTE_ACCESS ->
                Fuji.ClientState.REMOTE_MODE
            device.cameraState == Fuji.State.MULTIPLE_TRANSFER -> Fuji.ClientState.VIEW_MULTIPLE
            device.cameraState == Fuji.State.FULL_ACCESS -> Fuji.ClientState.VIEW_ALL_IMGS
            device.cameraState == Fuji.State.PC_AUTO_SAVE -> Fuji.ClientState.OLD_REMOTE
            else -> Fuji.ClientState.VIEW_ALL_IMGS
        }
        Log.i(TAG, "camera state ${device.cameraState}, asking for client mode $mode")

        ptp.setPropInt16(
            Fuji.DPC_CLIENT_STATE,
            mode,
            timeoutSec = PtpSession.USER_CONFIRM_TIMEOUT_SEC,
        ).require()
        pollEvents()
    }

    /**
     * Echo the camera's own version numbers back at it.
     *
     * Reading a version and writing the identical value straight back looks like a no-op and is
     * not: it is how the client tells the body that it understands that revision. Skip it and file
     * operations return garbage.
     */
    private fun configVersion() {
        when {
            device.cameraState == Fuji.State.PC_AUTO_SAVE -> {
                val v = ptp.getPropInt(Fuji.DPC_AUTOSAVE_VERSION)
                ptp.setPropInt32(Fuji.DPC_AUTOSAVE_VERSION, v).require()
            }
            device.remoteVersion == -1 -> {
                val v = ptp.getPropInt(Fuji.DPC_GET_OBJECT_VERSION)
                ptp.setPropInt32(Fuji.DPC_GET_OBJECT_VERSION, v).require()
            }
            else -> {
                ptp.setPropInt32(Fuji.DPC_REMOTE_VERSION, Fuji.CAM_CONNECT_REMOTE_VER).require()
                // Object 0xfffffff1 holds something Fuji's client reads at this point. Nobody has
                // worked out what. Asking for it is harmless; not asking has not been tested.
                runCatching { getObjectInfo(0xFFFFFFF1.toInt()) }
            }
        }
    }

    private fun configDeviceInfo() {
        if (device.remoteVersion != -1 && device.cameraState != Fuji.State.PC_AUTO_SAVE) {
            runCatching { ptp.send(Fuji.OC_GET_DEVICE_INFO) }
        }
    }

    /**
     * Remote mode, which newer bodies demand before they will hand over so much as a thumbnail.
     *
     * `InitiateOpenCapture` here does not mean take a picture. It tells the camera to open its
     * event and liveview sockets, which we then have to connect to — even though this app never
     * reads a byte from either — before terminating the capture we never started.
     */
    private fun setupRemoteMode() {
        onStatus("Starting remote mode")
        openCaptureTransaction = ptp.transaction
        ptp.send(Ptp.OC_INITIATE_OPEN_CAPTURE, 0, 0)
        pollEvents()

        eventSocket = openSocket(Fuji.EVENT_PORT)
        videoSocket = openSocket(Fuji.LIVEVIEW_PORT)

        pollEvents()
        ptp.send(Ptp.OC_TERMINATE_OPEN_CAPTURE, openCaptureTransaction)
        pollEvents()
    }

    /** Quieten liveview down and switch the camera into its gallery mode. */
    private fun configImageViewer() {
        if (device.remoteImageViewVersion == -1) return
        pollEvents()
        ptp.setPropInt16(Fuji.DPC_CAMERA_STATE, Fuji.State.REMOTE_ACCESS)
        pollEvents()
        pollEvents()
        ptp.setPropInt16(Fuji.DPC_CLIENT_STATE, Fuji.ClientState.REMOTE_IMG_VIEW).require()
        pollEvents()
        device.remoteImageViewVersion = ptp.getPropInt(Fuji.DPC_REMOTE_GET_OBJECT_VERSION)
        // The X-S10 and X-H1 want 5 here regardless of what they just reported.
        ptp.setPropInt32(Fuji.DPC_REMOTE_GET_OBJECT_VERSION, 5).require()
        pollEvents()
    }

    private fun openSocket(port: Int): Socket =
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), 3000)
        }

    // ---------------------------------------------------------------- events

    /**
     * Read the event list once and fold it into [device].
     *
     * This is also a keepalive. The camera stops answering object operations if too many go by
     * without one of these, which is why it appears in the middle of otherwise unrelated routines.
     */
    fun pollEvents() {
        val data = try {
            ptp.getPropValue(Fuji.DPC_EVENTS_LIST)
        } catch (e: PtpException) {
            return // a camera that has nothing to say answers with an error code, not an error
        }
        if (data.size < 2) return
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.short.toInt() and 0xFFFF
        for (i in 0 until count) {
            if (b.remaining() < 6) break
            val code = b.short.toInt() and 0xFFFF
            val value = b.int
            when (code) {
                Fuji.DPC_SELECTED_IMGS_MODE -> device.selectedImgsMode = value
                Fuji.DPC_OBJECT_COUNT -> device.objectCount = value
                Fuji.DPC_CAMERA_STATE -> device.cameraState = value
            }
        }
    }

    // ---------------------------------------------------------------- the card

    /**
     * Handles for everything on the card.
     *
     * Over Wi-Fi the camera does not answer `GetObjectHandles` — the handles are simply 1 through
     * the object count it reported in an event. Asking properly returns an empty list, which reads
     * as an empty memory card.
     */
    fun objectHandles(): IntArray {
        if (device.objectCount <= 0) pollEvents()
        val n = device.objectCount
        if (n <= 0) return IntArray(0)
        return IntArray(n) { it + 1 }
    }

    /**
     * Ask about one file. Wrapped in the correct-file-size property, without which every size
     * comes back as 100 kB and every download stops a fraction of the way in.
     */
    fun getObjectInfo(handle: Int): ObjectInfo {
        pollEvents()
        ptp.setPropInt16(Fuji.DPC_ENABLE_CORRECT_FILE_SIZE, 1)
        val payload = ptp.require(Ptp.OC_GET_OBJECT_INFO, handle).payload
        return ObjectInfo.parse(handle, payload)
    }

    /**
     * A JPEG thumbnail, or null if the camera would not produce one.
     *
     * The `GetObjectInfo` call in front of this is the workaround for newer bodies: above remote
     * version 0x20006, `GetThumb` on a handle the camera has not been asked about blocks forever
     * and takes the session with it.
     */
    fun getThumbnail(handle: Int): ByteArray? {
        if (device.remoteVersion > Fuji.THUMB_NEEDS_OBJECT_INFO_ABOVE) {
            runCatching { ptp.require(Ptp.OC_GET_OBJECT_INFO, handle) }
        }
        val result = ptp.send(Ptp.OC_GET_THUMB, handle)
        if (!result.ok) return null
        // A handful of bytes is not a thumbnail, it is the camera declining politely.
        return result.payload.takeIf { it.size >= 100 }
    }

    /**
     * Pull one file down in 1 MB chunks.
     *
     * The chunk size is not tunable. Larger reads work on most bodies most of the time and then
     * stall permanently on one image, which is exactly the kind of bug you cannot reproduce on
     * demand, so this matches Fuji's own client byte for byte.
     */
    fun downloadObject(
        info: ObjectInfo,
        out: OutputStream,
        progress: TransferProgress? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        beginFileDownload()
        try {
            runCatching { ptp.getPropValue(Fuji.DPC_COMPRESSION_CUT_OFF) }
            var read = 0
            val total = info.compressedSize
            while (read < total) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val chunk = minOf(total - read, Fuji.MAX_PARTIAL_OBJECT)
                val result = ptp.require(
                    Ptp.OC_GET_PARTIAL_OBJECT,
                    info.handle,
                    read,
                    chunk,
                    timeoutSec = PARTIAL_TIMEOUT_SEC,
                )
                if (result.payload.isEmpty()) {
                    throw PtpException(0, "camera stopped sending at $read of $total bytes")
                }
                out.write(result.payload)
                read += result.payload.size
                progress?.onBytes(read.toLong(), total.toLong())
            }
            out.flush()
        } finally {
            if (transport == Fuji.Transport.WirelessComm) {
                runCatching { pollEvents() }
                runCatching { endFileDownload() }
            }
        }
    }

    private fun beginFileDownload() {
        pollEvents()
        ptp.setPropInt16(Fuji.DPC_ENABLE_CORRECT_FILE_SIZE, 1)
    }

    private fun endFileDownload() {
        ptp.setPropInt16(Fuji.DPC_ENABLE_CORRECT_FILE_SIZE, 0)
    }

    // ---------------------------------------------------------------- teardown

    /**
     * Say goodbye properly.
     *
     * The eight-byte farewell is Fuji's, not PTP's. Without it the camera holds the session open
     * and refuses the next connection until it is power-cycled, which looks like an app that only
     * works once.
     */
    override fun close() {
        runCatching { ptp.closeSession() }
        runCatching { ptp.writeRaw(byteArrayOf(8, 0, 0, 0, -1, -1, -1, -1)) }
        runCatching { eventSocket?.close() }
        runCatching { videoSocket?.close() }
        runCatching { ptp.close() }
    }

    companion object {
        private const val TAG = "FujiSession"

        /** A megabyte over camera Wi-Fi is not fast. */
        const val PARTIAL_TIMEOUT_SEC = 30

        fun connect(host: String = Fuji.CAMERA_IP, clientName: String = "BrightImport"): FujiSession {
            // A camera that has just been put into transfer mode ignores the first TCP connect
            // rather than refusing it, so one retry is normal rather than exceptional.
            val ptp = runCatching { PtpSession.connect(host, Fuji.CMD_PORT) }
                .getOrElse { PtpSession.connect(host, Fuji.CMD_PORT) }
            return FujiSession(ptp, host, clientName)
        }

        fun readUtf16(data: ByteArray, offset: Int): String {
            val sb = StringBuilder()
            var i = offset
            while (i + 1 < data.size) {
                val ch = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)
                if (ch == 0) break
                sb.append(ch.toChar())
                i += 2
            }
            return sb.toString()
        }
    }
}
