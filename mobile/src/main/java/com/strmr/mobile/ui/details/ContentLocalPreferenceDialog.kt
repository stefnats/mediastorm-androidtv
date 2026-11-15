package com.strmr.mobile.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.strmr.tv.api.LocalSourceChoice
import com.strmr.tv.ui.details.ContentLocalPreferences
import com.strmr.tv.ui.details.ContentLocalScope

@Composable
fun ContentLocalPreferenceDialog(targets: List<ContentLocalScope>, expectedContext: Long, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val scope=rememberCoroutineScope()
    val editor=remember(targets,expectedContext) { ContentLocalPreferences(scope,targets,expectedContext,onSaved) }
    val state by editor.state.collectAsState()
    LaunchedEffect(editor) { editor.load() }
    LaunchedEffect(state.closed) { if (state.closed) onDismiss() }
    DisposableEffect(editor) { onDispose { editor.close() } }
    if (state.closed) return
    AlertDialog(
        onDismissRequest=editor::close,
        title={ Text("Automatic source preference") },
        text={ Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("For this profile. More specific overrides take priority. Prefer local uses only eligible matching files; normal selection searches first and keeps local fallback. Audio and subtitles are unchanged. Explicit library/Versions playback stays deliberate. Season/episode controls require official numbering.")
            when {
                state.loading -> Text("Loading source preferences...")
                state.saving -> Text("Saving ${state.selected.label}...")
                else -> {
                    state.error?.let { Text(it) }
                    if (state.supported) {
                        state.scopes.forEach { target -> TextButton(onClick={editor.select(target)}) { Text((if(target==state.selected) "Scope: " else "Edit ")+target.label) } }
                        LocalSourceChoice.entries.forEach { choice ->
                            val selected=state.selected.id in state.values && state.values[state.selected.id]==choice.preference
                            TextButton(onClick={editor.choose(choice)}) { Text((if(selected) "Selected: " else "")+choice.label) }
                        }
                    }
                }
            }
        } },
        confirmButton={ if(!state.loading && !state.saving && state.error!=null) TextButton(onClick=editor::load) { Text("Reload") } },
        dismissButton={TextButton(onClick=editor::close) { Text("Cancel") }},
    )
}
