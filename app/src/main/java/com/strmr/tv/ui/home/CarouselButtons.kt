package com.strmr.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.app.AppLog
import com.strmr.tv.home.CAROUSEL_LOG
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HeroActions
import com.strmr.tv.home.heroCta
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.SourceHintBadge
import com.strmr.tv.ui.rememberVisibleTitleSource
import com.strmr.tv.ui.rememberSourceHint
import com.strmr.tv.library.WatchlistStore
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.ui.LocalNavigator
import com.strmr.tv.ui.Page
import kotlinx.coroutines.launch

/**
 * Play / Resume (opens details and starts its primary action, as a home screen Play link does),
 * Watchlist (toggles in place, + or ✓) and Info (details). [onEdge] gets Left on Play (-1)
 * and Right on Info (+1), which change slides; it returns whether it took the key. Select within
 * 1 s of an auto-advance is dropped ([CarouselState.selectBlocked]), so it can't act on the
 * title that just replaced the one the press was meant for.
 */
@Composable
fun CarouselButtons(slide: HomeCard, state: CarouselState, focus: FocusRequester, owner: CarouselSetup, onEdge: (Int) -> Boolean) {
    val target = slide.target ?: return
    val navigator = LocalNavigator.current
    var focused by remember { mutableIntStateOf(-1) }
    val active = owner.context == ProfileDataContext.current && owner.parent.isActive && owner.selected() && owner.currentSlides().getOrNull(state.index) === slide
    val visibleSource = rememberVisibleTitleSource(slide, enabled = active)
    val source by rememberUpdatedState(visibleSource.source)
    val hint = rememberSourceHint(visibleSource.source)
    val cta = heroCta(slide.progress, hint)
    val actions = remember(owner, slide, active) {
        HeroActions(slide, owner.context, owner.parent, owner.selected,
            currentSlide = { owner.currentSlides().getOrNull(state.index) }, source = { source },
            prepareBeforeOpen = true, prepare = PlaybackCoordinator::prewarm,
            discard = { value, expected -> PlaybackCoordinator.invalidateWarmSources(value.titleId, expected) },
            open = { value, autoplay, _ -> navigator.open(Page.Details(value, autoplay = autoplay)) },
            openPlayback = { value, receipt -> navigator.open(Page.Details(value, autoplay = true, startupIntent = receipt)) })
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    /**
     * [focus] sits on the button last focused (held by [state]), so focusing the carousel again
     * (Up from the rows, or after a title action rebuilds Home's rows) lands where it was left.
     */
    fun button(i: Int) = Modifier
        .then(if (state.button == i) Modifier.focusRequester(focus) else Modifier)
        .onFocusChanged { if (it.isFocused) { focused = i; state.button = i } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.then(visibleSource.modifier).onPreviewKeyEvent { e ->
            e.type == KeyEventType.KeyDown && when (e.key) {
                Key.DirectionLeft -> focused == 0 && onEdge(-1)
                Key.DirectionRight -> focused == 2 && onEdge(1)
                else -> false
            }
        },
    ) {
        Button(
            onClick = {
                if (state.selectBlocked()) return@Button
                if (actions.primary(cta.confirmedMissing)) AppLog.i(CAROUSEL_LOG, "hero ${target.titleId}")
            },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = button(0),
        ) {
            Icon(if (cta.confirmedMissing) Icons.Outlined.Info else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(cta.label)
        }
        WatchlistToggle(target, slide, state, owner, button(1))
        IconButton(
            onClick = { if (!state.selectBlocked()) actions.details(cta.confirmedMissing) },
            modifier = button(2),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "Details")
        }
        hint?.let { SourceHintBadge(it) }
    }
}

/** One button whose icon flips, so focus stays on it; presses while a change is in flight are ignored. */
@Composable
private fun WatchlistToggle(target: DetailsTarget, slide: HomeCard, state: CarouselState, owner: CarouselSetup, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val active = owner.selected() && owner.currentSlides().getOrNull(state.index) === slide
    val operations = remember(owner.context, owner.parent, com.strmr.tv.library.CustomListSelectionKey(owner.shelf), com.strmr.tv.library.CustomListSelectionKey(slide), active) {
        com.strmr.tv.library.CustomListOperations(owner.context, target) {
            owner.parent.isActive && owner.selected() && owner.currentSlides().getOrNull(state.index) === slide
        }
    }
    androidx.compose.runtime.DisposableEffect(operations) { onDispose { operations.close() } }
    var busy by remember(operations) { mutableStateOf(false) }
    val inList = WatchlistStore.contains(target)
    IconButton(
        onClick = {
            if (busy || state.selectBlocked()) return@IconButton
            val operation = operations.begin(key = "watchlist") ?: return@IconButton
            val saved = !inList
            operation.publish { busy = true }
            scope.launch(owner.parent) {
                try { operations.run(operation) { WatchlistStore.setSaved(target, saved, it) } }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { operation.publish { com.strmr.tv.ui.AppToast.show(com.strmr.tv.library.watchlistFailureMessage(error)) } }
                finally { operation.publish { busy = false } }
            }
        },
        modifier = modifier,
    ) {
        Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (inList) "In Watchlist" else "Add to Watchlist")
    }
}
