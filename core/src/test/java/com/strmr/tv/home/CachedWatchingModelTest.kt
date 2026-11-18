package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.lists.ListGridModel
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CachedWatchingModelTest {
    @Test fun `actual model consumes full beyond 650 language pool without hiding or changing original rows`()=runTest {
        val list=WatchingFixture.list();var calls=0
        val model=ListGridModel(list,emptyMap(),backgroundScope,readPage={_,limit,offset,_,options,_->calls++;assertEquals(0,offset);assertTrue(options.includeUnknownOriginalLanguage);WatchingFixture.page("unknown-only",limit,offset)},pageSize=700)
        model.options=BrowseOptions(includeUnknownOriginalLanguage=true);model.loadPage()
        assertEquals(696,model.entries.size);assertEquals(696,model.total);assertEquals(1,calls);assertTrue(model.cards.all{it.cacheOnlyBrowseEntry&&it.titleSourceCoordinate()==null&&it.reloadStoredRating==null&&it.episode==null})
        assertTrue(model.originalLanguageFilterSupported);assertNull(model.loadError)
    }
    @Test fun `first pool survives options reload and replacement model in same entry lifetime`()=runTest {
        val list=WatchingFixture.list();var reads=0;var b=false
        val read:suspend(BrowseList,Int,Int,Map<String,String>,BrowseOptions,Long)->BrowsePage={_,_,_,_,_,_->reads++;WatchingFixture.page("late-selected").let{if(b)it.copy(watching=it.watching!!.copy(id="f".repeat(32)))else it}}
        val model=ListGridModel(list,emptyMap(),backgroundScope,readPage=read,pageSize=1);model.loadPage();assertEquals(1,model.entries.size)
        b=true;model.options=BrowseOptions(originalLanguages=setOf("fra"));model.loadPage();assertTrue(model.entries.isEmpty());assertNotNull(model.loadError)
        model.reload();assertTrue(model.entries.isEmpty());assertNotNull(model.loadError)
        val replacement=ListGridModel(list,emptyMap(),backgroundScope,readPage=read,pageSize=1);replacement.loadPage();assertTrue(replacement.entries.isEmpty());assertNotNull(replacement.loadError)
        val reopened=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage=read,pageSize=1);reopened.loadPage();assertEquals(1,reopened.entries.size)
        assertEquals(5,reads)
    }
    @Test fun `pool descriptor does not freeze language totals across option generations`()=runTest {
        val model=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage={_,_,_,_,options,_->if(options.originalLanguageActive)WatchingFixture.page("rare")else WatchingFixture.page("unfiltered")},pageSize=2)
        model.loadPage();assertEquals(700,model.total);model.options=BrowseOptions(originalLanguages=setOf("nob"));model.loadPage();assertEquals(1,model.total);assertNull(model.loadError)
    }
    @Test fun `changed total duplicated ID or lost page fails entire generation without fallback`()=runTest {
        for(mode in listOf("total","duplicate","missing","marker")){
            var calls=0;val first=WatchingFixture.page("unfiltered")
            val model=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage={_,_,offset,_,_,_->calls++;if(offset==0)first else when(mode){"total"->WatchingFixture.page("continuation");"duplicate"->first;"missing"->first.copy(entries=emptyList());else->first.copy(originalLanguageFilterSupported=false)}},pageSize=2)
            model.loadPage();assertEquals(2,model.entries.size);model.loadPage();assertTrue(model.entries.isEmpty());assertNull(model.total);assertNotNull(model.loadError);model.loadPage();assertEquals(2,calls)
        }
    }
    @Test fun `late options profile and original page replies never publish old rows`()=runTest {
        for(mode in listOf("options","profile","page")){
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val pageJob=Job(backgroundScope.coroutineContext[Job]);val pageScope=CoroutineScope(backgroundScope.coroutineContext+pageJob)
            val list=WatchingFixture.list();val model=ListGridModel(list,emptyMap(),pageScope,readPage={_,_,_,_,_,_->withContext(NonCancellable){entered.complete(Unit);release.await()};WatchingFixture.page("unfiltered")},pageSize=2)
            val reading=async{runCatching{model.loadPage()}};entered.await()
            when(mode){"options"->model.options=BrowseOptions(originalLanguages=setOf("fra"));"profile"->ProfileDataContext.invalidate();else->pageJob.cancel()};release.complete(Unit);reading.await()
            assertTrue(model.entries.isEmpty());assertNull(model.total);pageJob.cancel()
        }
    }
    @Test fun `bounded clock expiry clears original model and same entry never renews local deadline`()=runBlocking {
        val parent=Job();val scope=CoroutineScope(Dispatchers.Default+parent)
        try{
            val list=WatchingFixture.list();val page=WatchingFixture.page("late-selected");val clock=WatchingFixture.clock();val expires=java.time.Instant.ofEpochMilli(clock.upperMillis()+120).toString()
            val short=page.copy(watching=page.watching!!.copy(expiresAt=expires,clock=clock))
            val model=ListGridModel(list,emptyMap(),scope,readPage={_,_,_,_,_,_->short},pageSize=1);model.loadPage();assertEquals(1,model.entries.size)
            delay(250);assertTrue(model.entries.isEmpty());assertNull(model.total);assertNotNull(model.loadError)
            var attempts=0;val replacement=ListGridModel(list,emptyMap(),scope,readPage={_,_,_,_,_,_->attempts++;short},pageSize=1);replacement.loadPage();assertEquals(0,attempts);assertTrue(replacement.entries.isEmpty())
        }finally{scope.cancel()}
    }
    @Test fun `backwards actual observation Date rejects later page without extending original expiry`()=runTest {
        var offsetRead=0;val first=WatchingFixture.page("unfiltered")
        val second=first.copy(entries=first.entries.map{entry->val row=(entry as BrowseEntry.CachedWatchingEntry).item;BrowseEntry.CachedWatchingEntry(row.copy(seriesId=row.seriesId+"0"))},watching=first.watching!!.copy(clock=WatchingFixture.clock(WatchingFixture.date-1000)))
        val model=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage={_,_,_,_,_,_->if(offsetRead++==0)first else second},pageSize=2)
        model.loadPage();model.loadPage();assertTrue(model.entries.isEmpty());assertNotNull(model.loadError)
    }
    @Test fun `random from full selected saved pool opens only its original page link`()=runTest {
        val model=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage={_,_,_,_,_,_->WatchingFixture.page("random-pool")},randomOffset={2},pageSize=3)
        model.loadPage();var link:HomeLink?=null;model.pickRandom{link=it};runCurrent();assertEquals("tmdb:tv:800",(link as HomeLink.Details).target.titleId)
    }
    @Test fun `native request exports use actual canonical builder and no normal producer flags`() {
        val list=WatchingFixture.list();fun query(options:BrowseOptions,limit:Int,offset:Int=0):String{val request=cachedWatchingRequest("profile-a",list,limit,offset,options);val url=("http://127.0.0.1"+request.path).toHttpUrl().newBuilder().apply{request.query.toSortedMap().forEach{(k,v)->addQueryParameter(k,v)}}.build();return url.encodedPath+"?"+url.encodedQuery}
        val selected=BrowseOptions(sort=BrowseSort.NAME,ascending=true,originalLanguages=setOf("fra","nob"))
        val values=linkedMapOf("unfiltered" to query(BrowseOptions(),2),"continuation" to query(selected,1,1),"french" to query(selected.copy(originalLanguages=setOf("fra")),2),"unknown" to query(BrowseOptions(includeUnknownOriginalLanguage=true),0),"or-unknown" to query(selected.copy(includeUnknownOriginalLanguage=true),0),"random-pool" to query(selected,0),"series" to query(BrowseOptions(kind=BrowseKind.SHOWS),2))
        assertTrue(values.values.all{"source=continue-watching-cached" in it});assertTrue(values.values.none{"includeFacets" in it||"includeUnwatchedCounts" in it||"shelfPhase" in it})
        val bytes=buildJsonObject{values.forEach{(k,v)->put(k,v)}}.toString().toByteArray()
        System.getProperty("sourceFilterContractsDir")?.let{val dir=File(it).apply{mkdirs()};File(dir,"native-cached-watching-queries.json").writeBytes(bytes);File(dir,"native-cached-watching-queries.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}+"\n")}
    }
    @Test fun `existing normal continue producer still reads rows and aside independently`()=runTest {
        var rows=0;var lists=0;var items=0
        val result=readContinueWatching(ProfileDataContext.current,{rows++;listOf(ContinueWatchingItem("tmdb:movie:1","One"))},{lists++;listOf(CustomList("aside","Set Aside"))},{_,_->items++;emptyList()})
        assertEquals(1,result.size);assertEquals(listOf(1,1,1),listOf(rows,lists,items))
    }
    @Test fun `actual stale new API rejects epoch before Android transport or auth`()=runTest {
        val list=WatchingFixture.list();ProfileDataContext.invalidate()
        assertTrue(runCatching{ApiClient.browsePage(list,2,expectedContext=list.originalContext)}.exceptionOrNull() is CancellationException)
    }
    @Test fun `page hide then return retires original in-flight read even under same epoch`()=runTest {
        var selected=true;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val model=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage={_,_,_,_,_,_->calls++;withContext(NonCancellable){entered.complete(Unit);release.await()};WatchingFixture.page("unfiltered")},pageSize=2,pageSelected={selected})
        val reading=async{runCatching{model.loadPage()}};entered.await();selected=false;model.pageSelectionChanged(false);selected=true;release.complete(Unit);reading.await()
        assertTrue(model.entries.isEmpty());assertNull(model.total);assertNotNull(model.loadError);assertEquals(1,calls)
    }
    @Test fun `client wallclock one hour either side never controls actual response-bound expiry`()=runTest {
        for(skew in listOf(-3_600_000L,3_600_000L)) {
            val model=ListGridModel(WatchingFixture.list(),emptyMap(),backgroundScope,readPage={_,_,_,_,_,_->WatchingFixture.page("unfiltered")},pageSize=2,clockMillis={WatchingFixture.date+skew})
            model.loadPage();assertEquals(2,model.entries.size);assertEquals(700,model.total);assertNull(model.loadError)
        }
    }
    @Test fun `cache-only actual card coordinates produce zero SourceObserver work`()=runTest {
        val cards=WatchingFixture.page("random-pool").entries.map(HomeCard::from)
        var calls=0;val gateway=object:TitleSourceGateway{override fun isCurrent()=true;override suspend fun update(view:String,body:TitleSourceDemand):TitleSourceResponse{calls++;error("No saved card demand")}}
        val states=mutableListOf<Map<String,SourceAvailability>>()
        TitleSourceObserver(gateway).states(cards.mapNotNull{it.titleSourceCoordinate()}).collect{states+=it}
        assertEquals(0,calls);assertEquals(listOf(emptyMap<String,SourceAvailability>()),states)
    }

}
