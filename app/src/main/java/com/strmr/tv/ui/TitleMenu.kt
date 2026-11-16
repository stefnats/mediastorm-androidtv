package com.strmr.tv.ui

import androidx.compose.runtime.DisposableEffect
import com.strmr.tv.library.TitleListAction
import com.strmr.tv.library.TitleMenuSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.vodDenial
import com.strmr.tv.api.isEpisodeWatched
import com.strmr.tv.api.isMovieWatched
import com.strmr.tv.home.titleSourceCoordinate
import com.strmr.tv.home.HomeCard
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.SetAsideStore
import com.strmr.tv.library.TitleActions
import com.strmr.tv.library.WatchlistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A title's long-press menu, in the Expo app's order: on a Continue Watching card Hide from
 * Continue Watching and Set Aside; Remove from Set Aside for a set-aside title; Manage Lists…;
 * the watchlist toggle; Hide from this profile (Unhide when it is hidden); Mark as watched /
 * unwatched for a movie or the card's one episode (its current state read when the menu
 * opens), the bulk watch-state sheet for a series; then any `extra` choices. Actions run in `scope` (the caller's, which
 * outlives the menu) and report failures to `onError`.
 */
@Composable
fun TitleMenu(
    selection: TitleMenuSelection,
    scope: CoroutineScope,
    extra: List<DialogChoice> = emptyList(),
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val card = selection.card
    val origin = selection.origin
    val target = origin.target ?: return
    val continueWatching = selection.continueWatching
    DisposableEffect(selection) { onDispose { selection.close() } }
    val rate = rememberRatingAction(target)
    val sourceHint = rememberSourceHint(com.strmr.tv.ui.rememberTitleSourceEvidence(card))
    val navigator = LocalNavigator.current
    val inWatchlist = WatchlistStore.contains(target)
    var watched by remember(selection) { mutableStateOf<Boolean?>(null) }
    var watchedBlocked by remember(selection) { mutableStateOf(false) }
    val episode = card.episode
    LaunchedEffect(selection) {
        // Continue Watching's next episode is unwatched by definition.
        watched = try { when {
            target.isMovie -> ApiClient.isMovieWatched(target, origin.context)
            episode != null && !continueWatching -> ApiClient.isEpisodeWatched(episode, target, origin.context)
            else -> null
        }.also { if (!origin.isCurrent) throw kotlinx.coroutines.CancellationException("Menu changed") } }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            if (!origin.isCurrent) return@LaunchedEffect
            if (error.vodDenial != null) {
                watchedBlocked = true
                com.strmr.tv.ui.AppToast.show(requireNotNull(error.vodDenial).message)
                return@LaunchedEffect
            }
            null
        }
        SetAsideStore.refresh(origin.context)
    }
    fun act(action: suspend () -> Unit) = scope.launch { action() }

    val choices = buildList {
        if (!target.fromLibrary) add(DialogChoice("Rate for this profile…") { rate() })
        if (card.titleSourceCoordinate() != null && sourceHint != null) {
            add(DialogChoice("Check sources again") { launchSourceRecheck(scope, target, onError = onError) })
        }
        if (continueWatching) {
            add(DialogChoice("Hide from Continue Watching", TitleListAction(origin, scope) { TitleActions.hideFromContinueWatching(target, it) }))
            add(DialogChoice("Set Aside", TitleListAction(origin, scope) { TitleActions.setAside(target, it) }))
        }
        if (SetAsideStore.contains(target)) add(DialogChoice("Remove from Set Aside", TitleListAction(origin, scope) { TitleActions.removeFromSetAside(target, it) }))
        add(DialogChoice("Manage Lists…", TitleListAction(origin, scope) { CustomListsStore.openPicking(target, it) }))
        add(DialogChoice(if (inWatchlist) "Remove from Watchlist" else "Add to Watchlist", TitleListAction(origin, scope) { operation ->
            try { WatchlistStore.setSaved(target, !inWatchlist, operation) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { operation.publish { com.strmr.tv.ui.AppToast.show(com.strmr.tv.library.watchlistFailureMessage(error)) } }
        }))
        if (card.hiddenManagementEntry || HiddenItemsStore.isHidden(target)) {
            add(DialogChoice("Unhide", TitleListAction(origin, scope) { TitleActions.unhide(target, it) }))
        } else {
            add(DialogChoice("Hide from this profile", TitleListAction(origin, scope) { TitleActions.hide(target, it) }))
        }
        if (!watchedBlocked) {
            val desired = watched != true
            if (target.isMovie) {
                val intent = com.strmr.tv.api.movieWatchedIntent(target, desired, origin.context)
                add(DialogChoice(if (desired) "Mark as watched" else "Mark as unwatched", TitleListAction(origin, scope) { operation ->
                    intent.bind(operation)?.let { TitleActions.setWatched(it) }
                }))
            } else {
                if (episode != null) {
                    val intent = com.strmr.tv.api.cardEpisodeWatchedIntent(target, episode, desired, origin.context)
                    add(DialogChoice(if (desired) "Mark ${episode.code} as watched" else "Mark ${episode.code} as unwatched", TitleListAction(origin, scope) { operation ->
                        intent.bind(operation)?.let { TitleActions.setWatched(it) }
                    }))
                }
                add(DialogChoice("Bulk mark as watched…", TitleListAction(origin, scope) { operation -> operation.publish { navigator.openDetails(target, openWatchState = true) } }))
            }
        }
        addAll(extra)
        add(DialogChoice("Cancel") {})
    }
    val bound = choices.map { choice -> DialogChoice(choice.label) {
        val action = choice.action
        if (action is TitleListAction) action.accept()?.let { action.commit(it, onDismiss) }
        else if (origin.isCurrent) { onDismiss(); action() }
    } }
    ChoiceDialog(title = card.title, message = sourceHint?.description(), choices = bound,
        dismissOnChoice = false, onDismiss = { if (origin.isCurrent) { selection.close(); onDismiss() } })
}
