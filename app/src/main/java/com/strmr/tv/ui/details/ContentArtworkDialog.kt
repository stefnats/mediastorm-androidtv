package com.strmr.tv.ui.details

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.strmr.tv.api.Artwork
import com.strmr.tv.api.ArtworkKind
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Title
import com.strmr.tv.ui.SidePanelDialog
import kotlinx.coroutines.launch

@Composable
fun ContentArtworkDialog(target: DetailsTarget, title: Title, expected: Long, onDismiss: () -> Unit) {
    val editor = rememberContentArtworkEditor(target, title, expected)
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    SidePanelDialog("Artwork for this profile", state.error ?: state.message ?: target.name, onDismiss) {
        items(ArtworkKind.entries) { kind ->
            ListItem(selected = state.kind == kind, enabled = !state.saving, onClick = { editor.kind(kind) },
                headlineContent = { Text(kind.label) })
        }
        if (state.loading || state.error != null) item {
            ListItem(selected = false, enabled = !state.loading, onClick = { scope.launch { editor.load() } },
                headlineContent = { Text(if (state.loading) "Loading artwork choices…" else "Reload choices") })
        }
        if (state.supported) items(editor.options) { option ->
            ListItem(selected = state.selected == option.url, enabled = !state.saving && state.error == null,
                onClick = { scope.launch { editor.save(option.url) } },
                headlineContent = { Text(if (state.saving) "Saving…" else option.label) },
                leadingContent = { AsyncImage(Artwork.url(option.previewUrl, "w185"), option.label,
                    modifier = Modifier.width(if (state.kind == ArtworkKind.POSTER) 64.dp else 112.dp).height(96.dp), contentScale = ContentScale.Fit) })
        }
        item { ListItem(selected = false, onClick = onDismiss, headlineContent = { Text("Done") }) }
    }
}
