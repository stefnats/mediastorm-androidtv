package com.strmr.mobile.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.components.ArtImage
import com.strmr.tv.api.ArtworkKind
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Title
import com.strmr.tv.ui.details.rememberContentArtworkEditor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentArtworkDialog(target: DetailsTarget, title: Title, expected: Long, onDismiss: () -> Unit) {
    val editor = rememberContentArtworkEditor(target, title, expected)
    val state by editor.state.collectAsState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            item { ListItem(headlineContent = { Text("Artwork for this profile") }, supportingContent = { Text(state.error ?: state.message ?: target.name) }) }
            items(ArtworkKind.entries) { kind ->
                ListItem(headlineContent = { Text(kind.label + if (state.kind == kind) " · selected" else "") },
                    modifier = Modifier.clickable(enabled = !state.saving) { editor.kind(kind) })
            }
            if (state.loading || state.error != null) item {
                ListItem(headlineContent = { Text(if (state.loading) "Loading artwork choices…" else "Reload choices") },
                    modifier = Modifier.clickable(enabled = !state.loading) { scope.launch { editor.load() } })
            }
            if (state.supported) items(editor.options) { option ->
                ListItem(headlineContent = { Text((if (state.saving) "Saving…" else option.label) + if (state.selected == option.url) " · selected" else "") },
                    leadingContent = { ArtImage(option.previewUrl, "w185", option.label, Modifier.size(80.dp), contentScale = ContentScale.Fit) },
                    modifier = Modifier.clickable(enabled = !state.saving && state.error == null) { scope.launch { editor.save(option.url) } })
            }
            item { ListItem(headlineContent = { Text("Done") }, modifier = Modifier.clickable(onClick = onDismiss)) }
        }
    }
}
