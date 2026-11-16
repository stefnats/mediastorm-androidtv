package com.strmr.tv.ui.live.multiscreen

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.VodAccessDenial
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.app.AppLog
import com.strmr.tv.live.EpgNowPlaying
import com.strmr.tv.live.LiveChannel
import com.strmr.tv.live.LiveStore
import com.strmr.tv.live.LiveStream
import com.strmr.tv.live.MultiscreenMemory
import com.strmr.tv.live.MultiscreenPrefs
import com.strmr.tv.live.MultiscreenSession
import com.strmr.tv.live.SavedMultiscreen
import com.strmr.tv.live.epgNow
import com.strmr.tv.live.keepLiveSessionAlive
import com.strmr.tv.live.liveUsage
import com.strmr.tv.live.startLive
import com.strmr.tv.live.stopLiveSessionStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The multiscreen screen's state around its [MultiscreenSession], a port of the Expo TV
 * `TVMultiscreen`: the enlarged tile, the control bar, the add / change channel picker, the
 * stability warning before a third screen (once per device), the stream-limit replacement
 * prompt and the exit that releases every stream. The channels on show are remembered for
 * Resume multiscreen ([resume] reopens them; [MultiscreenResume]). Main thread only.
 */
@Stable
class MultiscreenModel(launch: MultiscreenLaunch, private val finish: () -> Unit) {
    val ownership = launch.ownership.also { it.requireCurrent() }
    private val handover = launch.handover
    private val resume = launch.resume
    private var retired = false
    val isCurrent get() = !retired && ownership.isCurrent()
    fun currentSource(index: Int, source: com.strmr.tv.live.TileSource) = isCurrent && session.matches(index, source)
    fun tileDenied(index: Int, source: com.strmr.tv.live.TileSource, reason: VodAccessDenial) { session.deny(index, source, reason) }
    fun tileFailed(index: Int, source: com.strmr.tv.live.TileSource) { if (isCurrent) session.fail(index, source) }
    private fun deny(reason: VodAccessDenial) { retire(reason.message) }
    private fun retire(message: String) {
        if (retired) return
        retired = true; session.retire(); selections.cancel(); afterWarning = null
        picker = false; warning = false; replacement = null; epg = emptyMap(); streamInfo.clear(); exitError = message; actions = true
        scope.cancel()
        sessionScope.launch { runCatching { session.close() } }
    }
    /** Outlives the screen, so the sessions are still released when the activity goes first. */
    private val sessionScope = MainScope()
    private val scope = MainScope()

    private val transport = object : MultiscreenSession.Transport {
        override suspend fun start(channel: LiveChannel): LiveStream = start(channel) { ownership.requireCurrent() }
        override suspend fun start(channel: LiveChannel, check: () -> Unit): LiveStream = readMultiscreenStart(
            ownership, check,
            capacity = { ApiClient.liveUsage(channel.sourceId, it).hasCapacity },
            open = { ApiClient.startLive(channel, channel.name, forceHls = true, expectedContext = it) },
        )
        override suspend fun stop(sessionId: String) = ApiClient.stopLiveSessionStrict(sessionId, ownership.epoch)
    }
    val session = MultiscreenSession(transport, sessionScope, ownership = ownership, onDenied = ::deny)
    /** The picker remains bound to the launch that opened it. */
    val store = LiveStore(scope, ownership.epoch)

    var expanded by mutableStateOf<Int?>(null)
    var actions by mutableStateOf(false)
    var warning by mutableStateOf(false)
    var replacement by mutableStateOf<LiveChannel?>(null)
    /** Why Add Screen can't add one (the screen cap or this box's decoders). */
    var notice by mutableStateOf<String?>(null)
    var picker by mutableStateOf(false)
    /** The picker adds a screen (rather than changing the focused one's channel). */
    private var adding by mutableStateOf(false)
    /** Not yet showing two channels: closing the picker leaves multiscreen (from the Live tab it chooses both). */
    private var initialSetup by mutableStateOf(false)
    /** Opened from the player, whose channel is screen 1: the picker adds the second. */
    private val fromPlayer = handover != null
    var pickerError by mutableStateOf<String?>(null)
    var exitError by mutableStateOf<String?>(null)
    var exiting by mutableStateOf(false)
    var lastInput by mutableLongStateOf(System.currentTimeMillis())
    /** Now / next by guide key, for the tiles' channels. */
    var epg by mutableStateOf<Map<String, EpgNowPlaying>>(emptyMap())
    /** What each tile's player reports (its video type feeds [DecoderLimits]). */
    val streamInfo = mutableStateMapOf<Int, TileStreamInfo>()
    /** What the warning's Continue / Cancel go on to. */
    private var afterWarning: Pair<() -> Unit, () -> Unit>? = null
    private val selections = MultiscreenSelections()

    private val state get() = session.state.value
    private val occupied get() = state.slots.indices.filter { state.slots[it].isOccupied }

    init {
        // Logged for the record: what this box says it can decode at once.
        AppLog.i("Multiscreen", "device screen limit: h264=${DecoderLimits.instances(MimeTypes.VIDEO_H264)} " +
            "hevc=${DecoderLimits.instances(MimeTypes.VIDEO_H265)}")
        if (handover != null && handover.ownership === ownership) {
            if (handover.stream != null) session.adopt(handover.channel, handover.stream) else session.tune(0, handover.channel)
            AppLog.i("Multiscreen", "from the player: ${handover.channel.name} (${if (handover.stream != null) "session kept" else "retuned"})")
            session.focus(1)
            adding = true
            initialSetup = true
            picker = true
        } else if (resume == null) {
            adding = true
            initialSetup = true
            picker = true
        }
        MultiscreenResume(session, scope, selections, screenLimit = { screenLimit }, warn = ::showWarning,
                          stopped = { if (isCurrent) { exitError = it; actions = true } }, saved = resume, ownership = ownership, current = { isCurrent }, onDenied = ::deny)
        scope.launch {
            while (isActive && isCurrent) {
                delay(KEEPALIVE_MS)
                state.slots.forEachIndexed { index, slot ->
                    val source = slot.source ?: return@forEachIndexed
                    try { session.keepAlive(index, source) { id, expected -> ApiClient.keepLiveSessionAlive(id, expectedContext = expected, throwOnAccessDenial = true) } }
                    catch (error: CancellationException) { currentCoroutineContext().ensureActive(); if (!isCurrent) throw error }
                }
            }
        }
        scope.launch { ProfileDataContext.changes.collect { if (!ownership.isCurrent()) { retire("Profile changed. Reopen Multiscreen from Live TV."); finish() } } }
    }

    /** The picker's title, for the screen `focused`. */
    fun pickerTitle(focused: Int): String = when {
        adding && (fromPlayer || !initialSetup) -> "Add channel"
        adding -> if (focused == 0) "Choose first channel" else "Choose second channel"
        else -> "Choose channel · Screen ${focused + 1}"
    }

    /** At most four screens, fewer when this box can't decode that many videos at once. */
    val screenLimit: Int
        get() = minOf(MultiscreenSession.MAX_SCREENS, DecoderLimits.screens(streamInfo.values.mapNotNull { it.videoMime }.toSet()))

    fun reveal() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        lastInput = System.currentTimeMillis()
    }

    suspend fun refreshEpg(keys: List<String>) {
        if (!isCurrent || keys.isEmpty()) return
        try {
            val list = ApiClient.epgNow(keys, ownership.epoch)
            currentCoroutineContext().ensureActive()
            ownership.use { if (isCurrent) epg = epg + list.associateBy { it.channelId } }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { if (isCurrent) error.vodDenial?.let { session.deny(it) } }
    }

    // Remote

    /** Select on a tile: the controls for one not playing, else listen to it, else enlarge it. */
    fun select(index: Int) {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        reveal()
        session.focus(index)
        val slot = state.slots.getOrNull(index) ?: return
        when {
            slot.source == null || slot.error != null || slot.loading -> actions = true
            state.audioIndex != index -> session.listen(index)
            else -> expanded = index
        }
    }

    /** An arrow over the tiles; Down with no tile below opens the control bar. */
    fun arrow(direction: TileDirection, frames: Map<Int, TileFrame>) {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        reveal()
        val next = MultiscreenLayout.neighbor(frames, state.focusedIndex, direction)
        if (next != null) session.focus(next) else if (direction == TileDirection.DOWN) actions = true
    }

    /** Back: the control bar, then the enlarged tile, then out of multiscreen. Dialogs take their own. */
    fun back() {
        reveal()
        when {
            actions -> actions = false
            expanded != null -> expanded = null
            else -> exit()
        }
    }

    // Control bar

    fun addScreen() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        actions = false
        val limit = screenLimit
        when {
            occupied.size >= limit -> notice = if (limit >= MultiscreenSession.MAX_SCREENS) {
                "Multiscreen shows up to ${MultiscreenSession.MAX_SCREENS} screens. Remove one to add another."
            } else {
                "This device can decode $limit videos at once. Remove a screen to add another."
            }
            MultiscreenMemory.needsWarning(occupied.size, MultiscreenPrefs.warningShown) ->
                showWarning(onContinue = ::beginAdd, onCancel = { actions = true })
            else -> beginAdd()
        }
    }

    /** The warning is never shown again on this device, from the moment it first shows. */
    private fun showWarning(onContinue: () -> Unit, onCancel: () -> Unit) {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        MultiscreenPrefs.markWarningShown()
        afterWarning = onContinue to onCancel
        warning = true
    }

    fun acceptWarning() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        warning = false
        afterWarning?.first?.invoke()
        afterWarning = null
    }

    /**
     * Cancel or Back on the warning: back to the control bar (on resume, the extra screens aren't
     * added). The dialog dismisses before any choice's action, so this waits a turn: Continue
     * ([acceptWarning]) has taken the continuation by then.
     */
    fun dismissWarning() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        warning = false
        scope.launch {
            if (!isCurrent) return@launch
            afterWarning?.second?.invoke()
            afterWarning = null
        }
    }

    private fun beginAdd() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        adding = true
        actions = false
        pickerError = null
        picker = true
    }

    fun changeChannel() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        actions = false
        adding = false
        pickerError = null
        picker = true
    }

    fun retry() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        val index = state.focusedIndex
        state.slots[index].channel?.let { session.tune(index, it) }
        actions = false
    }

    fun remove() {
        if (!isCurrent || exiting) return
        val index = state.focusedIndex
        actions = false
        val removal = session.remove(index)
        scope.launch {
            removal.await()
            if (!isCurrent || state.slots[index].channel != null) return@launch
            val remaining = maxOf(0, state.slots.indexOfFirst { it.channel != null })
            session.focus(remaining)
            expanded = remaining.takeIf { state.slots.count { it.channel != null } == 1 && state.slots[remaining].source != null }
        }
    }

    /** The control bar's Enlarge: offered for the focused screen while it plays. */
    fun canEnlarge(state: MultiscreenSession.State): Boolean {
        val slot = state.slots.getOrNull(state.focusedIndex) ?: return false
        return isCurrent && slot.source != null && slot.error == null && !slot.loading
    }

    /** Enlarge (the focused screen) or, with one enlarged, Equal tiles. */
    fun toggleEnlarged() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        expanded = if (expanded != null) null else state.focusedIndex.takeIf { canEnlarge(state) }
        actions = false
    }

    fun replace(index: Int, channel: LiveChannel) {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        session.tune(index, channel)
        session.focus(index)
        replacement = null
        expanded = null
    }

    // Picker

    fun closePicker() {
        if (!isCurrent || exiting) return
        if (!isCurrent) return
        selections.cancel()
        picker = false
        reveal()
        if (initialSetup && adding && state.slots[1].channel == null) exit() else adding = false
    }

    fun choose(channel: LiveChannel) {
        if (!isCurrent || selections.busy) return
        val duplicate = session.indexOf(channel)
        if (duplicate != -1) {
            if (initialSetup && adding && state.slots[1].channel == null) return
            session.focus(duplicate)
            adding = false
            picker = false
            return
        }
        val empty = state.slots.indexOfFirst { !it.isOccupied }
        val target = if (adding) (if (empty == -1) state.slots.size else empty) else state.focusedIndex
        val generation = selections.begin()
        scope.launch {
            try {
                if (!isCurrent || !selections.isCurrent(generation)) return@launch
                // Checked before a screen is added; a refused or failed check leaves the others alone.
                if (adding) {
                    val usage = ApiClient.liveUsage(channel.sourceId, ownership.epoch)
                    if (!isCurrent || !selections.isCurrent(generation)) return@launch
                    if (!usage.hasCapacity) {
                        replacement = channel
                        adding = false
                        picker = false
                        return@launch
                    }
                }
                if (!isCurrent || !selections.isCurrent(generation)) return@launch
                session.tune(target, channel)
                session.focus(target)
                if (initialSetup && adding && target == 0 && state.slots[1].channel == null) {
                    session.focus(1)
                } else {
                    initialSetup = false
                    reveal()
                    adding = false
                    expanded = null
                    picker = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrent && selections.isCurrent(generation)) e.vodDenial?.let { session.deny(it); return@launch }
                AppLog.w("Multiscreen", "capacity check failed")
                if (isCurrent && selections.isCurrent(generation)) pickerError = "Unable to check stream capacity. Retry before adding a screen."
            } finally {
                selections.end(generation)
            }
        }
    }

    // Exit

    /** Releases every stream and closes; a failed release stays, with the reason in the control bar. */
    fun exit() {
        if (exiting) return
        selections.cancel()
        session.retire()
        exiting = true
        sessionScope.launch {
            try {
                session.close()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Multiscreen", "unable to release streams")
                exitError = "Unable to release all streams. Select Exit to retry."
                actions = true
            } finally {
                exiting = false
            }
        }
    }

    /** The activity is going (Back, Home, sleep): release whatever is still open, then let go. */
    fun dispose() {
        retired = true; session.retire(); selections.cancel(); afterWarning = null
        scope.cancel()
        sessionScope.launch {
            withContext(NonCancellable) {
                runCatching { session.close() }.onFailure { AppLog.w("Multiscreen", "unable to release streams") }
            }
            sessionScope.cancel()
        }
    }

    private companion object {
        const val KEEPALIVE_MS = 10_000L
    }
}

/** Actual capacity-to-start adapter. No late operation may prepare under a replacement owner. */
internal suspend fun readMultiscreenStart(
    owner: PlaybackSessionContext, check: () -> Unit, capacity: suspend (Long) -> Boolean, open: suspend (Long) -> LiveStream,
): LiveStream {
    currentCoroutineContext().ensureActive(); owner.use { check() }
    val available = capacity(owner.epoch)
    currentCoroutineContext().ensureActive(); owner.use { check() }
    kotlin.check(available) { "Provider connection limit reached" }
    val stream = open(owner.epoch)
    owner.requireCurrent()
    return stream
}
