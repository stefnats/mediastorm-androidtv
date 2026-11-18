package com.strmr.tv.launcher

import androidx.compose.runtime.snapshotFlow
import com.strmr.tv.api.*
import com.strmr.tv.app.App
import com.strmr.tv.app.AppLog
import com.strmr.tv.app.AppSession
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.TitleActions
import com.strmr.tv.library.WatchlistStore
import com.strmr.tv.library.readListsTabContinue
import com.strmr.tv.player.PlaybackCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Original requests and one serialized provider owner, independent of Android row identifiers. */
object LauncherSync {
    private val scope = MainScope()
    private val io = Dispatchers.IO
    private var ownership: LauncherOwnership? = null
    private var started = false
    private var homeHint: Pair<Long, Boolean>? = null

    fun start() {
        if (started) return
        started = true
        val reason = TvLauncher.unsupportedReason(App.context)
        if (reason != null) { AppLog.i(TvLauncher.TAG, "Launcher rows unavailable on this device"); return }
        val launcher = TvLauncher(App.context)
        val actor = LauncherOwnership(scope, io, object : LauncherProviderPort {
            override suspend fun write(payload: LauncherPayload) = launcher.write(payload)
            override suspend fun clear() = launcher.clear()
        }, load = ::load, report = { outcome ->
            when (outcome) {
                LauncherOutcome.WRITTEN -> AppLog.i(TvLauncher.TAG, "Launcher rows updated")
                LauncherOutcome.CLEARED -> AppLog.i(TvLauncher.TAG, "Launcher rows cleared")
                LauncherOutcome.FAILED -> AppLog.w(TvLauncher.TAG, "Launcher rows could not be updated")
            }
        })
        ownership = actor
        // A legacy profile-name preference cannot establish server/account ownership of stored rows.
        actor.clear()
        scope.launch { ProfileDataContext.changes.collect { actor.retireStale() } }
        scope.launch { PlaybackCoordinator.sessionsEnded.drop(1).collect { request("playback ended") } }
        scope.launch {
            AppSession.wakes.drop(1).collect {
                val expected = ProfileDataContext.current
                request("wake")
                if (AppSession.phase.value == AppSession.Phase.Ready) scope.launch { WatchlistStore.refresh(expected) }
            }
        }
        scope.launch { snapshotFlow { TitleActions.changes }.drop(1).collect { request("title action") } }
        scope.launch {
            snapshotFlow { WatchlistStore.isLoaded to WatchlistStore.items }.filter { it.first }.collect { request("watchlist") }
        }
    }

    fun homeLoaded(continueWatching: List<ContinueWatchingItem>?, hideWatched: Boolean, expected: Long) {
        val input = capture(expected, continueWatching, hideWatched) ?: return
        homeHint = expected to hideWatched
        ownership?.request(input)
    }

    /** Inputs and original URL basis are captured before the debounced job exists. */
    fun request(reason: String) {
        val expected = ProfileDataContext.current
        val input = capture(expected, null, homeHint?.takeIf { it.first == expected }?.second ?: false) ?: return
        ownership?.request(input)
    }

    /** Called synchronously before a newly activated profile is published. */
    fun profileActivated(id: String) { clear("profile activated") }

    fun clear(reason: String) { homeHint = null; ownership?.clear() }

    private fun capture(expected: Long, prefetched: List<ContinueWatchingItem>?, hideWatched: Boolean): LauncherSnapshot? {
        if (ownership == null || AppSession.phase.value != AppSession.Phase.Ready) return null
        return try {
            ApiClient.inContext(expected) {
                if (AppSession.profile.value?.id != ApiClient.profileId) return@inContext null
                LauncherSnapshot.capture(expected, prefetched, hideWatched)
            }
        } catch (_: CancellationException) { null }
    }

    private suspend fun load(input: LauncherSnapshot): LauncherPayload? {
        input.requireCurrent()
        val items = input.prefetched ?: readListsTabContinue(input.owner.epoch) { path, expected ->
            ApiClient.sendRaw("GET", path, expectedContext = expected)
        }
        currentCoroutineContext().ensureActive(); input.requireCurrent()
        if (!WatchlistStore.isLoaded) WatchlistStore.refresh(input.owner.epoch)
        currentCoroutineContext().ensureActive(); input.requireCurrent()
        if (!WatchlistStore.isLoaded || WatchlistStore.loadError != null) return null
        val saved = WatchlistStore.items.map { it.copy(externalIds = it.externalIds?.toMap(), backdropUrls = it.backdropUrls?.toList()) }
        return input.payload(items, saved)
    }
}
