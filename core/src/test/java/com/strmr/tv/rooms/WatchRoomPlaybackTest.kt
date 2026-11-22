package com.strmr.tv.rooms

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.HttpFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

internal class FakeRoomPlayer : WatchRoomPlayerPort {
    var sample = RoomMediaState(40000, 100000, true, false, false)
    var end = 100000L
    val applies = mutableListOf<Pair<Long, Boolean>>()
    var stopped = false
    override fun sample() = sample
    override fun apply(positionMs: Long, playing: Boolean): Boolean {
        if (positionMs >= end) return false
        applies += positionMs to playing
        sample = sample.copy(positionMs = positionMs, playing = playing)
        return true
    }
    override fun pause() { sample = sample.copy(playing = false) }
    override fun stop(message: String) { stopped = true; pause() }
}

class WatchRoomPlaybackTest {
    @Test fun eachMemberResolvesOwnExactTarget() {
        val room = RoomFixtures.room().copy(mediaType = "episode", itemId = "tmdb:42:S02E03",
            params = RoomFixtures.room().params + mapOf("mediaType" to "series", "seasonNumber" to "2", "episodeNumber" to "3"))
        val request = WatchRoomTarget.request(room)
        assertEquals(2, request.episode!!.season); assertEquals(3, request.episode!!.episode)
        assertNull(request.libraryVersion); assertNull(request.start); assertTrue(request.following.isEmpty())
    }
    @Test fun sourceFailureNeverMarksReady() = runTest {
        val api = FakeRooms(); val actor = WatchRoomPlayback(api, api.room, this)
        // No actual player/source was admitted.
        actor.close().join()
        assertFalse(api.calls.contains("ready"))
    }
    @Test fun initialAndRebufferStartWaitForReady() = runTest {
        val api = FakeRooms(); api.room = api.room.copy(waitingForReady = true)
        val player = FakeRoomPlayer(); val actor = WatchRoomPlayback(api, api.room, this)
        actor.bind(player); runCurrent(); assertFalse(player.sample.playing)
        api.room = api.room.copy(waitingForReady = false, status = "playing")
        advanceTimeBy(2001); runCurrent(); assertTrue(player.sample.playing)
        assertEquals(0, api.calls.count { it == "state" }); actor.close().join()
    }
    @Test fun remoteApplyNeverWritesState() = runTest {
        val api = FakeRooms(); api.room = api.room.copy(status = "playing")
        val player = FakeRoomPlayer(); val actor = WatchRoomPlayback(api, api.room, this)
        actor.bind(player); runCurrent()
        assertEquals(40000L to true, player.applies.last())
        assertFalse(api.calls.contains("state")); actor.close().join()
    }
    @Test fun outOfRangePausesWithoutClampOrEcho() = runTest {
        val api = FakeRooms(); api.room = api.room.copy(status = "playing")
        val player = FakeRoomPlayer(); player.end = 10000
        val actor = WatchRoomPlayback(api, api.room, this)
        actor.bind(player); runCurrent()
        assertTrue(player.applies.isEmpty()); assertFalse(player.sample.playing)
        assertFalse(api.calls.contains("state")); actor.close().join()
    }
    @Test fun userCommandCarriesExactRevision() = runTest {
        val api = FakeRooms(); val player = FakeRoomPlayer()
        val actor = WatchRoomPlayback(api, api.room, this); actor.bind(player); runCurrent()
        actor.command(true, 45000); runCurrent()
        assertEquals(4L, api.lastRevision); assertEquals(45.0, api.room.position, 0.0)
        assertEquals(1, api.calls.count { it == "state" }); actor.close().join()
    }
    @Test fun conflictResyncNeverReplays() = runTest {
        val api = FakeRooms(); api.stateFailure = ApiError.Http(HttpFailure(409, "{}"))
        val actor = WatchRoomPlayback(api, api.room, this); actor.bind(FakeRoomPlayer()); runCurrent()
        actor.command(true); runCurrent()
        assertEquals(1, api.calls.count { it == "state" })
        assertEquals(2, api.calls.count { it == "read" }); actor.close().join()
    }
    @Test fun pollHeartbeatBoundedAndJoined() = runTest {
        val api = FakeRooms(); val actor = WatchRoomPlayback(api, api.room, this)
        actor.bind(FakeRoomPlayer()); runCurrent(); advanceTimeBy(5001); runCurrent()
        assertEquals(1, api.calls.count { it == "heartbeat" })
        actor.close().join(); val count = api.calls.size
        advanceTimeBy(10000); runCurrent(); assertEquals(count, api.calls.size)
    }
    @Test fun endExpiryContextChangeStopsOriginalPlayer() = runTest {
        for (end in listOf("ended", "expiry", "context")) {
            val api = FakeRooms(); val player = FakeRoomPlayer()
            val actor = WatchRoomPlayback(api, api.room, this)
            actor.bind(player); runCurrent()
            when (end) {
                "ended" -> api.room = api.room.copy(status = "ended")
                "expiry" -> api.room = api.room.copy(expiresAt = "2000-01-01T00:00:00Z")
                else -> api.valid = false
            }
            advanceTimeBy(2001); runCurrent()
            assertTrue(player.stopped); actor.close().join()
        }
    }
    @Test fun lateCleanupCannotStopReplacement() = runTest {
        val api = FakeRooms(); api.stateGate = CompletableDeferred()
        val old = WatchRoomPlayback(api, api.room, this); val first = FakeRoomPlayer()
        old.bind(first); runCurrent(); old.command(true); runCurrent()
        val close = old.close(); runCurrent(); assertFalse(close.isCompleted)
        val replacement = FakeRoomPlayer()
        api.stateGate!!.complete(Unit); close.join()
        assertFalse(replacement.stopped); assertFalse(replacement.sample.playing)
    }
    @Test fun historyProgressKeepsOriginalViewer() {
        val request = WatchRoomTarget.request(RoomFixtures.room())
        assertEquals("tmdb:42", request.progressItemId)
        assertFalse(request.fileOnly); assertNull(request.start)
    }
    @Test fun noCastRecoveryUpNextCombinedAdoption() {
        assertFalse(WatchRoomTarget.eligible(RoomFixtures.request().copy(fileOnly = true)))
        assertFalse(WatchRoomTarget.eligible(RoomFixtures.request().copy(title = RoomFixtures.request().title.copy(fromLibrary = true))))
        assertTrue(WatchRoomTarget.request(RoomFixtures.room()).following.isEmpty())
    }
}
