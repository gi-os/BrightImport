package com.gios.brightimport.ptp

/**
 * Picture Transfer Protocol constants, and the container format Fujifilm speaks over TCP.
 *
 * The wire format here is deliberately **not** ISO PTP/IP. Fuji forked their USB MTP stack and
 * pointed it at a socket, so after the one non-standard handshake in [FujiSession] every packet
 * on the wire is a USB bulk container:
 *
 * ```
 *   u32 length      // whole packet including this field
 *   u16 type        // 1 command, 2 data, 3 response, 4 event
 *   u16 code        // operation or response code
 *   u32 transaction
 *   u32 params[]    // command packets only; data packets put payload here instead
 * ```
 *
 * Twelve-byte header either way, which is the only thing worth memorising.
 */
object Ptp {
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    const val HEADER = 12

    // Operations
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_GET_OBJECT_HANDLES = 0x1007
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_GET_THUMB = 0x100A
    const val OC_DELETE_OBJECT = 0x100B
    const val OC_GET_DEVICE_PROP_VALUE = 0x1015
    const val OC_SET_DEVICE_PROP_VALUE = 0x1016
    const val OC_TERMINATE_OPEN_CAPTURE = 0x1018
    const val OC_GET_PARTIAL_OBJECT = 0x101B
    const val OC_INITIATE_OPEN_CAPTURE = 0x101C

    // Responses
    const val RC_OK = 0x2001
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPENED = 0x201E

    // Object formats
    const val OF_ASSOCIATION = 0x3001
    const val OF_MOV = 0x300D
    const val OF_JPEG = 0x3801
    const val OF_RAW = 0xB103

    fun rcName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_SESSION_ALREADY_OPENED -> "SessionAlreadyOpened"
        else -> "0x%04x".format(code)
    }
}

/** Which kinds of file an import should actually pull down. */
data class FormatMask(
    val jpeg: Boolean = true,
    val raw: Boolean = false,
    val video: Boolean = false,
) {
    fun accepts(objectFormat: Int): Boolean = when (objectFormat) {
        Ptp.OF_JPEG -> jpeg
        Ptp.OF_RAW -> raw
        Ptp.OF_MOV -> video
        // Anything the camera reports as an unknown still is treated as a JPEG: Fuji hands out
        // vendor format codes for some in-camera edits, and dropping those silently would look
        // like the importer losing photos.
        Ptp.OF_ASSOCIATION -> false
        else -> jpeg
    }
}

/** A response code the camera returned for an operation we asked it to do. */
class PtpException(val code: Int, message: String? = null) :
    Exception(message ?: "camera returned ${Ptp.rcName(code)}")
