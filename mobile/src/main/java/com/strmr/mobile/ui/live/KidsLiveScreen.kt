package com.strmr.mobile.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.shell.PageScaffold
import com.strmr.tv.live.rememberKidsLiveBrowser
import com.strmr.tv.player.PlaybackCoordinator
import kotlinx.coroutines.launch

@Composable
fun KidsLiveScreen(asPage: Boolean) {
    val browser = rememberKidsLiveBrowser()
    val state by browser.state.collectAsState()
    val scope = rememberCoroutineScope()
    PageScaffold("Child Live TV", showBack = asPage) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                ListItem(headlineContent = { Text(if (state.loading) "Loading the selected guide…" else "Refresh programmes") },
                    supportingContent = { Text(state.error ?: "Ages are claims from the guide selected by a parent. Playback ends with the chosen programme.") },
                    modifier = Modifier.clickable(enabled = !state.loading) { scope.launch { browser.load() } })
            }
            items(state.channels, key = { it.id }) { channel ->
                ListItem(headlineContent = { Text(channel.name) }, supportingContent = { Text(channel.description) },
                    modifier = Modifier.clickable(enabled = channel.selectable && !state.loading) {
                        PlaybackCoordinator.playLive(channel.channel(), channel.programme?.title, browser.context)
                    })
            }
            if (!state.loading && state.error == null && state.channels.isEmpty()) item {
                ListItem(headlineContent = { Text("No channels with guide information are available.") })
            }
        }
    }
}
