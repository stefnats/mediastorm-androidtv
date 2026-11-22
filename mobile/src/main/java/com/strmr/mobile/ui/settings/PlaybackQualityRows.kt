package com.strmr.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.strmr.tv.ui.settings.rememberSpokenAudioAutoEditor
import com.strmr.tv.ui.settings.QualitySettingRow
import com.strmr.tv.ui.settings.rememberPlaybackQualityEditor
import kotlinx.coroutines.launch

@Composable
fun PlaybackQualityRows() {
    SpokenAudioAutoRow()
    val editor = rememberPlaybackQualityEditor()
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picking by remember(editor) { mutableStateOf<QualitySettingRow?>(null) }
    picking?.let { row ->
        ChoiceDialog(row.title, row.choices.map { it.label }, row.selected, onDismiss = { picking = null }) { index ->
            picking = null
            scope.launch { editor.save(row.scope, row.field, row.choices[index].value) }
        }
    }
    if (state.loading || state.error != null) SettingsRow("Playback quality", state.error ?: "Loading playback quality…",
        enabled = !state.loading) { scope.launch { editor.load() } }
    state.snapshot?.rows?.forEach { row ->
        SettingsRow(row.title, if (state.saving) "Saving…" else "${row.valueLabel}. ${row.description}",
            enabled = !state.loading && !state.saving && state.error == null && row.editable) { picking = row }
    }
}

@Composable
private fun SpokenAudioAutoRow() {
    val editor = rememberSpokenAudioAutoEditor()
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picking by remember(editor) { mutableStateOf(false) }
    val snapshot = state.snapshot
    if (picking && snapshot != null) ChoiceDialog("Automatic spoken language hints", snapshot.choices, snapshot.selected,
        onDismiss = { picking = false }) { index ->
        picking = false
        scope.launch { editor.save(listOf(null, false, true)[index]) }
    }
    if (state.error != null || snapshot?.supported == true) SettingsRow("Automatic spoken language hints",
        state.error ?: "${if (state.busy) "Saving…" else snapshot?.label.orEmpty()}. After 45 seconds of continuous video, check two short samples. Hints never report or switch sources.",
        enabled = !state.busy && (state.error != null || snapshot?.editable == true)) {
        if (state.error != null) scope.launch { editor.load() } else picking = true
    }
}
