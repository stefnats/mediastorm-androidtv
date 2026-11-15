package com.strmr.tv.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.strmr.tv.api.LocalSourceChoice
import com.strmr.tv.ui.ChoiceDialog
import com.strmr.tv.ui.DialogChoice

@Composable
fun ContentLocalPreferenceDialog(targets: List<ContentLocalScope>, expectedContext: Long, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val scope=rememberCoroutineScope()
    val editor=remember(targets,expectedContext) { ContentLocalPreferences(scope,targets,expectedContext,onSaved) }
    val state by editor.state.collectAsState()
    LaunchedEffect(editor) { editor.load() }
    LaunchedEffect(state.closed) { if (state.closed) onDismiss() }
    DisposableEffect(editor) { onDispose { editor.close() } }
    if (state.closed) return
    val detail=when {
        state.saving -> "Saving ${state.selected.label}..."
        state.loading -> "Loading source preferences..."
        else -> state.error ?: "Changes apply to ${state.selected.label} for this profile. More specific overrides take priority. Inherit uses the parent title or profile/server setting."
    }
    ChoiceDialog(
        title="Automatic source preference",
        message=detail+"\n\nPrefer local uses only eligible matching files. Normal selection searches first and keeps local fallback. Audio and subtitles are unchanged. Explicit library/Versions playback stays deliberate. Season and episode controls require official numbering.",
        choices=buildList {
            add(DialogChoice("Cancel") { editor.close() })
            if (!state.loading && !state.saving) {
                if (state.supported) {
                    state.scopes.forEach { target -> add(DialogChoice((if (target==state.selected) "Scope: " else "Edit ")+target.label) { editor.select(target) }) }
                    LocalSourceChoice.entries.forEach { choice ->
                        val selected=state.selected.id in state.values && state.values[state.selected.id]==choice.preference
                        add(DialogChoice((if (selected) "Selected: " else "")+choice.label) { editor.choose(choice) })
                    }
                }
                if (state.error!=null) add(DialogChoice("Reload") { editor.load() })
            }
        },
        onDismiss=editor::close, steady=true, dismissOnChoice=false,
    )
}
