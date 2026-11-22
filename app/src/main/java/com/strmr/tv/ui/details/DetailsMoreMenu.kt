package com.strmr.tv.ui.details

import com.strmr.tv.library.TitleListAction
import com.strmr.tv.library.TitleListOrigin
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.DetailsMenuSnapshot
import com.strmr.tv.home.loadDetailsMenuSnapshot
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.TitleActions
import com.strmr.tv.library.WatchlistStore
import com.strmr.tv.player.WarmPrequeue
import com.strmr.tv.ui.DialogChoice
import com.strmr.tv.ui.SidePanelDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The details page's More menu (the Expo details page's More Options, its title actions):
 * Set Aside and Remove from Continue Watching while the title is in Continue Watching, Remove
 * from Set Aside while it is set aside, and Hide from this profile (Unhide when hidden).
 * Hiding closes the page, as in the Expo app. A side panel, like Watch State and Manage Lists.
 * Actions run in `scope` (the page's). Above them, the row's actions Customize Buttons moved here
 * (`moved`, [movedActions]); Customize Buttons… comes last.
 */
@Composable
fun DetailsMoreMenu(
    target: DetailsTarget,
    listOrigin: TitleListOrigin,
    scope: CoroutineScope,
    moved: List<DialogChoice>,
    /** Series with alternate orderings: "Episode Order: <current>", opening its picker. */
    episodeOrder: DialogChoice? = null,
    onCustomize: () -> Unit,
    onHidden: () -> Unit,
    onDismiss: () -> Unit,
    sourcePreference: DialogChoice? = null,
    reminder: DialogChoice? = null,
    artworkPreference: DialogChoice? = null,
) {
    val rate = com.strmr.tv.ui.rememberRatingAction(target, listOrigin, scope)
    var membership by remember(listOrigin) { mutableStateOf(DetailsMenuSnapshot()) }
    LaunchedEffect(listOrigin) {
        loadDetailsMenuSnapshot(target, listOrigin) { snapshot -> membership = snapshot }
    }
    val choices = buildList {
        if (!target.fromLibrary) add(DialogChoice("Rate for this profile…", rate))
        addAll(moved)
        sourcePreference?.let(::add)
        artworkPreference?.let(::add)
        reminder?.let(::add)
        if (membership.inContinueWatching) {
            add(DialogChoice("Set Aside", TitleListAction(listOrigin, scope) { TitleActions.setAside(target, it) }))
            add(DialogChoice("Remove from Continue Watching", TitleListAction(listOrigin, scope) { TitleActions.hideFromContinueWatching(target, it) }))
        }
        if (membership.inSetAside) add(DialogChoice("Remove from Set Aside", TitleListAction(listOrigin, scope) { TitleActions.removeFromSetAside(target, it) }))
        if (HiddenItemsStore.isHidden(target)) {
            add(DialogChoice("Unhide", TitleListAction(listOrigin, scope) { TitleActions.unhide(target, it) }))
        } else {
            add(DialogChoice("Hide from this profile", TitleListAction(listOrigin, scope) { if (TitleActions.hide(target, it)) it.publish(onHidden) }))
        }
        episodeOrder?.let(::add)
        add(DialogChoice("Customize Buttons…", onCustomize))
    }
    val first = remember { FocusRequester() }
    // The rows change once Continue Watching is read; focus the first one each time.
    LaunchedEffect(choices.map { it.label }) { withFrameNanos { }; runCatching { first.requestFocus() } }
    SidePanelDialog("More Options", membership.notice ?: target.name, { listOrigin.dismiss(onDismiss) }) {
        itemsIndexed(choices) { index, choice ->
            ListItem(
                selected = false,
                onClick = {
                    val action = choice.action
                    if (action is TitleListAction) action.accept()?.let { action.commit(it) { listOrigin.dismiss(onDismiss) } }
                    else if (listOrigin.dismiss(onDismiss)) action()
                },
                headlineContent = { Text(choice.label) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

/**
 * The row's actions set to More, in row order, each labelled with its current state. The
 * callbacks are the page's, as the row's buttons use them.
 */
@Composable
fun movedActions(
    model: DetailsModel,
    listOrigin: TitleListOrigin,
    trailer: TrailerController,
    prequeueTracks: PrequeueTracks,
    warm: WarmPrequeue?,
    scope: CoroutineScope,
    onStartOver: (DetailsModel.Primary) -> Unit,
    onVersions: () -> Unit,
    onChooseSource: () -> Unit,
    onWatchState: () -> Unit,
): List<DialogChoice> {
    val more = DetailsButtons.inMore()
    val target = model.target
    val primary = model.primary
    val inWatchlist = WatchlistStore.contains(target, model.serverInWatchlist)
    return buildList {
        if (DetailsAction.START_OVER in more && primary?.start != null) add(DialogChoice("Start Over") { onStartOver(primary) })
        if (DetailsAction.VERSIONS in more && model.primaryVersions.isNotEmpty()) add(DialogChoice("Versions", onVersions))
        if (DetailsAction.CHOOSE_SOURCE in more && (target.isMovie || model.primaryEpisode != null)) {
            add(DialogChoice("Choose Source", onChooseSource))
        }
        if (DetailsAction.TRACKS in more && prequeueTracks.visible(warm)?.tracks != null) {
            add(DialogChoice("Audio & Subtitles") { prequeueTracks.open = true })
        }
        if (DetailsAction.TRAILER in more && trailer.available) {
            add(DialogChoice(if (trailer.showing && !trailer.paused) "Pause Trailer" else "Play Trailer") { trailer.buttonPressed(target.name) })
        }
        if (DetailsAction.WATCHLIST in more) {
            add(DialogChoice(if (inWatchlist) "Remove from Watchlist" else "Add to Watchlist", TitleListAction(listOrigin, scope) { operation ->
                try { WatchlistStore.setSaved(target, !inWatchlist, operation) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { operation.publish { com.strmr.tv.ui.AppToast.show(com.strmr.tv.library.watchlistFailureMessage(error)) } }
            }))
        }
        if (DetailsAction.LISTS in more) add(DialogChoice("Manage Lists…", TitleListAction(listOrigin, scope) { CustomListsStore.openPicking(target, it) }))
        if (DetailsAction.WATCHED in more) {
            if (target.isMovie) {
                val watched = model.movieWatched == true
                val intent = model.movieWatchIntent(!watched)
                add(DialogChoice(if (watched) "Mark as Unwatched" else "Mark as Watched", TitleListAction(listOrigin, scope) { operation ->
                    intent?.bind(operation)?.let { model.applyWatchForUi(it) }
                }))
            } else if (model.seasons.isNotEmpty()) {
                add(DialogChoice("Watch State…", onWatchState))
            }
        }
    }
}
