package com.strmr.tv.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.strmr.tv.live.rememberKidsLiveBrowser
import com.strmr.tv.player.PlaybackCoordinator
import kotlinx.coroutines.launch

/** A programme list with no provider URLs, archive, recording or multiscreen actions. */
@Composable
fun KidsLiveScreen() {
    val browser = rememberKidsLiveBrowser()
    val state by browser.state.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ListItem(selected = false, enabled = !state.loading, onClick = { scope.launch { browser.load() } },
                headlineContent = { Text("Child Live TV") },
                supportingContent = { Text(if (state.loading) "Loading the selected guide…" else state.error ?:
                    "Ages are claims from the guide selected by a parent. Select to refresh; every programme needs a new playback decision.") })
        }
        items(state.channels, key = { it.id }) { channel ->
            ListItem(selected = false, enabled = channel.selectable && !state.loading,
                onClick = { PlaybackCoordinator.playLive(channel.channel(), channel.programme?.title, browser.context) },
                headlineContent = { Text(channel.name) }, supportingContent = { Text(channel.description) })
        }
        if (!state.loading && state.error == null && state.channels.isEmpty()) item { Text("No channels with guide information are available.") }
    }
}
