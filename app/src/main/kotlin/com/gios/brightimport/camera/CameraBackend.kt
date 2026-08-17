package com.gios.brightimport.camera

import com.gios.brightimport.net.CameraNetwork
import java.io.OutputStream

/** One file on a camera, whatever protocol it came from. */
data class CameraItem(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val takenAtMillis: Long?,
    val format: Int,
    val mimeType: String,
)

/** How to progress a transfer, in bytes. */
fun interface Progress {
    fun onBytes(done: Long, total: Long)
}

/**
 * What every camera has to be able to do.
 *
 * Three protocols with nothing in common — Fuji's forked MTP over TCP, Ricoh's REST, Sony's SOAP —
 * reduce to the same four verbs, and keeping the UI behind this interface is what stops the
 * gallery growing a branch per manufacturer.
 */
interface CameraBackend {
    val make: Make

    /** Prefix of the SSID this body advertises, for the network picker. */
    val ssidPrefix: String

    /** Connect and get to a state where the card can be listed. Blocking. */
    fun connect(onStatus: (String) -> Unit)

    /** Everything on the card, newest first where the camera makes that knowable. */
    fun list(): List<CameraItem>

    /** A JPEG thumbnail, or null when the camera declines to make one. */
    fun thumbnail(item: CameraItem): ByteArray?

    /** Stream one file out. */
    fun download(
        item: CameraItem,
        out: OutputStream,
        progress: Progress?,
        isCancelled: () -> Boolean,
    )

    fun disconnect()
}

enum class Make(val label: String, val ssidPrefix: String, val note: String) {
    /**
     * Set the body to 'WIRELESS COMMUNICATION' from the playback menu, or 'CONNECTION SETTING'
     * on newer ones. The X-Pro3 lands in remote-access state and needs two OK presses.
     */
    Fujifilm("Fujifilm", CameraNetwork.SSID_FUJI, "Playback menu → wireless communication"),

    /** Wi-Fi on, then the app talks HTTP to 192.168.0.1. No confirmation on the body. */
    Ricoh("Ricoh GR", CameraNetwork.SSID_RICOH, "Turn wireless on in the camera menu"),

    /** 'Send to Smartphone' puts the RX100 into the mode this app can see. */
    Sony("Sony RX100", CameraNetwork.SSID_SONY, "Playback → send to smartphone"),
}
