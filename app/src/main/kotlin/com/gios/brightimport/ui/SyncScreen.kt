package com.gios.brightimport.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.brightimport.camera.CameraItem
import com.gios.brightimport.camera.Make
import com.gios.brightimport.ptp.FormatMask
import com.gios.brightimport.ui.theme.LightText
import com.gios.brightimport.ui.theme.LightTextVariant

/**
 * The whole app, in four states.
 *
 * Deliberately one screen deep. On a panel this size a nested navigation graph costs more than it
 * buys, and there is nothing here that is not either "which camera", "which photos", or "wait".
 */
@Composable
fun SyncScreen(vm: SyncViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        when (val s = state) {
            is SyncState.PickCamera -> PickCamera(onPick = { make, pass -> vm.connect(make, pass) })

            is SyncState.Connecting -> Centered {
                LightText(s.make.label, LightTextVariant.Heading)
                Spacer(Modifier.height(8.dp))
                LightText(s.status, LightTextVariant.Copy)
                Spacer(Modifier.height(24.dp))
                LightText(s.make.note, LightTextVariant.Detail, lighten = true)
                Spacer(Modifier.height(24.dp))
                Action("CANCEL") { vm.disconnect() }
            }

            is SyncState.Browsing -> Browsing(
                state = s,
                thumbs = thumbs,
                onToggle = vm::toggle,
                onSelectAll = vm::selectAll,
                onMask = vm::setMask,
                onImport = vm::importSelected,
                onBack = vm::disconnect,
            )

            is SyncState.Importing -> Centered {
                LightText("${s.done + 1} of ${s.total}", LightTextVariant.Heading)
                Spacer(Modifier.height(8.dp))
                LightText(s.filename, LightTextVariant.Copy)
                Spacer(Modifier.height(16.dp))
                Bar(s.fileFraction)
                Spacer(Modifier.height(24.dp))
                Action("STOP") { vm.cancel() }
            }

            is SyncState.Finished -> Centered {
                LightText("${s.imported} imported", LightTextVariant.Heading)
                if (s.skipped > 0) {
                    Spacer(Modifier.height(8.dp))
                    LightText("${s.skipped} already in the roll", LightTextVariant.Detail, lighten = true)
                }
                Spacer(Modifier.height(24.dp))
                Action("DONE") { vm.disconnect() }
            }

            is SyncState.Failed -> Centered {
                LightText("Didn't work", LightTextVariant.Heading)
                Spacer(Modifier.height(8.dp))
                LightText(s.message, LightTextVariant.Detail, lighten = true)
                Spacer(Modifier.height(24.dp))
                Action("BACK") { vm.disconnect() }
            }
        }
    }
}

@Composable
private fun PickCamera(onPick: (Make, String?) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        LightText("CAMERA", LightTextVariant.Heading)
        Spacer(Modifier.height(16.dp))
        // Only the bodies with a backend are offered. A picker entry that leads to a crash is
        // worse than one that is not there.
        for (make in listOf(Make.Fujifilm)) {
            Action(make.label.uppercase()) { onPick(make, null) }
            Spacer(Modifier.height(4.dp))
            LightText(make.note, LightTextVariant.Detail, lighten = true)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Browsing(
    state: SyncState.Browsing,
    thumbs: Map<String, ByteArray>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onMask: (FormatMask) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LightText("BACK", LightTextVariant.Button, modifier = Modifier.clickable { onBack() })
            LightText("${state.selected.size}/${state.items.size}", LightTextVariant.Button)
            LightText("ALL", LightTextVariant.Button, modifier = Modifier.clickable { onSelectAll() })
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Toggle("JPEG", state.mask.jpeg) { onMask(state.mask.copy(jpeg = it)) }
            Toggle("RAW", state.mask.raw) { onMask(state.mask.copy(raw = it)) }
            Toggle("VIDEO", state.mask.video) { onMask(state.mask.copy(video = it)) }
        }
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                Cell(
                    item = item,
                    thumb = thumbs[item.id],
                    selected = item.id in state.selected,
                    dimmed = !state.mask.accepts(item.format),
                    onClick = { onToggle(item.id) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.selected.isNotEmpty()) Action("IMPORT ${state.selected.size}") { onImport() }
    }
}

@Composable
private fun Cell(
    item: CameraItem,
    thumb: ByteArray?,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = remember(thumb) {
        thumb?.let {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Color(0xFF111111))
            .then(if (selected) Modifier.border(2.dp, Color.White) else Modifier)
            .clickable(enabled = !dimmed, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // No spinner. A thumbnail that has not arrived yet is a common, uninteresting state
            // and a spinning grid of them is unreadable.
            LightText("···", LightTextVariant.Detail, lighten = true)
        }
        if (dimmed) Box(Modifier.fillMaxSize().background(Color(0xCC000000)))
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    LightText(
        if (on) "[$label]" else " $label ",
        LightTextVariant.Button,
        modifier = Modifier.clickable { onChange(!on) },
        lighten = !on,
    )
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { LightText(label, LightTextVariant.Button) }
}

/** A plain rule that fills left to right. The panel has no use for a spinner. */
@Composable
private fun Bar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Color(0xFF333333))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .background(Color.White)
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
