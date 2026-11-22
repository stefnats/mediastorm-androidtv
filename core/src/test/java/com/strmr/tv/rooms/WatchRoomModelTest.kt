package com.strmr.tv.rooms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchRoomModelTest {
    @Test fun createJoinAreExplicit() = runTest {
        val api = FakeRooms(); val model = WatchRoomModel(api, this)
        model.refresh(); runCurrent()
        assertFalse(api.calls.contains("join")); assertFalse(api.calls.contains("create"))
        model.create(RoomFixtures.request(), emptyList()); runCurrent()
        assertEquals(1, api.calls.count { it == "create" })
        model.close().join()
        assertEquals("leave", api.calls.last())
    }
    @Test fun lateCreateAckJoinedBeforeCleanup() = runTest {
        val api = FakeRooms(); api.createGate = CompletableDeferred()
        val model = WatchRoomModel(api, this)
        model.create(RoomFixtures.request(), emptyList()); runCurrent()
        val close = model.close(); runCurrent()
        assertFalse(close.isCompleted); assertFalse(api.calls.contains("end"))
        api.createGate!!.complete(Unit); close.join()
        assertEquals(listOf("create", "end"), api.calls)
        assertNull(model.state.value.room)
    }
    @Test fun oldLobbyCannotReplaceNewIntent() = runTest {
        val api = FakeRooms(); val old = WatchRoomModel(api, this)
        old.join("room-one"); runCurrent(); old.close().join()
        val current = WatchRoomModel(api, this)
        current.refresh(); runCurrent()
        old.join("late"); runCurrent()
        assertNull(current.state.value.room)
        assertEquals(1, api.calls.count { it == "join" }); current.close().join()
    }
    @Test fun refreshCannotEraseTypedInvite() = runTest {
        val api = FakeRooms(); val model = WatchRoomModel(api, this)
        model.join("room-one"); runCurrent()
        // Draft belongs to the UI, not a field reset by a room response.
        val draft = "london-friend"
        model.refresh(); runCurrent(); model.invite(draft); runCurrent()
        assertTrue(api.calls.contains("invite:london-friend")); model.close().join()
    }
    @Test fun accountInviteAcceptDeclineRevoke() = runTest {
        val api = FakeRooms(); val model = WatchRoomModel(api, this)
        model.accept("invite-one"); runCurrent()
        assertEquals(listOf("accept", "join"), api.calls)
        model.invite("friend"); runCurrent(); model.revoke("invite-one"); runCurrent()
        assertTrue(model.state.value.outgoing.isEmpty())
        model.decline("invite-one"); runCurrent()
        assertTrue(model.state.value.invitations.isEmpty()); model.close().join()
    }
    @Test fun closeJoinsActualMutationAndCleanup() = runTest {
        val api = FakeRooms(); api.stateGate = CompletableDeferred()
        val model = WatchRoomModel(api, this)
        model.join("room-one"); runCurrent(); model.startTogether(); runCurrent()
        val close = model.close(); runCurrent()
        assertFalse(close.isCompleted); assertFalse(api.calls.contains("leave"))
        api.stateGate!!.complete(Unit); close.join()
        assertEquals("leave", api.calls.last())
    }
}
