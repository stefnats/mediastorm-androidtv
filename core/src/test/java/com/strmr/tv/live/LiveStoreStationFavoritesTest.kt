package com.strmr.tv.live

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStoreStationFavoritesTest {
    private val a="station:"+"a".repeat(24)
    private val b="station:"+"b".repeat(24)
    private fun channel(id:String="one",station:String=a)=LiveChannel(id,id,"https://example.invalid/never-open/$id",stationId=station,sourceId=id)
    private class Prefs:LiveViewPreferences {
        override fun categories(profileId:String)=emptySet<String>()
        override fun groupStations(profileId:String)=true
        override fun saveCategories(profileId:String,names:Set<String>) {}
        override fun saveGroupStations(profileId:String,enabled:Boolean) {}
    }
    private inner class Catalog:LiveCatalogAccess {
        var settings=LiveChannelPreferences(stationFavoritesSupported=true)
        var read:suspend ()->LiveChannelPreferences={settings}
        var epg:suspend ()->Boolean={false}
        var pages:suspend (LiveStore.Query,Int)->LiveChannelsPage={_,_->LiveChannelsPage(listOf(channel()),1,false)}
        var save:suspend ()->Unit={}
        val requests=mutableListOf<Pair<LiveStore.Query,Int>>()
        val saves=mutableListOf<Pair<List<String>,List<String>>>()
        override suspend fun preferences(context:Long)=read()
        override suspend fun epgEnabled(context:Long)=epg()
        override suspend fun page(query:LiveStore.Query,offset:Int,limit:Int,context:Long):LiveChannelsPage { requests+=query to offset;return pages(query,offset) }
        override suspend fun saveLegacy(favorites:List<String>,hidden:List<String>,context:Long) {saves+=favorites to hidden;save()}
    }
    private fun TestScope.store(catalog:Catalog,session:StationFavorites)=LiveStore(backgroundScope,ProfileDataContext.current,Prefs(),catalog,session,"synthetic")
    private fun TestScope.session(ids:Set<String> = emptySet(),write:suspend (String,Boolean,Long)->StationFavoriteSnapshot={_,_,_->StationFavoriteSnapshot(ids)})=
        StationFavorites(backgroundScope,ProfileDataContext.current,write)

    @Test fun `supported preferences apply before first catalog request and preserve view policy`() = runTest {
        val catalog=Catalog();catalog.settings=LiveChannelPreferences(setOf("stream"),setOf("hidden"),true,setOf(a));val store=store(catalog,session())
        store.start();assertEquals(emptySet<String>(),catalog.requests.single().first.favoriteStationIds)
        store.toggleFavoritesOnly();store.reload();assertEquals(setOf(a),catalog.requests.last().first.favoriteStationIds);assertEquals(setOf("stream"),store.query.favoriteIds)
        store.toggleCategory("General");store.reload();assertEquals(emptySet<String>(),store.query.favoriteStationIds);assertTrue(store.query.favoriteIds.isEmpty())
        store.setSearchText("RTL");advanceTimeBy(401);runCurrent();assertEquals("RTL",store.query.search);assertTrue(store.query.favoriteStationIds!!.isEmpty())
    }
    @Test fun `query effect cancellation between settings and epg does not permanently disable guide`() = runTest {
        val catalog=Catalog();val gate=CompletableDeferred<Unit>();var calls=0
        catalog.epg={calls++;if(calls==1)gate.await();true}
        val store=store(catalog,session());val original=launch {store.start()};runCurrent()
        assertEquals(emptySet<String>(),store.query.favoriteStationIds);assertNull(store.epgEnabled)
        original.cancelAndJoin();store.start();assertEquals(true,store.epgEnabled);assertEquals(2,calls)
    }
    @Test fun `even a masked cancelled epg response cannot publish disabled status`() = runTest {
        val catalog=Catalog();val gate=CompletableDeferred<Unit>();catalog.epg={withContext(NonCancellable){gate.await()};false}
        val store=store(catalog,session());val job=launch {store.start()};runCurrent();job.cancel();gate.complete(Unit);job.join();assertNull(store.epgEnabled)
        catalog.epg={true};store.start();assertEquals(true,store.epgEnabled)
    }
    @Test fun `markerless and malformed station settings preserve legacy favorites and hidden lists`() = runTest {
        for(settings in listOf(LiveChannelPreferences(setOf("one"),setOf("hidden")),LiveChannelPreferences(setOf("one"),setOf("hidden"),true,emptySet(),false))) {
            val catalog=Catalog();catalog.settings=settings;val store=store(catalog,session());store.start();store.toggleFavoritesOnly()
            assertNull(store.query.favoriteStationIds);assertEquals(setOf("one"),store.query.favoriteIds);assertEquals(setOf("hidden"),store.hidden);assertNull(store.stationAction(channel()))
        }
    }
    @Test fun `flat catalog uses raw server station id for removable station action`() = runTest {
        val catalog=Catalog();catalog.settings=LiveChannelPreferences(stationFavoritesSupported=true,favoriteStationIds=setOf(a));val store=store(catalog,session());store.start();store.groupByStation=false;store.reload()
        val row=store.channelRows.single() as LiveStationGroups.Channel;assertNull(row.stationId);assertEquals(a,store.stationAction(row.channel)!!.stationId);assertTrue(store.stationAction(row.channel)!!.favorite)
        assertNull(store.stationAction(channel("alien",b)));assertNull(store.stationAction(channel("one"," $a")))
    }
    @Test fun `removing stream favorite retains independently favorited station channel`() = runTest {
        val catalog=Catalog();catalog.settings=LiveChannelPreferences(setOf("one"),emptySet(),true,setOf(a));val store=store(catalog,session());store.start();store.toggleFavoritesOnly();store.reload()
        store.toggleFavorite(channel());assertEquals(listOf(channel()),store.channels);assertFalse("one" in store.favorites);runCurrent();assertEquals(1,catalog.saves.size)
    }
    @Test fun `late favorite settings read cannot roll back source favorite or hidden save`() = runTest {
        val catalog=Catalog();val store=store(catalog,session());store.start();val gate=CompletableDeferred<Unit>();val stale=catalog.settings
        catalog.read={gate.await();stale};store.retryFavoriteSettings();runCurrent();store.toggleFavorite(channel());store.toggleHidden(channel());runCurrent();gate.complete(Unit);runCurrent()
        assertEquals(setOf("one"),store.favorites);assertEquals(setOf("one"),store.hidden)
    }
    @Test fun `station snapshots and legacy full settings writes stay independent when interleaved`() = runTest {
        val gate=CompletableDeferred<Unit>();val catalog=Catalog();catalog.save={gate.await()};val session=session(write={id,on,_->StationFavoriteSnapshot(if(on)setOf(id) else emptySet())});val store=store(catalog,session)
        store.start();store.toggleFavorite(channel());runCurrent();store.setStationFavorite(store.stationAction(channel())!!);runCurrent();assertEquals(setOf(a),store.favoriteStationIds)
        gate.complete(Unit);runCurrent();assertEquals(setOf(a),store.favoriteStationIds);assertEquals(setOf("one"),store.favorites);assertEquals(listOf("one"),catalog.saves.single().first)
    }
    @Test fun `station mutation invalidates old catalog pages without affecting selected playback object`() = runTest {
        val catalog=Catalog();val session=session(write={id,_,_->StationFavoriteSnapshot(setOf(id))});val store=store(catalog,session);store.start();store.toggleFavoritesOnly();store.reload()
        val playing=store.channels.single();val gate=CompletableDeferred<Unit>();catalog.pages={_,_->gate.await();LiveChannelsPage(listOf(channel("stale")),1,false)}
        val old=launch {store.reload(force=true)};runCurrent();session.set(a,true);runCurrent();gate.complete(Unit);old.join();assertTrue(store.channels.isEmpty());assertFalse(store.isCurrent)
        assertEquals("one",playing.id);assertEquals("https://example.invalid/never-open/one",playing.url)
        catalog.pages={_,_->LiveChannelsPage(listOf(channel(),channel("alternate")),2,false)};store.reload();assertEquals(2,store.channels.size);assertTrue(store.channels.all {store.stationAction(it)!!.favorite});assertTrue(store.favorites.isEmpty())
    }
    @Test fun `show hidden is part of station favorites request and rejects prior page`() = runTest {
        val catalog=Catalog();catalog.settings=LiveChannelPreferences(stationFavoritesSupported=true,favoriteStationIds=setOf(a));val store=store(catalog,session());store.start();store.toggleFavoritesOnly();store.reload();assertEquals(false,store.query.includeHidden)
        val gate=CompletableDeferred<Unit>();catalog.pages={_,_->gate.await();LiveChannelsPage(listOf(channel("stale")),1,false)};val old=launch {store.reload(force=true)};runCurrent();store.showHidden=true;gate.complete(Unit);old.join();assertTrue(store.channels.isEmpty());assertEquals(true,store.query.includeHidden)
        store.toggleFavoritesOnly();assertNull(store.query.includeHidden)
    }
    @Test fun `legacy hidden reducer invalidation waits for confirmed settings save`() = runTest {
        val catalog=Catalog();catalog.settings=LiveChannelPreferences(stationFavoritesSupported=true,favoriteStationIds=setOf(a));val gate=CompletableDeferred<Unit>();catalog.save={gate.await()};val store=store(catalog,session());store.start();store.toggleFavoritesOnly();store.reload()
        val before=store.query;store.toggleHidden(channel());runCurrent();assertEquals(before,store.query);gate.complete(Unit);runCurrent();assertNotEquals(before,store.query);assertFalse(store.isCurrent)
    }
    @Test fun `pagination advances by raw returned variants and preserves station membership`() = runTest {
        val catalog=Catalog();catalog.pages={_,offset->LiveChannelsPage(listOf(channel(if(offset==0)"one" else "alternate")),2,offset==0)};val store=store(catalog,session());store.start();store.loadMore()
        assertEquals(listOf(0,1),catalog.requests.map {it.second});assertEquals(2,store.channels.size);assertEquals(2,store.total)
    }
    @Test fun `late old profile catalog cannot publish or expose station actions`() = runTest {
        val catalog=Catalog();val store=store(catalog,session());store.start();val gate=CompletableDeferred<Unit>();catalog.pages={_,_->gate.await();LiveChannelsPage(listOf(channel("late")),1,false)}
        val job=launch {store.reload(force=true)};runCurrent();ProfileDataContext.invalidate();gate.complete(Unit);job.join();assertTrue(store.channels.isEmpty());assertNull(store.stationAction(channel()))
    }

    @Test fun `two live views share serialized station state without writing legacy favorites`() = runTest {
        val catalog=Catalog();val saved=mutableSetOf<String>();val session=session(write={id,on,_->if(on)saved+=id else saved-=id;StationFavoriteSnapshot(saved.toSet())})
        val first=store(catalog,session);val second=store(catalog,session);first.start();second.start()
        first.setStationFavorite(first.stationAction(channel())!!);runCurrent()
        assertEquals(setOf(a),first.favoriteStationIds);assertEquals(setOf(a),second.favoriteStationIds);assertTrue(catalog.saves.isEmpty())
        second.setStationFavorite(second.stationAction(channel())!!);runCurrent();assertTrue(first.favoriteStationIds.isEmpty());assertTrue(second.favoriteStationIds.isEmpty())
    }
    @Test fun `failed legacy hide save leaves station request membership unchanged`() = runTest {
        val catalog=Catalog();catalog.settings=LiveChannelPreferences(stationFavoritesSupported=true,favoriteStationIds=setOf(a));catalog.save={error("synthetic failure")}
        val store=store(catalog,session());store.start();store.toggleFavoritesOnly();store.reload();val before=store.query
        store.toggleHidden(channel());runCurrent();assertEquals(before,store.query);assertTrue(store.hidden.isEmpty());assertTrue(store.isCurrent)
    }
    @Test fun `late legacy get started while source save pending is rejected`() = runTest {
        val catalog=Catalog();val store=store(catalog,session());store.start();val gate=CompletableDeferred<Unit>();catalog.save={gate.await()}
        store.toggleFavorite(channel());runCurrent();store.retryFavoriteSettings();runCurrent();assertEquals(setOf("one"),store.favorites)
        gate.complete(Unit);runCurrent();assertEquals(setOf("one"),store.favorites)
    }
}
