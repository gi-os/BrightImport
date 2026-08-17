package com.gios.brightimport.camera

import com.gios.brightimport.fuji.FujiSession
import com.gios.brightimport.ptp.ObjectInfo
import java.io.OutputStream

/**
 * [CameraBackend] over [FujiSession].
 *
 * The listing is where Fuji is least like anything else: over Wi-Fi the camera will not answer
 * `GetObjectHandles`, so the handles are 1..n against a count that only ever arrives in an event.
 * Everything else is a thin wrapper.
 */
class FujiBackend : CameraBackend {

    override val make = Make.Fujifilm
    override val ssidPrefix = Make.Fujifilm.ssidPrefix

    private var session: FujiSession? = null
    private val infoCache = mutableMapOf<Int, ObjectInfo>()

    override fun connect(onStatus: (String) -> Unit) {
        val s = FujiSession.connect()
        s.onStatus = onStatus
        s.setup()
        session = s
    }

    override fun list(): List<CameraItem> {
        val s = requireNotNull(session) { "not connected" }
        val handles = s.objectHandles()
        val out = ArrayList<CameraItem>(handles.size)
        for (handle in handles) {
            val info = runCatching { s.getObjectInfo(handle) }.getOrNull() ?: continue
            if (info.isFolder) continue
            infoCache[handle] = info
            out += CameraItem(
                id = handle.toString(),
                filename = info.filename,
                sizeBytes = info.compressedSize.toLong(),
                takenAtMillis = info.takenAtMillis(),
                format = info.format,
                mimeType = info.mimeType,
            )
        }
        // The camera hands these out oldest-first. A roll reads newest-first.
        return out.asReversed()
    }

    override fun thumbnail(item: CameraItem): ByteArray? =
        session?.getThumbnail(item.id.toInt())

    override fun download(
        item: CameraItem,
        out: OutputStream,
        progress: Progress?,
        isCancelled: () -> Boolean,
    ) {
        val s = requireNotNull(session) { "not connected" }
        val handle = item.id.toInt()
        val info = infoCache[handle] ?: s.getObjectInfo(handle)
        s.downloadObject(
            info = info,
            out = out,
            progress = { done, total -> progress?.onBytes(done, total) },
            isCancelled = isCancelled,
        )
    }

    override fun disconnect() {
        session?.close()
        session = null
        infoCache.clear()
    }
}
