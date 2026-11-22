package com.strmr.tv.ui.lists

import com.strmr.tv.home.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoredMediaModelTest {
    private data class Call(val offset:Int, val limit:Int, val options:BrowseOptions)
    private class Fixture(scope:CoroutineScope, list:BrowseList=BrowseList.Watchlist) {
        val calls=mutableListOf<Call>()
        var reply:suspend (Call)->BrowsePage={ page("initial") }
        val model=ListGridModel(list, emptyMap(), scope, readPage={ _,limit,offset,_,options,_ -> Call(offset,limit,options).let { calls+=it; reply(it) } }, randomOffset={ 80 })
    }
    companion object {
        private fun page(id:String, total:Int=1, supported:Boolean=true, basis:String?=StoredMediaFilters.BASIS)=BrowsePage(
            listOf(BrowseEntry.ItemEntry(WatchlistItem(id,name=id))), total, originalLanguageFilterSupported=true,
            storedMediaFilterSupported=supported, storedMediaFilterBasis=basis)
    }
    @Test fun `actual list model pages rereads and randomize one immutable media selection`()=runTest {
        val f=Fixture(backgroundScope); f.model.reload(); assertTrue(f.model.storedMediaFilterSupported)
        f.model.options=BrowseOptions(originalLanguages=setOf("fra"), storedMedia=StoredMediaFilters(audioLanguage="de", audioCodec="eac3", unknown="exclude"))
        f.reply={ page("row-${it.offset}", total=100) }; f.model.reload(); f.model.loadPage(); f.model.rereadLoaded()
        var opened=0; f.model.pickRandom { opened++ }; runCurrent()
        assertEquals(1,opened); assertEquals(80,f.calls.last().offset); assertEquals(1,f.calls.last().limit)
        assertTrue(f.calls.drop(1).all { it.options.storedMedia.audioLanguage=="deu" && it.options.storedMedia.audioCodec=="eac3" && it.options.storedMedia.unknown=="exclude" && it.options.originalLanguages==setOf("fra") })
    }
    @Test fun `missing active basis blocks mixed rows without retry and explicit clear recovers`()=runTest {
        val f=Fixture(backgroundScope); f.model.reload()
        f.model.options=BrowseOptions(storedMedia=StoredMediaFilters(minHeight=1080))
        f.reply={ if(it.offset==0)page("matched",total=2) else page("unfiltered",total=100,basis=null) }
        f.model.reload(); f.model.loadPage()
        assertTrue(f.model.entries.isEmpty()); assertNull(f.model.total); assertNotNull(f.model.loadError)
        val count=f.calls.size; f.model.loadPage(); f.model.pickRandom {}; runCurrent(); assertEquals(count,f.calls.size)
        assertTrue(f.model.options.storedMedia.active)
        f.model.options=f.model.options.copy(storedMedia=StoredMediaFilters()); f.reply={page("legacy",supported=false,basis=null)}; f.model.reload()
        assertEquals(1,f.model.entries.size); assertFalse(f.model.storedMediaFilterSupported)
    }
    @Test fun `profile retirement hides media capability and rejects late old rows`()=runTest {
        val f=Fixture(backgroundScope); f.model.reload(); val deferred=CompletableDeferred<BrowsePage>()
        f.model.options=BrowseOptions(storedMedia=StoredMediaFilters(hdr="hdr10")); f.reply={withContext(NonCancellable){deferred.await()}}
        val work=async { f.model.reload() }; runCurrent(); ProfileDataContext.invalidate()
        assertFalse(f.model.storedMediaFilterSupported); assertTrue(f.model.entries.isEmpty())
        deferred.complete(page("old")); try { work.await(); fail("Retired request survived") } catch (_:CancellationException) {}
        val fresh=Fixture(backgroundScope); assertFalse(fresh.model.options.storedMedia.active)
    }
    @Test fun `saved custom shelf exposes controls from unfiltered capability while legacy server does not`()=runTest {
        val shelf=BrowseList.Remote("shelf","Shelf",mapOf("source" to "mdblist-shelf", "url" to "mediastorm:custom-list:saved"))
        val f=Fixture(backgroundScope,shelf); f.reply={page("saved",basis=null)}; f.model.reload()
        assertTrue(f.model.storedMediaFilterSupported); assertFalse(f.model.options.storedMedia.active)
        f.model.options=BrowseOptions(storedMedia=StoredMediaFilters(subtitleLanguage="fr",unknown="only"))
        f.reply={page("filtered")}; f.model.reload(); assertEquals("fra",f.calls.last().options.storedMedia.subtitleLanguage)
        val legacy=Fixture(backgroundScope); legacy.reply={page("legacy",supported=false,basis=null)}; legacy.model.reload()
        assertFalse(legacy.model.storedMediaFilterSupported); assertEquals(1,legacy.model.entries.size)
    }
    @Test fun `unsupported discovery clears only media choices even when server advertises support`()=runTest {
        val list=BrowseList.Remote("discover","Discover",mapOf("source" to "tmdb-list"))
        val f=Fixture(backgroundScope,list); f.model.options=BrowseOptions(storedMedia=StoredMediaFilters(audioCodec="aac"))
        assertFalse(f.model.options.storedMedia.active); f.model.reload(); assertFalse(f.model.storedMediaFilterSupported)
    }
}
