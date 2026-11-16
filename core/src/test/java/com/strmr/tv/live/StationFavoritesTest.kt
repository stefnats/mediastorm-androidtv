package com.strmr.tv.live

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationFavoritesTest {
    private val a="station:"+"a".repeat(24)
    private val b="station:"+"b".repeat(24)
    private fun StationFavorites.load(ids:Set<String> = emptySet(), supported:Boolean=true, readable:Boolean=true) =
        acceptRead(beginRead(),LiveChannelPreferences(stationFavoritesSupported=supported,favoriteStationIds=ids,stationFavoritesReadable=readable))

    @Test fun `different stations serialize authoritative snapshots and duplicate taps do not toggle`() = runTest {
        val first=CompletableDeferred<Unit>();val calls=mutableListOf<Pair<String,Boolean>>();val saved=mutableSetOf<String>()
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { id,on,_ ->
            calls+=id to on;if(id==a)first.await();if(on)saved+=id else saved-=id;StationFavoriteSnapshot(saved.toSet())
        }
        session.load();session.set(a,true);session.set(a,true);session.set(b,true);runCurrent()
        assertEquals(listOf(a to true),calls);assertTrue(session.state.value.ids.isEmpty());assertEquals(setOf(a,b),session.state.value.pending)
        first.complete(Unit);runCurrent();assertEquals(setOf(a,b),session.state.value.ids);assertEquals(listOf(a to true,b to true),calls)
        session.set(a,true);runCurrent();assertEquals(2,calls.size)
    }
    @Test fun `ambiguous failure retries the same desired value without optimistic success`() = runTest {
        var calls=0;val desired=mutableListOf<Boolean>()
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,on,_ -> desired+=on;calls++;if(calls==1)error("private transport detail");StationFavoriteSnapshot(setOf(a)) }
        session.load();session.set(a,true);runCurrent();assertTrue(session.state.value.ids.isEmpty())
        val failure=session.state.value.failures.getValue(a);assertFalse(failure.message.contains("private"));session.retry(failure);runCurrent()
        assertEquals(listOf(true,true),desired);assertEquals(setOf(a),session.state.value.ids);assertTrue(session.state.value.failures.isEmpty())
    }
    @Test fun `an unconfirmed or malformed snapshot never replaces the old authoritative membership`() = runTest {
        var response=StationFavoriteSnapshot(emptySet())
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_ -> response }
        session.load(setOf(b));session.set(a,true);runCurrent();assertEquals(setOf(b),session.state.value.ids)
        response=StationFavoriteSnapshot(setOf(a,"invalid"));session.retry(session.state.value.failures.getValue(a));runCurrent();assertEquals(setOf(b),session.state.value.ids)
        response=StationFavoriteSnapshot(setOf(a,b));session.retry(session.state.value.failures.getValue(a));runCurrent();assertEquals(setOf(a,b),session.state.value.ids)
    }
    @Test fun `a full later snapshot reconciles only failures actually confirmed`() = runTest {
        var response=setOf(b)
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { id,_,_ -> if(id==a)error("timeout");StationFavoriteSnapshot(response) }
        session.load();session.set(a,true);runCurrent();session.set(b,true);runCurrent();assertTrue(a in session.state.value.failures)
        response=setOf(a);session.set(b,false);runCurrent();assertTrue(session.state.value.failures.isEmpty());assertEquals(setOf(a),session.state.value.ids)
    }
    @Test fun `late settings snapshots cannot roll back writes or newer reads`() = runTest {
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_ -> StationFavoriteSnapshot(setOf(a)) }
        session.load();val old=session.beginRead();session.set(a,true);runCurrent();session.acceptRead(old,LiveChannelPreferences(stationFavoritesSupported=true))
        assertEquals(setOf(a),session.state.value.ids)
        val older=session.beginRead();val newer=session.beginRead();session.acceptRead(newer,LiveChannelPreferences(stationFavoritesSupported=true,favoriteStationIds=setOf(b)))
        session.acceptRead(older,LiveChannelPreferences(stationFavoritesSupported=true));assertEquals(setOf(b),session.state.value.ids)
    }
    @Test fun `pending writes reject even otherwise current settings reads`() = runTest {
        val gate=CompletableDeferred<Unit>();val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_ -> gate.await();StationFavoriteSnapshot(setOf(a)) }
        session.load();session.set(a,true);runCurrent();session.load(setOf(b));assertTrue(session.state.value.ids.isEmpty());gate.complete(Unit);runCurrent();assertEquals(setOf(a),session.state.value.ids)
    }
    @Test fun `markerless downgrade disables writes and clears obsolete station state`() = runTest {
        var calls=0;val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_ -> calls++;error("no") }
        session.load(setOf(a));session.set(b,true);runCurrent();assertTrue(session.state.value.failures.isNotEmpty())
        session.load(setOf(a),supported=false);assertFalse(session.state.value.supported);assertTrue(session.state.value.ids.isEmpty());assertTrue(session.state.value.failures.isEmpty())
        session.set(a,false);runCurrent();assertEquals(1,calls);assertNull(session.action(a))
    }
    @Test fun `malformed optional settings disable station writes without erasing the last snapshot`() = runTest {
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_ -> error("must not write") }
        session.load(setOf(a));session.load(readable=false);assertFalse(session.state.value.supported);assertEquals(setOf(a),session.state.value.ids);assertNotNull(session.state.value.loadError)
        session.load(setOf(a));assertTrue(session.state.value.supported);assertNull(session.state.value.loadError)
    }
    @Test fun `a masked late write and queued second write cannot enter another profile epoch`() = runTest {
        val gate=CompletableDeferred<Unit>();var calls=0;val old=ProfileDataContext.current
        val session=StationFavorites(backgroundScope,old) { _,_,_ -> calls++;withContext(NonCancellable){gate.await()};StationFavoriteSnapshot(setOf(a)) }
        session.load();session.set(a,true);session.set(b,true);runCurrent();ProfileDataContext.invalidate();runCurrent();gate.complete(Unit);runCurrent()
        assertEquals(1,calls);assertTrue(session.state.value.ids.isEmpty());assertNull(session.action(a))
        ProfileDataContext.invalidate();session.load(setOf(a));assertFalse(session.state.value.supported)
    }
    @Test fun `a queued request never starts after profile change`() = runTest {
        var calls=0;val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_ -> calls++;StationFavoriteSnapshot(setOf(a)) }
        session.load();session.set(a,true);ProfileDataContext.invalidate();runCurrent();assertEquals(0,calls)
    }
    @Test fun `actions retain explicit desired membership and pending labels`() {
        assertTrue(StationFavoriteAction(a,false,false).desired);assertFalse(StationFavoriteAction(a,true,false).desired)
        assertEquals("Saving station favorite…",StationFavoriteAction(a,false,true).label)
    }

    @Test fun `malformed marker bearing live section cannot clear a previously confirmed favorite`() = runTest {
        val session=StationFavorites(backgroundScope,ProfileDataContext.current) { _,_,_->error("must not write") }
        session.load(setOf(a))
        for(section in listOf("null","[]","\"invalid\"")) {
            val settings=liveChannelPreferences(com.strmr.tv.api.ApiJson.parseToJsonElement("""{"stationFavoritesSupported":true,"liveTV":$section}""") as kotlinx.serialization.json.JsonObject)
            session.acceptRead(session.beginRead(),settings)
            assertEquals(setOf(a),session.state.value.ids);assertFalse(session.state.value.supported);assertNotNull(session.state.value.loadError)
        }
    }
}
