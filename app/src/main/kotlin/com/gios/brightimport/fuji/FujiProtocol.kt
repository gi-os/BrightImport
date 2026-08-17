package com.gios.brightimport.fuji

/**
 * Fujifilm's PTP vendor extensions.
 *
 * Transcribed from petabyt's `fujiptp.h` (Apache-2.0), which is the only description of this
 * protocol that exists — Fuji publishes an SDK that covers fifteen recent bodies and nothing else.
 * The magic numbers are magic; none of them are derivable and several of them are load-bearing.
 */
object Fuji {
    /** Sent in the init packet. Not a version number in any readable sense, just a constant. */
    const val PROTOCOL_VERSION = 0x8F53E4F2.toInt()

    const val CMD_PORT = 55740
    const val EVENT_PORT = 55741
    const val LIVEVIEW_PORT = 55742

    /** Every Fuji camera is its own access point at this address. */
    const val CAMERA_IP = "192.168.0.1"

    /** PTP/IP packet types, used only by the handshake. */
    const val INIT_COMMAND_REQ = 0x1
    const val INIT_COMMAND_ACK = 0x2
    const val INIT_FAIL = 0x5

    /**
     * Transfers over 1 MB work right up until they don't — one body will stall mid-image and never
     * recover. Fuji's own client chunks at exactly this, so we do too.
     */
    const val MAX_PARTIAL_OBJECT = 0x100000

    /** Device property codes. */
    const val DPC_EVENTS_LIST = 0xD212
    const val DPC_SELECTED_IMGS_MODE = 0xD220
    const val DPC_OBJECT_COUNT = 0xD222
    const val DPC_COMPRESS_SMALL = 0xD226

    /**
     * With this at its default of 0, `ObjectInfo.compressedSize` comes back pinned at 100 kB for
     * every file on the card. Set it to 1 before asking about sizes and back to 0 afterwards —
     * leaving it on during setup makes the thumbnail grid crawl.
     */
    const val DPC_ENABLE_CORRECT_FILE_SIZE = 0xD227

    const val DPC_COMPRESSION_CUT_OFF = 0xD235
    const val DPC_STORAGE_ID = 0xD244
    const val DPC_CAMERA_STATE = 0xDF00
    const val DPC_CLIENT_STATE = 0xDF01
    const val DPC_IMAGE_GET_VERSION = 0xDF21
    const val DPC_GET_OBJECT_VERSION = 0xDF22
    const val DPC_AUTOSAVE_VERSION = 0xDF23
    const val DPC_REMOTE_VERSION = 0xDF24
    const val DPC_REMOTE_GET_OBJECT_VERSION = 0xDF25

    /** Operation codes. */
    const val OC_GET_DEVICE_INFO = 0x902B

    /**
     * What Fuji's own Camera Connect reports as its remote version. Bodies advertise anything from
     * 0x20004 up; answering with the highest is what the official client does.
     */
    const val CAM_CONNECT_REMOTE_VER = 0x2000C

    /**
     * Above this remote version, `GetThumb` blocks forever unless `GetObjectInfo` was called for
     * the same handle first. It is not documented anywhere and it is not an error you can catch —
     * the socket simply goes quiet.
     */
    const val THUMB_NEEDS_OBJECT_INFO_ABOVE = 0x20006

    /** How the camera got here. Only the wireless ones matter to this app for now. */
    enum class Transport(val id: Int) {
        AutoSave(1),
        WirelessTether(2),
        WirelessComm(3),
        UsbCardReader(4),
        UsbTetherShoot(5),
        RawConv(6),
        Usb(7),
        Webcam(8),
        MovieShoot(9),
    }

    /** Values of [DPC_CAMERA_STATE] — what the camera thinks is going on. */
    object State {
        /** Poll until this changes. The user has not pressed OK yet. */
        const val WAIT_FOR_ACCESS = 0

        /** The user picked specific photos on the camera and is pushing them at us. */
        const val MULTIPLE_TRANSFER = 1

        /** Ordinary full access to the card. */
        const val FULL_ACCESS = 2

        /** 'PC AUTO SAVE' from the playback menu. */
        const val PC_AUTO_SAVE = 3

        /** Full access plus liveview. Newer bodies, including the X-Pro3, land here. */
        const val REMOTE_ACCESS = 6
    }

    /** Values we write to [DPC_CLIENT_STATE] — what we are asking to be. */
    object ClientState {
        const val VIEW_MULTIPLE = 1
        const val VIEW_ALL_IMGS = 2
        const val OLD_REMOTE = 3
        const val REMOTE_MODE = 5
        const val CAMERA_ERR = 7
        const val REMOTE_IMG_VIEW = 11
    }
}
