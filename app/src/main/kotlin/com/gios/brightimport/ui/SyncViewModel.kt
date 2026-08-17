package com.gios.brightimport.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightimport.camera.CameraBackend
import com.gios.brightimport.camera.CameraItem
import com.gios.brightimport.camera.FujiBackend
import com.gios.brightimport.camera.Make
import com.gios.brightimport.media.RollWriter
import com.gios.brightimport.net.CameraNetwork
import com.gios.brightimport.ptp.FormatMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the app is. One screen at a time, which is all a phone this size wants. */
sealed interface SyncState {
    data object PickCamera : SyncState
    data class Connecting(val make: Make, val status: String) : SyncState
    data class Browsing(
        val make: Make,
        val cameraName: String,
        val items: List<CameraItem>,
        val selected: Set<String> = emptySet(),
        val mask: FormatMask = FormatMask(),
    ) : SyncState

    data class Importing(
        val done: Int,
        val total: Int,
        val filename: String,
        val fileFraction: Float,
    ) : SyncState

    data class Finished(val imported: Int, val skipped: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

class SyncViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<SyncState>(SyncState.PickCamera)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val network = CameraNetwork(app)
    private val roll = RollWriter(app)
    private var backend: CameraBackend? = null
    private var job: Job? = null

    /** Set when the user backs out of a transfer. Checked between chunks, not between files. */
    @Volatile
    private var cancelled = false

    /** Thumbnails, kept in memory for the life of the browse. */
    private val thumbs = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ByteArray>> = thumbs.asStateFlow()

    fun connect(make: Make, passphrase: String?) {
        job?.cancel()
        cancelled = false
        _state.value = SyncState.Connecting(make, "Looking for the camera")
        job = viewModelScope.launch {
            try {
                network.connect(make.ssidPrefix, passphrase)
                val b = when (make) {
                    Make.Fujifilm -> FujiBackend()
                    // Ricoh and Sony land here once their backends exist. Until then the picker
                    // does not offer them, so this is unreachable rather than broken.
                    else -> error("${make.label} is not supported yet")
                }
                backend = b
                withContext(Dispatchers.IO) {
                    b.connect { status ->
                        _state.update { current ->
                            if (current is SyncState.Connecting) current.copy(status = status)
                            else current
                        }
                    }
                }
                val items = withContext(Dispatchers.IO) { b.list() }
                _state.value = SyncState.Browsing(make, make.label, items)
                loadThumbnails(items)
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                fail(e)
            }
        }
    }

    /**
     * Fetch thumbnails one at a time in the background.
     *
     * Serial, not parallel, and not negotiable: the camera has one command socket and a second
     * concurrent request does not fail, it makes the body stop answering altogether.
     */
    private fun loadThumbnails(items: List<CameraItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (item in items) {
                if (cancelled) return@launch
                val bytes = runCatching { backend?.thumbnail(item) }.getOrNull() ?: continue
                thumbs.update { it + (item.id to bytes) }
            }
        }
    }

    fun toggle(id: String) {
        _state.update { s ->
            if (s !is SyncState.Browsing) s
            else s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
        }
    }

    fun selectAll() {
        _state.update { s ->
            if (s !is SyncState.Browsing) s
            else s.copy(selected = s.items.filter { s.mask.accepts(it.format) }.map { it.id }.toSet())
        }
    }

    fun setMask(mask: FormatMask) {
        _state.update { s -> if (s is SyncState.Browsing) s.copy(mask = mask) else s }
    }

    fun importSelected() {
        val browsing = _state.value as? SyncState.Browsing ?: return
        val b = backend ?: return
        val chosen = browsing.items.filter { it.id in browsing.selected && browsing.mask.accepts(it.format) }
        if (chosen.isEmpty()) return

        cancelled = false
        job?.cancel()
        job = viewModelScope.launch {
            var imported = 0
            var skipped = 0
            try {
                withContext(Dispatchers.IO) {
                    chosen.forEachIndexed { index, item ->
                        if (cancelled) return@withContext
                        _state.value = SyncState.Importing(index, chosen.size, item.filename, 0f)
                        val pending = roll.begin(item)
                        if (pending == null) {
                            skipped++
                            return@forEachIndexed
                        }
                        try {
                            b.download(
                                item = item,
                                out = pending.stream,
                                progress = { done, total ->
                                    val f = if (total > 0) done.toFloat() / total else 0f
                                    _state.value =
                                        SyncState.Importing(index, chosen.size, item.filename, f)
                                },
                                isCancelled = { cancelled },
                            )
                            pending.commit()
                            imported++
                        } catch (e: Exception) {
                            // A partial file is worse than no file: it would look imported and be
                            // skipped forever after.
                            pending.abandon()
                            if (!cancelled) throw e
                        }
                    }
                }
                _state.value = SyncState.Finished(imported, skipped)
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                fail(e)
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun disconnect() {
        cancelled = true
        job?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backend?.disconnect() }
            backend = null
            network.release()
        }
        thumbs.value = emptyMap()
        _state.value = SyncState.PickCamera
    }

    private fun fail(e: Exception) {
        runCatching { backend?.disconnect() }
        backend = null
        network.release()
        _state.value = SyncState.Failed(e.message ?: e::class.java.simpleName)
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SyncViewModel"
    }
}
