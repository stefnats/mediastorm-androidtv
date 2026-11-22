package com.strmr.mobile.ui.details

import com.strmr.tv.library.CustomListSelectionKey

import kotlinx.coroutines.Job
import androidx.compose.runtime.DisposableEffect
import com.strmr.tv.library.TitleListAction
import com.strmr.tv.library.TitleListOrigin
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.reminders.ReminderTarget
import com.strmr.tv.ui.reminders.reminderChoices
import com.strmr.tv.ui.reminders.remindersAllowed
import androidx.compose.runtime.collectAsState

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.strmr.tv.ui.rememberSourceHint
import com.strmr.tv.ui.launchSourceRecheck
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.strmr.mobile.ui.components.ActionSheet
import com.strmr.mobile.ui.components.SheetAction
import com.strmr.mobile.ui.components.SheetHeader
import com.strmr.tv.ui.details.contentLocalScopes
import com.strmr.tv.ui.details.ContentLocalRequest
import com.strmr.tv.api.Episode
import com.strmr.tv.home.DetailsMenuSnapshot
import com.strmr.tv.home.loadDetailsMenuSnapshot
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.TitleActions
import com.strmr.tv.player.WarmPrequeue
import com.strmr.tv.ui.AppIcons
import com.strmr.tv.ui.details.isTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The top bar's More (the Expo page's More Options with the TV page's extras): Audio &
 * Subtitles, Choose Source, Versions, Add to List…, Watch State…, Set Aside and Remove from
 * Continue Watching while the title is in Continue Watching, Remove from Set Aside, Episode
 * Order…, then Hide from This Profile (which leaves the page) or Unhide, and any added actions.
 */
@Composable
fun DetailsMoreSheet(state: DetailsPageState, warm: WarmPrequeue?, scope: CoroutineScope, extras: DetailsActionContext, currentRoute: () -> Boolean, onHidden: () -> Unit) {
    val reminderProfile by AppSession.profile.collectAsState()
    val model = state.model
    val target = model.target
    val artworkSupported = com.strmr.tv.ui.details.rememberArtworkSupported(target, model.dataContext)
    val listOrigin = remember(model, state) { TitleListOrigin(target, model.dataContext, requireNotNull(scope.coroutineContext[Job]), currentRoute) }
    DisposableEffect(listOrigin) { onDispose { listOrigin.close() } }
    val rate = com.strmr.tv.ui.rememberRatingAction(target, listOrigin, scope)
    var membership by remember(listOrigin) { mutableStateOf(DetailsMenuSnapshot()) }
    LaunchedEffect(listOrigin) {
        loadDetailsMenuSnapshot(target, listOrigin) { snapshot -> membership = snapshot }
    }
    val actions = buildList {
        if(remindersAllowed(reminderProfile) && reminderChoices(ReminderTarget(target)).isNotEmpty()) add(SheetAction("Remind me…", Icons.Outlined.Star) {state.reminderTarget=ReminderTarget(target)})
        if (!target.fromLibrary) add(SheetAction("Rate for this profile…", Icons.Outlined.Star, action = rate))
        if (artworkSupported && model.title != null) add(SheetAction("Choose artwork for this profile…", Icons.Outlined.Star) {
            if (currentRoute() && state.playbackCurrent) state.artworkTitle = model.title
        })
        model.currentSeason.let { season ->
            contentLocalScopes(target, season).takeIf { it.isNotEmpty() }?.let { targets ->
                val generation = model.accessGeneration
                add(SheetAction("Automatic source preference…", Icons.Outlined.Storage, action = TitleListAction(listOrigin, scope) { operation ->
                    ContentLocalRequest.capture(target, targets, operation, listOrigin, { state.localPreferenceRequest }) {
                        currentRoute() && state.playbackCurrent && model.accessGeneration == generation && model.target === target && model.currentSeason === season
                    }?.let { state.localPreferenceRequest = it }
                }))
            }
        }
        if (state.prequeueTracks.visible(warm)?.tracks != null) add(SheetAction("Audio & Subtitles", AppIcons.Waveform) { state.prequeueTracks.open = true })
        if (target.isMovie || state.activeEpisode != null) add(SheetAction("Choose Source", Icons.Outlined.Search) { state.sourcesFor = EpisodeTarget(state.activeEpisode) })
        if (model.libraryVersions(state.activeEpisode).isNotEmpty()) {
            add(SheetAction("Versions", Icons.Outlined.Storage) { state.versionsFor = EpisodeTarget(state.activeEpisode) })
        }
        DetailsExtras.providers.forEach { addAll(it.moreActions(extras)) }
        add(SheetAction("Add to List…", Icons.AutoMirrored.Outlined.PlaylistAdd, action = TitleListAction(listOrigin, scope) { CustomListsStore.openPicking(target, it) }))
        if (!target.isMovie && model.seasons.isNotEmpty()) add(SheetAction("Watch State…", Icons.Outlined.Checklist) { state.watchState = true })
        if (membership.inContinueWatching) {
            add(SheetAction("Set Aside", Icons.Outlined.PauseCircle, action = TitleListAction(listOrigin, scope) { TitleActions.setAside(target, it) }))
            add(SheetAction("Remove from Continue Watching", Icons.Outlined.RemoveCircleOutline, action = TitleListAction(listOrigin, scope) { TitleActions.hideFromContinueWatching(target, it) }))
        }
        if (membership.inSetAside) {
            add(SheetAction("Remove from Set Aside", Icons.Outlined.PauseCircle, action = TitleListAction(listOrigin, scope) { TitleActions.removeFromSetAside(target, it) }))
        }
        if (model.offersOrderings) {
            add(SheetAction("Episode Order: ${model.currentOrdering?.name ?: "Default"}…", Icons.Outlined.FormatListNumbered) { state.episodeOrder = true })
        }
        if (HiddenItemsStore.isHidden(target)) {
            add(SheetAction("Unhide", Icons.Outlined.Visibility, action = TitleListAction(listOrigin, scope) { TitleActions.unhide(target, it) }))
        } else {
            add(SheetAction("Hide from This Profile", Icons.Outlined.VisibilityOff, destructive = true, action = TitleListAction(listOrigin, scope) { if (TitleActions.hide(target, it)) it.publish(onHidden) }))
        }
    }
    ActionSheet({ listOrigin.dismiss { state.more = false } }, header = {
        Column {
            SheetHeader(target.name, model.metadataLine, model.displayPosterUrl)
            membership.notice?.let { Text(it, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }
    }, actions = actions)
}

/**
 * An episode's long press: Play (or Resume) and Start Over, Choose Version (library copies),
 * Choose Source, Mark as Watched / Unwatched, and any added actions (Download).
 */
@Composable
fun EpisodeMenuSheet(state: DetailsPageState, episode: Episode, scope: CoroutineScope, extras: DetailsActionContext, currentRoute: () -> Boolean) {
    val reminderProfile by AppSession.profile.collectAsState()
    val model = state.model
    val sourceHint = rememberSourceHint(state.episodeSourceHint)
    val resumeAt = model.resumePoint(episode)
    val watched = model.isWatched(episode)
    val watchedIntent = model.episodeWatchIntent(listOf(episode), !watched, null)
    val watchedOrigin = remember(model, CustomListSelectionKey(episode)) { TitleListOrigin(model.target, model.dataContext, requireNotNull(scope.coroutineContext[Job]), currentRoute) }
    DisposableEffect(watchedOrigin) { onDispose { watchedOrigin.close() } }
    val actions = buildList {
        add(SheetAction(resumeAt?.let { "Resume from ${it.label}" } ?: "Play", Icons.Outlined.PlayArrow) { state.play(episode, resumeAt) })
        if (resumeAt != null) add(SheetAction("Start Over", Icons.Outlined.Replay) { state.play(episode, null) })
        if (model.libraryVersions(episode).isNotEmpty()) add(SheetAction("Choose Version", Icons.Outlined.Storage) { state.versionsFor = EpisodeTarget(episode) })
        add(SheetAction("Choose Source", Icons.Outlined.Search) { state.sourcesFor = EpisodeTarget(episode) })
        if(remindersAllowed(reminderProfile) && reminderChoices(ReminderTarget(model.target,episode)).isNotEmpty()) add(SheetAction("Remind me…", Icons.Outlined.Star) {state.reminderTarget=ReminderTarget(model.target,episode)})
        contentLocalScopes(model.target, episode = episode).takeIf { it.isNotEmpty() }?.let { targets ->
            val generation = model.accessGeneration
            add(SheetAction("Automatic source preference…", Icons.Outlined.Storage, action = TitleListAction(watchedOrigin, scope) { operation ->
                ContentLocalRequest.capture(model.target, targets, operation, watchedOrigin, { state.localPreferenceRequest }) {
                    currentRoute() && state.playbackCurrent && model.accessGeneration == generation &&
                        model.seasons.any { season -> season.episodes.any { it === episode } }
                }?.let { state.localPreferenceRequest = it }
            }))
        }
        if (sourceHint != null && episode.numbering != null) add(SheetAction("Check sources again", Icons.Outlined.Refresh) { launchSourceRecheck(scope, model.target, episode) })
        DetailsExtras.providers.forEach { addAll(it.episodeActions(extras, episode)) }
        add(SheetAction(if (watched) "Mark as Unwatched" else "Mark as Watched", Icons.Outlined.CheckCircle,
            action = TitleListAction(watchedOrigin, scope) { operation -> watchedIntent?.bind(operation)?.let { model.applyWatchForUi(it) } }))
    }
    ActionSheet(
        { state.episodeMenu = null },
        header = { Column {
            SheetHeader("${episode.code} · ${episode.name}", model.target.name, null)
            sourceHint?.let { Text(it.description(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
        } },
        actions = actions,
    )
}

/**
 * Episode Order (TVDB season types: Aired, DVD, Absolute, …): the current one checked. Picking
 * one saves it for the profile and reloads the page in it. A non-default order pauses
 * watch-history sync for the show, which the header says.
 */
@Composable
fun EpisodeOrderSheet(state: DetailsPageState, scope: CoroutineScope) {
    val model = state.model
    val current = model.currentOrdering
    val actions = model.orderings.map { ordering ->
        val label = if (ordering.isOfficial) "${ordering.name} (Default)" else ordering.name
        SheetAction(label, if (ordering.type == current?.type) Icons.Outlined.CheckCircle else Icons.Outlined.FormatListNumbered) {
            state.selectedEpisodeId = null
            scope.launch { model.pickOrdering(ordering) }
        }
    }
    ActionSheet(
        { state.episodeOrder = false },
        header = { SheetHeader("Episode Order", "Non-default orders pause watch-history sync for this show.", null) },
        actions = actions,
    )
}
