package com.strmr.tv.live

import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.player.PlayerHlsLifecycle
import com.strmr.tv.api.VodAccessDenial
import com.strmr.tv.api.vodDenial
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a multiscreen tile plays. Compared by identity: a retune of the same URL is a new source. */
class TileSource(val url: String) {
    override fun toString() = "TileSource($url)"
}

/** One screen. Empty (no channel, not loading) until a channel is tuned into it. */
data class MultiscreenSlot(
    val channel: LiveChannel? = null,
    val source: TileSource? = null,
    /** The server HLS session, kept even when the start then fails, so cleanup can retry it. */
    val sessionId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isOccupied get() = channel != null || loading
}

/**
 * The multiscreen's screens, a port of the Expo TV `TVMultiscreenSession`: slot indices are
 * stable (only their rectangles change), each slot owns its server session, and every start,
 * removal and close runs through one queue so the transport can recheck the provider's
 * capacity before each start. A per-slot version makes a superseded tune give way to the
 * newer one; a slot's old session is released before its new one starts. At most [maxSlots]
 * screens (the Expo TV screen had no cap). Main thread only: [scope] runs the queue.
 */
class MultiscreenSession(
    private val transport: Transport,
    scope: CoroutineScope,
    private val maxSlots: Int = MAX_SCREENS,
    val ownership: PlaybackSessionContext = PlaybackSessionContext.capture(),
    private val onDenied: (VodAccessDenial) -> Unit = {},
) {
    interface Transport {
        /** Opens `channel` as an HLS session; an empty URL is a start that returned nothing playable. */
        suspend fun start(channel: LiveChannel): LiveStream
        /** Production checks the original operation again between capacity and actual start. */
        suspend fun start(channel: LiveChannel, check: () -> Unit): LiveStream { check(); return start(channel) }
        /** Stops a session; throws when the server didn't confirm. */
        suspend fun stop(sessionId: String)
    }

    data class State(val slots: List<MultiscreenSlot>, val audioIndex: Int, val focusedIndex: Int)

    private val slots = mutableListOf(MultiscreenSlot(), MultiscreenSlot())
    private var audioIndex = 0
    private var focusedIndex = 0
    private var closed = false
    private var denied = false
    val isCurrent get() = !closed && !denied && ownership.isCurrent()
    private val lifetimes = mutableMapOf<Int, PlayerHlsLifecycle>()
    private val returnedIds = mutableMapOf<Int, MutableSet<String>>()
    private fun lifetime(index: Int): PlayerHlsLifecycle = lifetimes.getOrPut(index) {
        PlayerHlsLifecycle(ownership, { isCurrent }, onDenied = ::deny,
            stopServer = { id, _ ->
                // A different slot may have adopted this opaque ID in the meantime.
                if (slots.indices.none { it != index && slots[it].sessionId == id }) transport.stop(id)
            }, strictStop = true)
    }
    fun matches(index: Int, source: TileSource): Boolean = isCurrent && slots.getOrNull(index)?.let { it.source === source && it.error == null && !it.loading } == true
    fun retire() {
        closed = true
        versions.indices.forEach { versions[it]++ }
        lifetimes.values.forEach { it.close() }
        slots.indices.forEach { i -> slots[i] = slots[i].copy(source = null, loading = false) }
        publish()
    }
    fun deny(reason: VodAccessDenial) {
        if (!isCurrent) return
        denied = true; retire()
        slots.indices.forEach { i -> slots[i] = slots[i].copy(source = null, loading = false, error = reason.message) }
        publish(); onDenied(reason)
    }
    /** A retired tile's late error cannot deny or fail a replacement source. */
    fun deny(index: Int, source: TileSource, reason: VodAccessDenial) { if (matches(index, source)) deny(reason) }
    suspend fun keepAlive(index: Int, source: TileSource, request: suspend (String, Long) -> Unit) {
        if (!matches(index, source)) return
        val id = slots[index].sessionId ?: return
        val life = lifetime(index); val ticket = life.ticket()
        life.read(ticket) { expected -> request(id, expected) }
    }
    private val versions = mutableListOf(0, 0)
    /** The channel each slot's newest tune asked for, while it is in flight (duplicates count it). */
    private val requested = mutableListOf<LiveChannel?>(null, null)
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<State> = _state

    private class Operation(val run: suspend () -> Unit, val cancel: () -> Unit)
    private val queue = Channel<Operation>(Channel.UNLIMITED)

    init {
        scope.launch {
            try { for (operation in queue) operation.run() }
            finally { queue.close(); while (true) { val pending = queue.tryReceive().getOrNull() ?: break; pending.cancel() } }
        }
    }

    private fun snapshot() = State(slots.toList(), audioIndex, focusedIndex)

    private fun publish() {
        _state.value = snapshot()
    }

    private fun <T> enqueue(operation: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        val pending = Operation(run = {
            try { result.complete(operation()) }
            catch (e: CancellationException) {
                result.cancel(e)
                // Retiring one operation must not kill the cleanup queue.
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) { result.completeExceptionally(e) }
        }, cancel = { result.cancel(CancellationException("Multiscreen closed")) })
        if (queue.trySend(pending).isFailure) pending.cancel()
        return result
    }

    private suspend fun release(index: Int) {
        val life = lifetime(index)
        val ids = (returnedIds[index].orEmpty() + listOfNotNull(slots[index].sessionId)).toList()
        for (id in ids) {
            try { life.stop(life.receipt(id)) }
            catch (error: Exception) {
                error.vodDenial?.let { reason ->
                    life.detach(id); returnedIds[index]?.remove(id)
                    slots[index] = slots[index].copy(sessionId = null, source = null)
                    if (isCurrent) deny(reason)
                }
                throw error
            }
            returnedIds[index]?.remove(id)
        }
        slots[index] = MultiscreenSlot()
    }

    private fun valid(index: Int) = index in slots.indices

    /** The slot showing (or tuning) `channel`, or -1. */
    fun indexOf(channel: LiveChannel): Int = slots.indices.firstOrNull { index ->
        val candidate = requested[index] ?: slots[index].channel
        candidate != null && candidate.id == channel.id && candidate.sourceId == channel.sourceId
    } ?: -1

    /**
     * Takes over a stream another player opened (the full-screen live player's HLS session) as
     * the first screen; this session stops it like its own. Ignored once the first screen is in use.
     */
    fun adopt(channel: LiveChannel, stream: LiveStream) {
        if (!isCurrent || slots[0].isOccupied) return
        lifetime(0).remember(stream.sessionId)
        slots[0] = MultiscreenSlot(channel, TileSource(stream.url), stream.sessionId)
        publish()
    }

    fun focus(index: Int) {
        if (!isCurrent || !valid(index)) return
        focusedIndex = index
        publish()
    }

    /** Moves the sound to `index`, if it is playing something. */
    fun listen(index: Int) {
        if (!isCurrent || slots.getOrNull(index)?.source == null) return
        audioIndex = index
        publish()
    }

    /**
     * Tunes `channel` into slot `index` (one past the last adds a screen). A channel already on
     * another screen focuses that screen instead.
     */
    fun tune(index: Int, channel: LiveChannel): Deferred<Unit> {
        if (!isCurrent || index < 0 || index > slots.size || index >= maxSlots) return done()
        val duplicate = indexOf(channel)
        if (duplicate != -1 && duplicate != index) {
            focus(duplicate)
            return done()
        }
        if (index == slots.size) {
            slots += MultiscreenSlot()
            versions += 0
            requested += null
        }
        val life = lifetime(index)
        val ticket = life.begin() ?: return done()
        val version = ++versions[index]
        requested[index] = channel
        slots[index] = slots[index].copy(loading = true, error = null)
        publish()
        return enqueue {
            if (!isCurrent || version != versions[index]) return@enqueue
            try {
                release(index)
                if (!isCurrent || version != versions[index]) return@enqueue
                slots[index] = MultiscreenSlot(channel, loading = true)
                publish()
                val prepared = life.prepare(ticket, {
                    transport.start(channel) { life.check(ticket) }.also { stream ->
                        stream.sessionId?.let { returnedIds.getOrPut(index) { mutableSetOf() }.add(it) }
                    }
                }, { it.sessionId })
                if (prepared.sessionId != null && slots.indices.any { it != index && slots[it].sessionId == prepared.sessionId }) {
                    throw IllegalStateException("Session already assigned to another screen")
                }
                // Kept even if stale, so a failed cleanup can be retried on exit.
                slots[index] = MultiscreenSlot(channel, sessionId = prepared.sessionId)
                if (prepared.url.isEmpty()) {
                    release(index)
                    throw IllegalStateException("No playable stream returned")
                }
                if (!isCurrent || version != versions[index]) {
                    release(index)
                    return@enqueue
                }
                slots[index] = MultiscreenSlot(channel, TileSource(prepared.url), prepared.sessionId)
                prepared.sessionId?.let { returnedIds[index]?.remove(it) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (version == versions[index]) { requested[index] = null; slots[index] = slots[index].copy(source = null, loading = false); publish() }
                // Superseding a tune is a completed selection change, not parent cancellation.
                return@enqueue
            } catch (e: Exception) {
                if (isCurrent && version == versions[index]) slots[index] = slots[index].copy(channel = slots[index].channel ?: channel, loading = false, error = TUNE_FAILED)
            }
            if (version == versions[index]) requested[index] = null
            publish()
        }
    }

    /** The player of slot `index` failed; ignored once the slot has moved on to another source. */
    fun fail(index: Int, source: TileSource) {
        val slot = slots.getOrNull(index) ?: return
        if (!matches(index, source)) return
        slots[index] = slot.copy(error = STREAM_FAILED)
        publish()
    }

    /** Empties slot `index`; its sound moves to the first screen still playing. */
    fun remove(index: Int): Deferred<Unit> {
        if (!isCurrent || !valid(index)) return done()
        lifetime(index).invalidate()
        ++versions[index]
        slots[index] = slots[index].copy(source = null, loading = false)
        publish()
        return enqueue {
            try {
                release(index)
                requested[index] = null
                if (audioIndex == index) audioIndex = maxOf(0, slots.indexOfFirst { it.source != null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                slots[index] = slots[index].copy(error = RELEASE_FAILED)
            }
            publish()
        }
    }

    /**
     * Supersedes every pending tune and releases every session; throws if any release failed
     * (the released ones are empty, so calling it again retries only the rest).
     */
    suspend fun close() {
        retire()
        enqueue {
            val failed = coroutineScope {
                slots.indices.map { index ->
                    async {
                        try {
                            release(index)
                            false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            true
                        }
                    }
                }.awaitAll()
            }
            publish()
            if (failed.any { it }) throw IllegalStateException("Unable to release all multiscreen sessions")
        }.await()
    }

    private fun done(): Deferred<Unit> = CompletableDeferred(Unit)

    companion object {
        const val MAX_SCREENS = 4
        const val TUNE_FAILED = "Unable to tune. Retry or choose another channel."
        const val STREAM_FAILED = "Stream unavailable. Retry or choose another channel."
        const val RELEASE_FAILED = "Unable to release stream. Try again."
    }
}
