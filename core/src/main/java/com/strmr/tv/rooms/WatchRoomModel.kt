package com.strmr.tv.rooms

import com.strmr.tv.api.ApiError
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.PlaybackSessionContext
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

data class WatchRoomViewState(
    val rooms: List<WatchRoom> = emptyList(),
    val invitations: List<WatchRoomAccountInvite> = emptyList(),
    val outgoing: List<WatchRoomAccountInvite> = emptyList(),
    val room: WatchRoom? = null,
    val busy: Boolean = false,
    val notice: String? = null,
)

/** Shared lobby state. The finite admitted task, not a disappearing Compose screen, owns ACK cleanup. */
class WatchRoomModel(
    val gateway: WatchRoomGateway,
    private val scope: CoroutineScope = MainScope(),
) {
    private val mutable = MutableStateFlow(WatchRoomViewState())
    val state: StateFlow<WatchRoomViewState> = mutable
    private var operation: Job? = null
    private var polling: Job? = null
    private var closing: Job? = null
    private var closed = false
    private var transferred = false
    private var created = false
    private var knownRoom: WatchRoom? = null
    val profileId get() = gateway.profileId
    val creator get() = knownRoom?.creatorProfileId == profileId
    fun current() = !closed && !transferred && gateway.current()

    private fun publish(change: (WatchRoomViewState) -> WatchRoomViewState) {
        if (current()) mutable.value = change(mutable.value)
    }
    private fun remember(room: WatchRoom): WatchRoom {
        room.checked()
        knownRoom = room
        if (!gateway.current()) throw CancellationException("The original viewer changed.")
        publish { it.copy(room = room) }
        return room
    }
    private fun run(block: suspend () -> Unit) {
        if (!current() || operation?.isActive == true) return
        publish { it.copy(busy = true, notice = null) }
        operation = scope.launch {
            try { polling?.cancelAndJoin(); polling = null; block() }
            catch (e: Exception) {
                if (e !is CancellationException) publish { it.copy(notice = roomError(e)) }
            } finally {
                publish { it.copy(busy = false) }
                if (current() && knownRoom != null) watch()
            }
        }
    }
    fun refresh() = run {
        val rooms = gateway.inbox()
        val invites = gateway.accountInbox()
        publish { it.copy(rooms = rooms, invitations = invites) }
    }
    fun create(request: PlaybackRequest, invitees: List<String>) = run {
        val body = WatchRoomTarget.create(request, invitees, gateway.clientId)
        val room = gateway.create(body)
        created = true
        remember(room)
    }
    fun join(id: String) = run { remember(gateway.join(id)) }
    fun accept(id: String) = run { remember(gateway.accept(id)); remember(gateway.join(requireNotNull(knownRoom).id)) }
    fun decline(id: String) = run {
        gateway.decline(id)
        val invites = gateway.accountInbox()
        publish { it.copy(invitations = invites) }
    }
    fun invite(username: String) = run {
        val room = requireNotNull(knownRoom)
        require(username.isNotBlank()) { "Enter your friend's account username." }
        gateway.invite(room.id, username)
        val invites = gateway.outgoing(room.id)
        publish { it.copy(outgoing = invites, notice = "Invitation sent.") }
    }
    fun revoke(id: String) = run {
        val room = requireNotNull(knownRoom)
        gateway.revoke(room.id, id)
        val invites = gateway.outgoing(room.id)
        publish { it.copy(outgoing = invites) }
    }
    fun startTogether() = run {
        val room = requireNotNull(knownRoom)
        remember(gateway.state(room.id, "playing", room.position, room.duration, room.revision))
    }
    fun end() = run {
        val room = requireNotNull(knownRoom)
        gateway.end(room.id)
        knownRoom = null
        publish { it.copy(room = null, notice = "Room ended.") }
    }
    private fun watch() {
        polling = scope.launch {
            while (isActive && current()) {
                delay(2_000)
                val room = knownRoom ?: break
                try {
                    remember(gateway.read(room.id))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    publish { it.copy(notice = roomError(e)) }
                    if ((e as? ApiError)?.statusCode in setOf(401, 403, 404, 410)) break
                }
            }
        }
    }

    /** Transfer only after actual lobby I/O has joined, before any media resolve. */
    suspend fun playback(owner: PlaybackSessionContext): WatchRoomPlayback {
        check(current() && operation?.isActive != true) { "Wait for the room operation to finish." }
        polling?.cancelAndJoin(); polling = null
        owner.requireCurrent()
        val room = requireNotNull(knownRoom)
        check(current() && owner.profileId == gateway.profileId && room.alive())
        check(room.member(gateway.profileId, gateway.clientId) != null) { "Join this room on this device first." }
        WatchRoomTarget.request(room)
        transferred = true
        return WatchRoomPlayback(gateway, room)
    }
    fun close(): Job {
        closing?.let { return it }
        closed = true
        return scope.launch {
            polling?.cancelAndJoin()
            operation?.join()
            val room = knownRoom
            if (!transferred && room != null && gateway.current()) {
                try { if (created) gateway.end(room.id) else gateway.leave(room.id) }
                catch (_: Exception) { mutable.value = mutable.value.copy(notice = "Room cleanup was not confirmed. Refresh before retrying.") }
            }
        }.also { closing = it }
    }
}

internal fun roomError(error: Exception): String = when ((error as? ApiError)?.statusCode) {
    409 -> "The room changed. Refresh and choose the action again."
    410 -> "This room has ended. Create or join a new room."
    503 -> "The room operation could not be confirmed. Refresh before retrying."
    else -> error.message ?: "Watch Together is unavailable."
}
