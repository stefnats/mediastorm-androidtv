package com.strmr.mobile.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.components.SheetHeader
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.BulkWatchConfirmation
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Episode
import com.strmr.tv.api.Season
import com.strmr.tv.api.WatchedScope
import com.strmr.tv.api.WatchedSelection
import com.strmr.tv.api.WatchedConfirmation
import com.strmr.tv.library.CustomListOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.runtime.DisposableEffect
import com.strmr.tv.library.TitleActions
import com.strmr.tv.ui.details.DetailsModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.strmr.tv.player.PlaybackSessionContext

/** Original menu origin retained through delayed composition; never a server/content grant. */
class WatchStateOrigin(val target: DetailsTarget, val context: Long, val parent: Job) {
    private val owner = PlaybackSessionContext.capture(context)
    val isCurrent get() = parent.isActive && owner.isCurrent()
}

@Composable
fun WatchStateLoader(origin: WatchStateOrigin, current: () -> Boolean, onDismiss: () -> Unit) {
    val uiScope = rememberCoroutineScope()
    val scope = remember(origin) { CoroutineScope(uiScope.coroutineContext + SupervisorJob(origin.parent)) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val model = remember(origin) { DetailsModel(origin.target, scope, expectedContext = origin.context) }
    LaunchedEffect(model) { if (origin.isCurrent && current()) model.load() }
    WatchStateSheet(model, episode = model.primaryEpisode, currentPage = { origin.isCurrent && current() }, onDismiss = onDismiss)
}

/**
 * A series' watch state (the TV page's watch-state panel) as a bottom sheet: the [episode] Play
 * targets and the whole-show choices (all, or all but the most recent season) close it; each
 * season toggles in place with the sheet left up (checked once every aired episode is
 * watched). Whole-show and whole-season changes carry their scope so Trakt-style providers
 * record them with their own bulk calls. Only aired episodes are marked watched. Whole-show
 * choices, and seasons of [BulkWatchConfirmation.SEASON_THRESHOLD] episodes or more, confirm
 * first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchStateSheet(model: DetailsModel, episode: Episode?, currentPage: () -> Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<BulkWatchConfirmation.Prompt, WatchedConfirmation>?>(null) }
    val active = currentPage()
    val operations = remember(model, model.accessGeneration, active) { CustomListOperations(model.dataContext, model.target, currentPage) }
    DisposableEffect(operations) { onDispose { pending?.second?.cancel(); operations.close() } }
    val show = model.target.name
    val ordered = remember(model.seasons) { model.seasons.sortedBy { it.number } }
    val aired = remember(model.seasons) { ordered.associate { s -> s.number to s.episodes.filter(DetailsModel::hasAired) } }
    val regular = ordered.map { it.number }.filter { it > 0 }
    val latest = if (regular.size > 1) regular.max() else null
    val rest = ordered.filter { it.number != latest }

    fun prepare(watched: Boolean, episodes: List<Episode>, watchedScope: WatchedScope?): WatchedSelection? {
        val intent = model.episodeWatchIntent(episodes.toList(), watched, watchedScope) ?: return null
        val operation = operations.begin(key = "watched:${episodes.map { it.id }.sorted().joinToString(",")}") ?: return null
        return intent.bind(operation)
    }
    fun execute(selection: WatchedSelection, close: Boolean) {
        scope.launch {
            operations.run(selection.operation) {
                val success = model.applyWatchForUi(selection)
                selection.publish {
                    error = if (success) null else model.watchedError
                    if (success && close) onDismiss()
                }
            }
        }
    }
    fun apply(watched: Boolean, episodes: List<Episode>, watchedScope: WatchedScope?, close: Boolean) {
        prepare(watched, episodes, watchedScope)?.let { execute(it, close) }
    }
    fun confirm(watched: Boolean, episodes: List<Episode>, watchedScope: WatchedScope?, prompt: BulkWatchConfirmation.Prompt, close: Boolean) {
        val selection = prepare(watched, episodes, watchedScope) ?: return
        pending?.second?.cancel()
        pending = prompt to WatchedConfirmation(selection, close)
    }
    fun confirmAll(watched: Boolean, episodes: List<Episode>, watchedScope: WatchedScope?, exceptSeason: Int? = null) {
        if (episodes.isNotEmpty()) confirm(watched, episodes, watchedScope, BulkWatchConfirmation.allEpisodes(watched, episodes.size, show, exceptSeason), close = true)
    }

    ModalBottomSheet(onDismissRequest = { pending?.second?.cancel(); operations.close(); onDismiss() }, sheetState = rememberModalBottomSheetState()) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item { SheetHeader("Watch State", model.target.name, null) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) } }
            if (model.seasons.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                return@LazyColumn
            }
            episode?.let { ep ->
                val watched = model.isWatched(ep)
                section("${ep.code} · ${ep.name}")
                item { Choice(if (watched) "Mark as Unwatched" else "Mark as Watched") { apply(!watched, listOf(ep), null, close = true) } }
            }
            val allAired = ordered.flatMap { aired[it.number].orEmpty() }
            val all = ordered.flatMap { it.episodes }
            section("All Episodes")
            item { Choice("Mark All ${allAired.size} as Watched", enabled = allAired.isNotEmpty()) { confirmAll(true, allAired, WatchedScope.SHOW) } }
            if (latest != null) {
                val restAired = rest.flatMap { aired[it.number].orEmpty() }
                item { Choice("All Except Season $latest as Watched", enabled = restAired.isNotEmpty()) { confirmAll(true, restAired, null, exceptSeason = latest) } }
            }
            item { Choice("Mark All ${all.size} as Unwatched", enabled = all.isNotEmpty()) { confirmAll(false, all, WatchedScope.SHOW) } }
            if (latest != null) {
                val restAll = rest.flatMap { it.episodes }
                item { Choice("All Except Season $latest as Unwatched", enabled = restAll.isNotEmpty()) { confirmAll(false, restAll, null, exceptSeason = latest) } }
            }
            section("Seasons")
            // Keyed by number: a season's ID can be empty, and a repeated key crashes the list.
            ordered.forEach { season ->
                item(key = "season-${season.number}") {
                    SeasonToggle(season, aired[season.number].orEmpty(), model::isWatched) { w, eps ->
                        val seasonScope = if (season.number > 0) WatchedScope.SEASON else null
                        val prompt = BulkWatchConfirmation.season(w, eps.size, show, seasonName(season.number))
                        if (prompt == null) apply(w, eps, seasonScope, close = false)
                        else confirm(w, eps, seasonScope, prompt, close = false)
                    }
                }
            }
        }
    }

    pending?.let { (prompt, ticket) ->
        AlertDialog(
            onDismissRequest = { if (pending?.second === ticket) { ticket.cancel(); pending = null } },
            title = { Text(prompt.title) },
            text = { Text(prompt.message) },
            confirmButton = { TextButton(onClick = { if (pending?.second === ticket) { val selection = ticket.confirm(); pending = null; if (selection != null) execute(selection, ticket.close) } }) { Text(prompt.confirm) } },
            dismissButton = { TextButton(onClick = { if (pending?.second === ticket) { ticket.cancel(); pending = null } }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.section(title: String) = item {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/** Watched (every aired episode) unmarks the whole season; otherwise its aired episodes are marked. */
@Composable
private fun SeasonToggle(season: Season, aired: List<Episode>, isWatched: (Episode) -> Boolean, apply: (Boolean, List<Episode>) -> Unit) {
    val watchedCount = season.episodes.count(isWatched)
    val complete = aired.isNotEmpty() && aired.all(isWatched)
    ListItem(
        headlineContent = { Text(seasonName(season.number)) },
        supportingContent = if (watchedCount in 1 until season.episodes.size) {
            { Text("$watchedCount of ${season.episodes.size} watched") }
        } else null,
        trailingContent = if (complete) { { Icon(Icons.Filled.Check, contentDescription = "Watched", tint = MaterialTheme.colorScheme.primary) } } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = aired.isNotEmpty() || watchedCount > 0) {
            if (complete) apply(false, season.episodes) else apply(true, aired)
        },
    )
}

/** "Season 2", or "Specials" for season 0. */
fun seasonName(number: Int) = if (number == 0) "Specials" else "Season $number"
