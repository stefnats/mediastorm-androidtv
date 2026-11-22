package com.strmr.tv.rooms

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.player.PlaybackRequest
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal object RoomFixtures {
    fun room(position: Double = 40.0, status: String = "paused", revision: Long = 4) = WatchRoom(
        "room-one", "viewer", "Host", "Synthetic movie", "movie", "tmdb:42",
        params = mapOf("titleId" to "tmdb:42", "titleName" to "Synthetic movie", "mediaType" to "movie"),
        status = status, waitingForReady = false, position = position, duration = 100.0, revision = revision,
        anchorUpdatedAt = "2026-11-22T12:00:00Z", expiresAt = "2200-01-01T00:00:00Z",
        members = listOf(WatchRoomMember("viewer", "Host", "client-one", isCreator = true, ready = true, joined = true)),
    )
    fun request() = PlaybackRequest(DetailsTarget("tmdb:42", "Synthetic movie", true))
    val now: Instant = Instant.parse("2026-11-22T12:00:10Z")
}

internal open class FakeRooms : WatchRoomGateway {
    override val profileId = "viewer"
    override val clientId = "client-one"
    var valid = true
    var room = RoomFixtures.room()
    val calls = mutableListOf<String>()
    var createGate: CompletableDeferred<Unit>? = null
    var stateGate: CompletableDeferred<Unit>? = null
    var failure: Exception? = null
    var stateFailure: Exception? = null
    var account = listOf(WatchRoomAccountInvite("invite-one", room.id, title = room.title, status = "pending", expiresAt = room.expiresAt))
    override fun current() = valid
    override suspend fun inbox(): List<WatchRoom> { calls += "inbox"; failure?.let { throw it }; return listOf(room) }
    override suspend fun accountInbox(): List<WatchRoomAccountInvite> { calls += "account-inbox"; return account }
    override suspend fun create(body: JsonObject): WatchRoom { calls += "create"; createGate?.await(); return room }
    override suspend fun read(id: String): WatchRoom { calls += "read"; failure?.let { throw it }; return room }
    override suspend fun join(id: String): WatchRoom { calls += "join"; return room }
    override suspend fun ready(id: String, value: Boolean): WatchRoom {
        calls += "ready"
        room = room.copy(members = room.members.map { it.copy(ready = value) })
        return room
    }
    var lastRevision: Long? = null
    override suspend fun state(id: String, status: String, position: Double, duration: Double, revision: Long): WatchRoom {
        calls += "state"; lastRevision = revision; stateGate?.await(); stateFailure?.let { throw it }
        check(revision == room.revision)
        room = room.copy(status = status, position = position, duration = duration, revision = revision + 1)
        return room
    }
    override suspend fun heartbeat(id: String, buffering: Boolean) { calls += "heartbeat" }
    override suspend fun leave(id: String) { calls += "leave" }
    override suspend fun end(id: String) { calls += "end" }
    override suspend fun invite(id: String, username: String): WatchRoomAccountInvite {
        calls += "invite:" + username
        return account.single()
    }
    override suspend fun outgoing(id: String): List<WatchRoomAccountInvite> { calls += "outgoing"; return account }
    override suspend fun accept(id: String): WatchRoom { calls += "accept"; return room }
    override suspend fun decline(id: String) { calls += "decline"; account = emptyList() }
    override suspend fun revoke(room: String, invite: String) { calls += "revoke"; account = emptyList() }
}

class WatchRoomWireTest {
    @Test fun unjoinedMemberMayOmitClientId() {
        val member = ApiJson.decodeFromString<WatchRoomMember>("""{"profileId":"invited","joined":false}""")
        assertNull(member.clientId)
        assertEquals(2, RoomFixtures.room().copy(members = RoomFixtures.room().members + member).checked().members.size)
    }
    @Test fun joinedMemberMustMatchOriginalClient() {
        val room = RoomFixtures.room()
        assertNotNull(room.member("viewer", "client-one"))
        assertNull(room.member("viewer", "another-device"))
        assertNull(room.member("another-profile", "client-one"))
        assertNull(room.copy(members = room.members.map { it.copy(joined = false) }).member("viewer", "client-one"))
    }
    @Test fun revisionRetainsLongPrecision() {
        val room = RoomFixtures.room(revision = 9007199254740993L)
        val raw = ApiJson.encodeToString(WatchRoom.serializer(), room)
        assertEquals(room.revision, ApiJson.decodeFromString<WatchRoom>(raw).checked().revision)
        assertTrue(runCatching { room.copy(revision = -1).checked() }.isFailure)
    }
    @Test fun backendProjectedPositionNotAdvancedAgain() {
        val actor = WatchRoomPlayback(FakeRooms(), RoomFixtures.room(status = "playing"), now = { RoomFixtures.now })
        assertEquals(40000L, actor.target(RoomMediaState(0, 100000, true, false, false)))
        // The anchor is ten seconds old; the server's forty must remain forty.
        assertNotEquals(50000L, actor.target(RoomMediaState(0, 100000, true, false, false)))
    }
    @Test fun nonFiniteOverflowPositionRejected() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE.toDouble())) {
            assertTrue(runCatching { RoomFixtures.room(position = value).checked() }.isFailure)
        }
    }
    @Test fun onlySupportedCatalogueParamsProject() {
        val room = RoomFixtures.room()
        assertEquals("tmdb:42", WatchRoomTarget.request(room).title.titleId)
        assertTrue(runCatching { WatchRoomTarget.request(room.copy(params = room.params + ("streamPath" to "/private"))) }.isFailure)
        assertFalse(WatchRoomTarget.eligible(RoomFixtures.request().copy(fileOnly = true)))
        assertTrue(runCatching { WatchRoomTarget.request(room.copy(itemId = "tmdb:other")) }.isFailure)
    }
}
