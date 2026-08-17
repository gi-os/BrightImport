package com.gios.brightimport.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.gios.brightimport.camera.CameraItem
import com.gios.brightimport.ptp.Ptp
import java.io.OutputStream

/**
 * Where imported photos land.
 *
 * Roll shows everything under `DCIM/`, so writing here is all the integration there is — no
 * shared database, no provider, no coupling between the two apps at all. A photo pulled off the
 * X-Pro3 appears in the roll above the viewfinder next to the ones the phone took, and in every
 * other gallery on the device, because it is a real file in the real camera roll.
 *
 * `IS_PENDING` is what keeps a half-transferred RAW out of those galleries while the bytes are
 * still arriving — which matters more here than it does for a phone photo, because a 50 MB file
 * over camera Wi-Fi is a slow minute, not a blink.
 */
class RollWriter(private val context: Context) {

    private val images: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val videos: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Reserve a row and open it for writing.
     *
     * @return null when a file of this name is already in the folder — the importer treats that as
     *   "already have it" rather than as an error, which is what makes re-running a sync cheap.
     */
    fun begin(item: CameraItem): Pending? {
        if (contains(item.filename)) {
            Log.i(TAG, "${item.filename} is already in the roll")
            return null
        }
        val isVideo = item.format == Ptp.OF_MOV
        val collection = if (isVideo) videos else images
        val nameColumn = MediaStore.MediaColumns.DISPLAY_NAME
        val taken = item.takenAtMillis

        val values = ContentValues().apply {
            put(nameColumn, item.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (taken != null) {
                // Seconds for DATE_TAKEN's sibling column, milliseconds for DATE_TAKEN itself.
                // Getting these two the wrong way round sorts every import to 1970 or to the year
                // 57000, and both look like the importer inventing dates.
                put(MediaStore.MediaColumns.DATE_ADDED, taken / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, taken / 1000)
                if (isVideo) {
                    put(MediaStore.Video.Media.DATE_TAKEN, taken)
                } else {
                    put(MediaStore.Images.Media.DATE_TAKEN, taken)
                }
            }
        }

        val uri = context.contentResolver.insert(collection, values) ?: return null
        val stream = context.contentResolver.openOutputStream(uri) ?: run {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        return Pending(uri, stream)
    }

    /** An in-flight import. Always finished through [Pending.commit] or [Pending.abandon]. */
    inner class Pending(val uri: Uri, val stream: OutputStream) {
        fun commit() {
            runCatching { stream.close() }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }

        /**
         * Throw the partial file away.
         *
         * A cancelled or failed transfer must not leave a truncated JPEG behind: it would clear
         * its pending flag on nothing and show up in the roll as a grey rectangle, and the next
         * sync would skip it because the name already exists.
         */
        fun abandon() {
            runCatching { stream.close() }
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /** Has this filename already been imported? Names from a camera are unique per card. */
    fun contains(filename: String): Boolean {
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(filename, "$FOLDER%")
        for (collection in listOf(images, videos)) {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null,
            )?.use { if (it.count > 0) return true }
        }
        return false
    }

    companion object {
        private const val TAG = "RollWriter"

        /**
         * Its own folder under DCIM rather than `DCIM/Camera`. Roll's "Camera roll" scope is
         * everything under DCIM, so these show up there either way, and keeping them separate
         * means an import can be deleted in bulk without touching phone photos.
         */
        const val FOLDER = "DCIM/BrightImport"
    }
}
