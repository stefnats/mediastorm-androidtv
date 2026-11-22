package com.strmr.tv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.strmr.tv.ui.PickerDialog
import kotlinx.coroutines.launch

@Composable
fun PlaybackQualityRows() {
    val editor = rememberPlaybackQualityEditor()
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picking by remember(editor) { mutableStateOf<QualitySettingRow?>(null) }
    picking?.let { row ->
        PickerDialog(row.title, row.choices.map { it.label }, row.selected,
            onPick = { index -> scope.launch { editor.save(row.scope, row.field, row.choices[index].value) } },
            onDismiss = { picking = null })
    }
    Column {
        SpokenAudioAutoRow()
        if (state.loading || state.error != null) ListItem(selected = false, enabled = !state.loading,
            onClick = { scope.launch { editor.load() } }, headlineContent = { Text("Playback quality") },
            supportingContent = { Text(state.error ?: "Loading playback quality…") })
        state.snapshot?.rows?.forEach { row ->
            ListItem(selected = false, enabled = !state.loading && !state.saving && state.error == null && row.editable,
                onClick = { picking = row }, headlineContent = { Text(row.title) },
                supportingContent = { Text(if (state.saving) "Saving…" else row.description) },
                trailingContent = { Text(row.valueLabel) })
        }
    }
}

@Composable
private fun SpokenAudioAutoRow() {
    val editor = rememberSpokenAudioAutoEditor()
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picking by remember(editor) { mutableStateOf(false) }
    val snapshot = state.snapshot
    if (picking && snapshot != null) PickerDialog("Automatic spoken language hints", snapshot.choices, snapshot.selected,
        onPick = { index -> scope.launch { editor.save(listOf(null, false, true)[index]) } }, onDismiss = { picking = false })
    if (state.error != null || snapshot?.supported == true) ListItem(selected = false,
        enabled = !state.busy && (state.error != null || snapshot?.editable == true),
        onClick = { if (state.error != null) scope.launch { editor.load() } else picking = true },
        headlineContent = { Text("Automatic spoken language hints") },
        supportingContent = { Text(state.error ?: "After 45 seconds of continuous video, check two short samples. Hints never report or switch sources.") },
        trailingContent = { Text(if (state.busy) "Saving…" else snapshot?.label.orEmpty()) })
}
