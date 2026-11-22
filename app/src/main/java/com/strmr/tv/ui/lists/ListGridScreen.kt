package com.strmr.tv.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.strmr.tv.home.BrowseList
import com.strmr.tv.home.BrowseOptions
import com.strmr.tv.home.BrowseSort
import com.strmr.tv.home.HomeLink
import com.strmr.tv.home.filters
import com.strmr.tv.home.indexesLetters
import com.strmr.tv.home.offersRandom
import com.strmr.tv.home.sorts
import kotlinx.coroutines.CancellationException
import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.TitleActions
import com.strmr.tv.ui.ChoiceDialog
import com.strmr.tv.ui.DialogChoice
import com.strmr.tv.ui.GridCardSizeButton
import com.strmr.tv.ui.LocalNavigator
import com.strmr.tv.ui.Page
import com.strmr.tv.ui.pageBackground
import com.strmr.tv.ui.PageMargin
import com.strmr.tv.ui.PosterGrid
import com.strmr.tv.ui.fullWidth
import kotlinx.coroutines.launch

/**
 * A whole list as a poster grid, paged from `display-list` as focus nears the end: what a
 * Lists tile, an explore card, a streaming service, a collection hub card or Ask AI opens.
 * The server sorts and filters ([BrowseOptions]); any change reloads from the first page
 * ([ListGridModel]). The profile's own lists can be renamed or deleted here, and long press
 * removes a title.
 */
@Composable
fun ListGridScreen(list: BrowseList, query: Map<String, String> = emptyMap(), currentPage: () -> Boolean = { true }) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember { ListGridModel(list, query, scope, pageSelected = currentPage) }
    val active = currentPage()
    LaunchedEffect(model, active) { model.pageSelectionChanged(active) }
    val operations = remember(model, active) { CustomListOperations(model.dataContext, selected = currentPage) }
    DisposableEffect(operations) { onDispose { operations.close() } }
    var renaming by remember(operations) { mutableStateOf(false) }
    var deleting by remember(operations) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val status = remember { FocusRequester() }

    val customId = model.customId
    val title = model.title
    val manageable = model.manageable
    val options = model.options
    val entries = model.entries
    val total = model.total
    val loadError = model.loadError

    // Only this list's own changes: an add re-reads it, a removal drops the title in place so focus stays near it.
    LaunchedEffect(customId) { model.followItemChanges() }
    LaunchedEffect(model) { model.followStoredRatingChanges() }
    LaunchedEffect(model.reloadKey) { model.reload() }
    // A title action (Set Aside, hide / unhide, watched) re-reads the loaded part in place, so its
    // state and badges show while focus stays on the card instead of jumping to the controls.
    var seenChanges by remember { mutableIntStateOf(TitleActions.changes) }
    LaunchedEffect(TitleActions.changes) {
        if (TitleActions.changes == seenChanges) return@LaunchedEffect
        seenChanges = TitleActions.changes
        model.rereadLoaded()
    }
    // Once the first cards are laid out (a frame after they arrive), focus the first.
    LaunchedEffect(entries.isNotEmpty()) {
        if (entries.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    val cards = model.cards
    // Nothing left to show (the last title removed, unhidden or hidden here): the status line
    // takes focus, so the page keeps it and Back still closes just this page.
    val settled = cards.isEmpty() && (loadError != null || total != null)
    LaunchedEffect(settled) {
        if (!settled) return@LaunchedEffect
        withFrameNanos { }
        runCatching { status.requestFocus() }
    }

    fun pickRandom() = model.pickRandom { link ->
        when (link) {
            is HomeLink.Details -> navigator.openDetails(link.target)
            is HomeLink.Library -> navigator.open(Page.LibraryShow(link.entry))
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().pageBackground()) {
        PosterGrid(
            cards,
            first = first,
            contentPadding = PaddingValues(horizontal = PageMargin, vertical = 32.dp),
            onNearEnd = { scope.launch { model.loadPage() } },
            // About half a page ahead, so a cold page (seconds) is usually in before the end.
            nearEndCards = ListGridModel.PAGE / 2,
            continueWatching = list == BrowseList.ContinueWatching,
            // Set Aside titles leave through the menu's Remove from Set Aside.
            extraActions = { card -> if (manageable) listOf(DialogChoice("Remove from List") { model.remove(card) }) else emptyList() },
            header = {
                fullWidth("header") {
                    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            total?.let { Text(if (list is BrowseList.RecentlyWatchedCached) "$it watched entries" else if (it == 1) "1 title" else "$it titles", color = Color.White.copy(alpha = 0.6f)) }
                            if (list is BrowseList.ContinueWatchingCached) Text("Saved feed observation · No automatic update", color = Color.White.copy(alpha = 0.6f))
                            if (list is BrowseList.RecentlyWatchedCached) Text("Stored original-language information · Unknown may have no current metadata", color = Color.White.copy(alpha = 0.6f))
                            if (list is BrowseList.PopularOnServerCached) Text("Stored completed-item views · Original language uses current cached information", color = Color.White.copy(alpha = 0.6f))
                            if (list is BrowseList.SimilarCached) Text("Saved recommendations returned for this title · Original language is catalogue information", color = Color.White.copy(alpha = 0.6f))
                            if (list is BrowseList.TMDBPublicListCached) Text("Saved returned items · Original language is catalogue information", color = Color.White.copy(alpha = 0.6f))
                            if (list is BrowseList.CollectionCached) Text("Saved collection data · Original-language filters", color = Color.White.copy(alpha = 0.6f))
                            if (list == BrowseList.LovedStored) Text("All visible saved Love ratings, with original-language filters. Loved Titles also shows artwork and watched status.", color = Color.White.copy(alpha = 0.6f))
                        }
                        val parts = (list as? BrowseList.Service)?.parts.orEmpty()
                        // The card size button ends the controls row; a list without controls has it alone.
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (list.sorts.isNotEmpty() || list.filters || list.offersRandom || parts.size > 1 || model.storedMediaFilterSupported) {
                                BrowseControls(
                                    list, options, { model.options = it }, model.genres, parts, model.part, { model.part = it }, model.picking,
                                    onRandom = if (list.offersRandom) ::pickRandom else null,
                                    onRename = if (manageable) { { if (operations.isCurrent) renaming = true } } else null,
                                    onDelete = if (manageable) { { if (operations.isCurrent) deleting = true } } else null,
                                    originalLanguageFilterSupported = model.originalLanguageFilterSupported,
                                    storedMediaFilterSupported = model.storedMediaFilterSupported,
                                )
                            }
                            if (list == BrowseList.ContinueWatching) androidx.tv.material3.OutlinedButton(onClick = {
                                // Accept the original page/epoch before navigation hides it.
                                val accepted = model.openSavedWatching(requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), currentPage)
                                if (accepted != null) com.strmr.tv.api.ApiClient.inContext(model.dataContext) { navigator.openList(accepted) }
                            }) { Text("Browse saved Continue Watching") }
                            if (model.canOpenSavedRecent) androidx.tv.material3.OutlinedButton(onClick = {
                                val accepted = model.openSavedRecent(requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), currentPage)
                                if (accepted != null) com.strmr.tv.api.ApiClient.inContext(model.dataContext) { navigator.openList(accepted) }
                            }) { Text("Browse saved Recently Watched") }
                            if (model.canOpenSavedPopular) androidx.tv.material3.OutlinedButton(onClick = {
                                val accepted = model.openSavedPopular(requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), currentPage)
                                if (accepted != null) com.strmr.tv.api.ApiClient.inContext(model.dataContext) { navigator.openList(accepted) }
                            }) { Text("Browse saved Popular on This Server") }
                            if (model.canOpenSavedSimilar) androidx.tv.material3.OutlinedButton(onClick = {
                                val accepted = model.openSavedSimilar(requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), currentPage)
                                if (accepted != null) com.strmr.tv.api.ApiClient.inContext(model.dataContext) { navigator.openList(accepted) }
                            }) { Text("Browse saved recommendations") }
                            if (model.canOpenSavedPublicList) androidx.tv.material3.OutlinedButton(onClick = {
                                val accepted = model.openSavedPublicList(requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job]), currentPage)
                                if (accepted != null) com.strmr.tv.api.ApiClient.inContext(model.dataContext) { navigator.openList(accepted) }
                            }) { Text("Browse saved list") }
                            if (list == BrowseList.LovedStored || list is BrowseList.CollectionCached || list is BrowseList.ContinueWatchingCached || list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached) androidx.tv.material3.OutlinedButton(onClick = { scope.launch { model.reload() } }) { Text("Reload list") }
                            GridCardSizeButton()
                        }
                        model.actionError?.let { Text(it, color = Color(0xFFFF6B6B)) }
                        if (list.indexesLetters && options.sort == BrowseSort.NAME && model.letters.isNotEmpty()) {
                            LetterIndex(model.letters, options.letter) { model.options = options.copy(letter = it) }
                        }
                    }
                }
            },
            footer = {
                // The next page is on its way: the grid hasn't ended.
                if (cards.isNotEmpty() && model.loadingMore) fullWidth("more") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
                if (cards.isEmpty()) fullWidth("status") {
                    Box(Modifier.focusRequester(status).focusable().padding(24.dp)) {
                        when {
                            loadError != null -> Text(loadError!!, color = Color(0xFFFF6B6B))
                            total != null -> Text(
                                if (options.isFiltered || options.letter != null) "No titles match these filters." else "Nothing in this list yet.",
                                color = Color.White.copy(alpha = 0.6f),
                            )
                            else -> CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                    }
                }
            },
        )
    }

    if (renaming && customId != null) {
        ListNameDialog("Rename List", confirm = "Rename", initial = title, renaming = customId,
            expectedContext = model.dataContext, selected = { operations.isCurrent }, onDismiss = { renaming = false }) { name, operation ->
            if (name != title) CustomListsStore.rename(customId, name, operation)
        }
    }
    val deletion = remember(deleting, operations, customId) { if (deleting && customId != null) operations.begin(customId, key = "delete") else null }
    if (deleting && customId != null && deletion != null) {
        val dismiss = { if (deletion.publish { deleting = false }) operations.retire("delete") }
        ChoiceDialog(
            title = "Delete “$title”?", message = "The titles stay in your library; only the list goes.",
            dismissOnChoice = false,
            choices = listOf(
                DialogChoice("Delete List") {
                    if (deletion.publish { deleting = false }) scope.launch {
                        try {
                            operations.run(deletion) { CustomListsStore.delete(customId, it); it.publish { navigator.close() } }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { deletion.publish { model.actionError = "Unable to delete this list. Try again." } }
                    }
                },
                DialogChoice("Cancel", dismiss),
            ), onDismiss = dismiss,
        )
    }
}
