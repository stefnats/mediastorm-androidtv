/* Hallmark · Ecosystem Index · existing Mediastorm theme · native Material tokens
 * Audience: family profiles; use: resume and saved titles; tone: quiet cinematic utility.
 * No new motion, fonts, palette or navigation framework.
 */
package com.strmr.tv.ui.lists

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.strmr.tv.app.AppSession
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.ProfileDataContext
import androidx.compose.runtime.collectAsState
import com.strmr.tv.library.ListsTabStore
import com.strmr.tv.library.WatchlistStore
import com.strmr.tv.ratings.TitleRatingChanges
import com.strmr.tv.ui.ContentMargin
import com.strmr.tv.ui.LocalNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** A focused entry to existing profile lists; catalogue discovery keeps its own page. */
@Composable
fun PersonalAreaScreen() {
    val profile by AppSession.profile.collectAsState()
    if (!PersonalArea.available(profile)) {
        Text("Open Home to browse this profile's allowed titles.", modifier = Modifier.padding(ContentMargin))
        return
    }
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val listContext by ProfileDataContext.changes.collectAsState()
    var naming by remember(listContext) { mutableStateOf(false) }
    val preferenceRevision by TitleRatingChanges.generation.collectAsState()
    val originalContext = listContext
    val producer = requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job])
    val refreshState = remember(originalContext, producer) { com.strmr.tv.ui.lists.ProfileListRefresh(originalContext, producer) }
    DisposableEffect(refreshState) { onDispose { refreshState.close() } }
    val refreshing = refreshState.refreshing
    val failed = refreshState.failed
    suspend fun refresh() = refreshState.refresh { expected -> refreshPersonalArea(expected) }
    LaunchedEffect(originalContext) { refresh() }
    LaunchedEffect(navigator, originalContext) {
        snapshotFlow { navigator.isCovered }.drop(1).filter { !it }.collect { refresh() }
    }
    val lists = CustomListsStore.lists
    val watching = ListsTabStore.continueWatching
    val count = WatchlistStore.items.size.takeIf { WatchlistStore.isLoaded && WatchlistStore.loadError == null }
    LazyColumn(
        Modifier.fillMaxSize().padding(top = 76.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = ContentMargin), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("My Area", style = MaterialTheme.typography.headlineMedium)
                    Text(profile!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { scope.launch { refresh() } }) { Text(if (refreshing) "Refreshing…" else "Refresh") }
            }
        }
        item("personal") { TileRow("Continue and Saved", PersonalArea.tiles(watching, count, preferenceRevision)) }
        item("reminders") {
            OutlinedButton(onClick = { navigator.open(com.strmr.tv.ui.Page.Reminders) }, modifier = Modifier.padding(horizontal = ContentMargin)) { Text("Reminders & Inbox") }
        }
        item("your-lists") {
            TileRow("Your Lists", PersonalArea.ownLists(lists)) { if (listContext == ProfileDataContext.current) naming = true }
        }
        if (!refreshing && (failed || lists == null || watching == null || WatchlistStore.loadError != null || ListsTabStore.loadError != null)) item("retry") {
            Text("Some saved items could not be loaded. Select Refresh to try again.", modifier = Modifier.padding(horizontal = ContentMargin))
        }
    }
    if (naming) ListNameDialog("New List", "Add titles from a title's menu.", confirm = "Create", expectedContext = listContext, onDismiss = { naming = false }) { name, operation ->
        CustomListsStore.create(name, operation)
    }
}
