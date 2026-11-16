package com.strmr.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.strmr.tv.api.*
import com.strmr.tv.live.LiveStream
import com.strmr.tv.live.keepLiveSessionAlive
import com.strmr.tv.live.stopLiveSession
import kotlinx.coroutines.*

/** Native operation ordering and known resource receipts, never a server ownership grant. */
internal class PlayerHlsLifecycle(
    private val owner: PlaybackSessionContext,
    private val playerCurrent: () -> Boolean = { true },
    private val titleId: String? = null,
    private val onDenied: (VodAccessDenial) -> Unit = {},
    private val stopServer: suspend (String, Long) -> Unit = { id, expected -> ApiClient.stopLiveSession(id, expected) },
    private val strictStop: Boolean = false,
) {
    class Ticket internal constructor()
    class Stop internal constructor(val id: String, internal val ticket: Ticket)
    private var active = Ticket()
    private var closed = false
    private val known = mutableMapOf<String, Ticket>()
    private val detached = mutableSetOf<String>()
    private var assigned: Pair<String, Ticket>? = null
    fun current(ticket: Ticket = active) = !closed && ticket === active && owner.isCurrent() && playerCurrent()
    fun check(ticket: Ticket = active) { owner.requireCurrent(); if (!current(ticket)) throw CancellationException("HLS operation retired") }
    fun ticket(): Ticket = active
    fun begin(): Ticket? { if (!current()) return null; return Ticket().also { active = it } }
    fun invalidate() { active = Ticket() }
    fun close() { closed = true; invalidate() }
    fun remember(id: String?) { if (id != null) { known[id] = active; assigned = id to active } }
    fun detach(id: String?) { if (id != null) { detached += id; known.remove(id); if (assigned?.first == id) assigned = null } }
    fun receipt(id: String?): Stop? = id?.let { value -> known[value]?.let { Stop(value, it) } }
    fun <T> use(ticket: Ticket = active, action: () -> T): T = owner.use { check(ticket); action() }
    suspend fun stop(receipt: Stop?) {
        if (receipt == null || !owner.isCurrent() || receipt.id in detached || known[receipt.id] !== receipt.ticket) return
        // A later adopted use of the same ID owns it now; old cleanup cannot stop it.
        val replacement = assigned
        if (replacement?.first == receipt.id && replacement.second !== receipt.ticket) return
        try { stopServer(receipt.id, owner.epoch) } catch (error: CancellationException) { throw error } catch (error: Exception) { if (strictStop) throw error }
        if (known[receipt.id] === receipt.ticket) known.remove(receipt.id)
    }
    private fun failed(ticket: Ticket, attempt: VodAttempt?, error: Exception): Nothing {
        check(ticket)
        error.vodDenial?.let { reason ->
            close()
            attempt?.let { VodDenialEvents.reject(it, reason) }
            onDenied(reason)
            throw CancellationException("HLS access denied").apply { initCause(error) }
        }
        throw error
    }
    suspend fun <T> read(ticket: Ticket = active, request: suspend (Long) -> T): T {
        currentCoroutineContext().ensureActive(); check(ticket)
        val attempt = titleId?.let { VodDenialEvents.begin(it, owner.epoch) }
        return try {
            val value = request(owner.epoch)
            currentCoroutineContext().ensureActive(); check(ticket); value
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { currentCoroutineContext().ensureActive(); failed(ticket, attempt, error) }
    }
    /** Obtain a returned ID even on same-owner cancellation, then release only that receipt. */
    suspend fun <T> prepare(ticket: Ticket, request: suspend (Long) -> T, id: (T) -> String?): T {
        currentCoroutineContext().ensureActive(); check(ticket)
        val attempt = titleId?.let { VodDenialEvents.begin(it, owner.epoch) }
        val value = try { withContext(NonCancellable) { request(owner.epoch) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { currentCoroutineContext().ensureActive(); failed(ticket, attempt, error) }
        val resource = id(value)
        if (resource != null && owner.isCurrent() && resource !in detached && (current(ticket) || resource !in known)) known[resource] = ticket
        try {
            currentCoroutineContext().ensureActive(); check(ticket)
            assigned = resource?.let { it to ticket }
            return value
        } catch (error: CancellationException) {
            withContext(NonCancellable) { stop(resource?.let { Stop(it, ticket) }) }
            throw error
        }
    }
}

/** Growing recording HLS. Each restart belongs to this original player, not a later page/profile. */
@OptIn(UnstableApi::class)
class RecordingHls(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private var sessionId: String?,
    private val restart: suspend () -> LiveStream,
    expectedContext: Long = com.strmr.tv.library.ProfileDataContext.current,
    owner: PlaybackSessionContext = PlaybackSessionContext.capture(expectedContext),
    playerCurrent: () -> Boolean = { true },
    titleId: String? = null,
    private val onDenied: (VodAccessDenial) -> Unit = {},
) {
    private val lifetime = PlayerHlsLifecycle(owner, playerCurrent, titleId, { release(); onDenied(it) }).also { it.remember(sessionId) }
    private var restartJob: Job? = null
    private var restarting = false
    fun mediaItem(url: String): MediaItem = MediaItem.Builder().setUri(url)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build()).build()
    private val keepalive = scope.launch {
        while (isActive && lifetime.current()) {
            delay(20_000)
            try { ping() } catch (error: CancellationException) { if (!isActive || !lifetime.current()) throw error }
        }
    }
    private suspend fun ping(): Boolean? {
        val id = sessionId ?: return null
        return lifetime.read { expected -> ApiClient.keepLiveSessionAlive(id, player.currentPosition / 1000.0,
            paused = !player.playWhenReady, buffering = player.playbackState == Player.STATE_BUFFERING,
            expectedContext = expected, throwOnAccessDenial = true) }
    }
    fun ended(onFinished: () -> Unit) {
        if (restarting) return
        val ticket = lifetime.begin() ?: return
        restarting = true
        restartJob = scope.launch {
            try {
                if (ping() != true) { lifetime.use(ticket) { onFinished() }; return@launch }
                val at = lifetime.use(ticket) { player.currentPosition }
                val old = lifetime.receipt(sessionId)
                val next = lifetime.prepare(ticket, { restart() }, { it.sessionId })
                if (old?.id != next.sessionId) MainScope().launch { lifetime.stop(old) }
                lifetime.use(ticket) {
                    sessionId = next.sessionId
                    player.setMediaItem(mediaItem(next.url), at); player.playWhenReady = true; player.prepare()
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { if (lifetime.current(ticket)) lifetime.use(ticket) { onFinished() }
            } finally { if (lifetime.current(ticket)) restarting = false }
        }
    }
    fun release() {
        val stop = lifetime.receipt(sessionId)
        lifetime.close(); keepalive.cancel(); restartJob?.cancel(); sessionId = null
        MainScope().launch { lifetime.stop(stop) }
    }
}
