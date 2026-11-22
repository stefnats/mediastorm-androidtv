package com.strmr.tv.rooms

import com.strmr.tv.api.ApiError
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RoomMediaState(val positionMs: Long, val durationMs: Long, val seekable: Boolean, val buffering: Boolean, val playing: Boolean)

/** Implemented by the actual shared PlayerController; automatic events are observations only. */
interface WatchRoomPlayerPort {
    fun sample(): RoomMediaState
    fun apply(positionMs: Long, playing: Boolean): Boolean
    fun pause()
    fun stop(message: String)
}

/** Exactly one original room/player. It never resolves media or supplies profile permission. */
class WatchRoomPlayback(
    private val gateway: WatchRoomGateway,
    initial: WatchRoom,
    private val scope: CoroutineScope = MainScope(),
    private val now: () -> Instant = Instant::now,
) {
    private var room = initial.checked()
    val roomId get() = room.id
    val request = WatchRoomTarget.request(initial)
    private var port: WatchRoomPlayerPort? = null
    private var poll: Job? = null
    private var heartbeat: Job? = null
    private var mutation: Job? = null
    private var closing: Job? = null
    private var closed = false
    private val write = Mutex()
    private val mutableNotice = MutableStateFlow<String?>("Waiting for the room and your copy…")
    val notice: StateFlow<String?> = mutableNotice
    fun current() = !closed && gateway.current() && room.alive(now()) &&
        room.member(gateway.profileId, gateway.clientId) != null

    fun bind(actual: WatchRoomPlayerPort) {
        check(port == null && current()) { "The original room is no longer available." }
        port = actual
        actual.pause()
        poll = scope.launch {
            while (isActive && !closed) {
                if (!current()) { fail("This room or viewer has ended."); break }
                try {
                    observe(gateway.read(room.id))
                    if (!current()) { fail("This room membership has ended."); break }
                    val sample = actual.sample()
                    val target = target(sample)
                    if (target == null) {
                        actual.pause()
                    } else {
                        if (room.member(gateway.profileId, gateway.clientId)?.ready != true && mutation?.isActive != true) {
                            // Ready is a mutation too: closing the polling job must not drop its ACK.
                            mutation = scope.launch {
                                write.withLock {
                                    if (current()) try { observe(gateway.ready(room.id, true)) }
                                    catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        mutableNotice.value = roomError(e)
                                        if ((e as? ApiError)?.statusCode in setOf(401, 403, 404, 410)) fail(roomError(e))
                                    }
                                }
                            }
                            mutation?.join()
                        }
                        if (!current()) { fail("This room membership has ended."); break }
                        apply(actual, sample)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    actual.pause()
                    mutableNotice.value = roomError(e)
                    if ((e as? ApiError)?.statusCode in setOf(401, 403, 404, 410)) { fail(roomError(e)); break }
                }
                delay(2_000)
            }
        }
        heartbeat = scope.launch {
            while (isActive && !closed) {
                delay(5_000)
                if (!current()) { fail("This room or viewer has ended."); break }
                try { gateway.heartbeat(room.id, actual.sample().buffering) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    actual.pause()
                    mutableNotice.value = roomError(e)
                    if ((e as? ApiError)?.statusCode in setOf(401, 403, 404, 410)) { fail(roomError(e)); break }
                }
            }
        }
    }
    /** Already projected by the server; anchorUpdatedAt is deliberately not added again. */
    internal fun target(sample: RoomMediaState): Long? {
        if (!sample.seekable || sample.durationMs <= 0) {
            mutableNotice.value = "Waiting for a seekable copy…"
            return null
        }
        if (room.duration > 0 && abs(roomMilliseconds(room.duration) - sample.durationMs) > 10_000) {
            mutableNotice.value = "These copies have different durations. Leave the room to choose another edition."
            return null
        }
        val position = roomMilliseconds(room.position)
        if (position >= sample.durationMs) {
            mutableNotice.value = "The room position is outside this copy. Waiting to resync…"
            return null
        }
        return position
    }
    private fun apply(actual: WatchRoomPlayerPort, sample: RoomMediaState) {
        val target = target(sample) ?: return
        if (room.waitingForReady || room.status == "lobby" || room.member(gateway.profileId, gateway.clientId)?.ready != true) {
            actual.pause(); mutableNotice.value = "Waiting for everyone to be ready…"; return
        }
        if (!actual.apply(target, room.status == "playing")) {
            actual.pause(); mutableNotice.value = "This position is not available yet. Waiting to resync…"; return
        }
        mutableNotice.value = "Watch Together · " + room.members.count { it.joined }.toString() + " viewers"
    }

    /** Only causal user controls call this; remote apply/Exo listeners must never call it. */
    fun command(playing: Boolean, positionMs: Long? = null) {
        val actual = port ?: return
        if (!current() || mutation?.isActive == true) return
        val sample = actual.sample()
        if (target(sample) == null || !sample.seekable) return
        val position = positionMs ?: sample.positionMs
        if (position !in 0 until sample.durationMs) return
        actual.pause()
        mutation = scope.launch {
            write.withLock {
                if (!current()) return@withLock
                try {
                    observe(gateway.state(room.id, if (playing) "playing" else "paused",
                        position / 1000.0, sample.durationMs / 1000.0, room.revision))
                    if (current()) apply(actual, actual.sample())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutableNotice.value = roomError(e)
                    // A conflict permits a read, never a replay of the old command.
                    if ((e as? ApiError)?.statusCode == 409) {
                        try { observe(gateway.read(room.id)); if (current()) apply(actual, actual.sample()) }
                        catch (read: Exception) { if (read is CancellationException) throw read; mutableNotice.value = roomError(read) }
                    } else if ((e as? ApiError)?.statusCode in setOf(401, 403, 404, 410)) fail(roomError(e))
                }
            }
        }
    }
    private fun observe(value: WatchRoom) {
        value.checked()
        check(value.id == room.id && value.itemId == room.itemId && value.params == room.params) {
            "The room's original title changed. Leave and join again."
        }
        if (value.revision >= room.revision) room = value
    }
    private fun fail(message: String) {
        if (closed) return
        mutableNotice.value = message
        port?.stop(message)
        close()
    }
    /** Stop caller joins before new player ownership; callbacks themselves never join. */
    fun close(): Job {
        closing?.let { return it }
        closed = true
        port = null
        return scope.launch {
            poll?.cancelAndJoin()
            heartbeat?.cancelAndJoin()
            mutation?.join()
            if (gateway.current()) {
                try { gateway.leave(room.id) }
                catch (_: Exception) { mutableNotice.value = "Leaving the room was not confirmed. Refresh before retrying." }
            }
        }.also { closing = it }
    }
}
