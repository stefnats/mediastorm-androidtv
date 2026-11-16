package com.strmr.tv.ui.lists

import com.strmr.tv.home.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual shared ListGridModel with deferred page transport; no Android API-send or UI runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueLanguageModelTest {
    private data class Call(val list: BrowseList, val limit: Int, val offset: Int, val extra: Map<String,String>, val options: BrowseOptions, val expected: Long)
    private fun page(vararg ids: String, total: Int = ids.size, supported: Boolean = true) = BrowsePage(ids.map { BrowseEntry.ItemEntry(WatchlistItem(it,name="Synthetic $it")) },total,originalLanguageFilterSupported=supported)
    private class Fixture(scope: CoroutineScope, list: BrowseList = BrowseList.Watchlist, random: (Int)->Int = { 0 }, query: Map<String,String> = emptyMap()) {
        val calls = mutableListOf<Call>(); var reply: suspend (Call)->BrowsePage = { BrowsePage(emptyList(),0,originalLanguageFilterSupported=true) }
        val model = ListGridModel(list,query,scope,readPage={ shown,limit,offset,extra,options,expected ->
            Call(shown,limit,offset,extra.toMap(),options,expected).let { calls+=it;reply(it) }
        },randomOffset=random)
    }
    private fun ids(model: ListGridModel) = model.entries.map { (it as BrowseEntry.ItemEntry).item.id }
    private suspend fun canceled(action: suspend ()->Unit) { try { action();fail("Retired page survived") } catch (_:CancellationException){} }
    @Test fun `legacy page keeps rows but never advertises original-language controls`() = runTest {
        val f=Fixture(backgroundScope);f.reply={page("legacy",supported=false)};f.model.reload()
        assertEquals(listOf("legacy"),ids(f.model));assertFalse(f.model.originalLanguageFilterSupported)
    }
    @Test fun `normal supported first page explicitly enables the feature`() = runTest {
        val f=Fixture(backgroundScope);assertFalse(f.model.originalLanguageFilterSupported);f.reply={page("one")};f.model.reload()
        assertTrue(f.model.originalLanguageFilterSupported);assertFalse(f.calls.single().options.originalLanguageActive)
    }
    @Test fun `filter change clears old rows and server pages all carry the same canonical predicates`() = runTest {
        val f=Fixture(backgroundScope);f.reply={page("old")};f.model.reload()
        f.model.options=BrowseOptions(originalLanguages=setOf("fr"),includeUnknownOriginalLanguage=true)
        assertTrue(f.model.entries.isEmpty());assertNull(f.model.total)
        f.reply={if(it.offset==0)page("filtered-a","filtered-b",total=3)else page("filtered-c",total=3)}
        f.model.reload();f.model.loadPage()
        assertEquals(listOf("filtered-a","filtered-b","filtered-c"),ids(f.model));assertEquals(listOf(0,2),f.calls.drop(1).map{it.offset})
        assertTrue(f.calls.drop(1).all{it.options.originalLanguages==setOf("fra")&&it.options.includeUnknownOriginalLanguage});assertEquals(3,f.model.total)
    }
    @Test fun `same value A B A options cannot resurrect old page generation`() = runTest {
        val f=Fixture(backgroundScope);val old=CompletableDeferred<BrowsePage>();f.reply={page("initial")};f.model.reload()
        f.model.options=BrowseOptions(originalLanguages=setOf("fra"));f.reply={withContext(NonCancellable){old.await()}}
        val pending=async{f.model.reload()};runCurrent()
        f.model.options=BrowseOptions(originalLanguages=setOf("spa"));f.model.options=BrowseOptions(originalLanguages=setOf("fra"))
        f.reply={page("fresh")};f.model.reload();old.complete(page("old"));canceled{pending.await()}
        assertEquals(listOf("fresh"),ids(f.model));assertNull(f.model.loadError)
    }
    @Test fun `overlapping explicit same-key reload cannot clear replacement loading state`() = runTest {
        val f=Fixture(backgroundScope);val first=CompletableDeferred<BrowsePage>();val second=CompletableDeferred<BrowsePage>();var reads=0
        f.reply={withContext(NonCancellable){if(reads++==0)first.await()else second.await()}}
        val old=async{f.model.reload()};runCurrent();val current=async{f.model.reload()};runCurrent()
        first.complete(page("old"));canceled{old.await()};assertTrue(f.model.loadingMore)
        second.complete(page("current"));current.await();assertFalse(f.model.loadingMore);assertEquals(listOf("current"),ids(f.model))
    }
    @Test fun `queued old model after simulated profile A B A performs zero page reads`() = runTest {
        val f=Fixture(backgroundScope);val pending=async{f.model.reload()}
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();runCurrent();pending.await()
        assertTrue(f.calls.isEmpty());assertFalse(f.model.originalLanguageFilterSupported);assertTrue(f.model.entries.isEmpty())
    }
    @Test fun `late old-profile reply cannot expose rows totals or capability`() = runTest {
        val f=Fixture(backgroundScope);val reply=CompletableDeferred<BrowsePage>();f.reply={withContext(NonCancellable){reply.await()}}
        val pending=async{f.model.reload()};runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        assertTrue(f.model.entries.isEmpty());assertNull(f.model.total);assertFalse(f.model.originalLanguageFilterSupported)
        reply.complete(page("foreign"));canceled{pending.await()};assertTrue(f.model.entries.isEmpty());assertEquals(1,f.calls.size)
    }
    @Test fun `capability downgrade on later filtered page cannot mix legacy rows or auto fallback`() = runTest {
        val f=Fixture(backgroundScope);f.reply={page("initial")};f.model.reload();f.model.options=BrowseOptions(originalLanguages=setOf("fra"))
        f.reply={if(it.offset==0)page("filtered",total=2)else page("unfiltered",total=100,supported=false)}
        f.model.reload();f.model.loadPage()
        assertTrue(f.model.entries.isEmpty());assertNull(f.model.total);assertNotNull(f.model.loadError);assertTrue(f.model.options.originalLanguageActive)
        val reads=f.calls.size;var opened=0;f.model.loadPage();f.model.pickRandom{opened++};runCurrent();assertEquals(reads,f.calls.size);assertEquals(0,opened)
        f.model.options=BrowseOptions();f.reply={page("explicit-clear",supported=false)};f.model.reload()
        assertEquals(listOf("explicit-clear"),ids(f.model));assertFalse(f.model.originalLanguageFilterSupported)
    }
    @Test fun `rereading all loaded pages freezes original language options and uses offset before dedup`() = runTest {
        val f=Fixture(backgroundScope);f.reply={page("initial")};f.model.reload();f.model.options=BrowseOptions(originalLanguages=setOf("spa"),includeUnknownOriginalLanguage=true)
        f.reply={if(it.offset==0)page("a","b",total=3)else page("c",total=3)};f.model.reload();f.model.loadPage();val count=f.calls.size
        f.model.rereadLoaded();assertEquals(listOf(0,2),f.calls.drop(count).map{it.offset});assertTrue(f.calls.drop(count).all{it.options.originalLanguages==setOf("spa")&&it.options.includeUnknownOriginalLanguage})
        assertEquals(listOf("a","b","c"),ids(f.model))
    }
    @Test fun `random reads a server offset in the complete filtered list not only loaded cards`() = runTest {
        val f=Fixture(backgroundScope,random={80});f.reply={page("initial")};f.model.reload();f.model.options=BrowseOptions(originalLanguages=setOf("fra"),includeUnknownOriginalLanguage=true)
        f.reply={if(it.limit==1)page("random",total=100)else page("loaded",total=100)};f.model.reload();val opened=mutableListOf<HomeLink>()
        f.model.pickRandom{opened+=it};runCurrent();val call=f.calls.last();assertEquals(1,call.limit);assertEquals(80,call.offset);assertEquals(setOf("fra"),call.options.originalLanguages);assertTrue(call.options.includeUnknownOriginalLanguage)
        assertEquals(1,opened.size);assertFalse(f.model.picking)
    }
    @Test fun `changed filters cancel late random navigation without publishing a stale choice`() = runTest {
        val f=Fixture(backgroundScope,random={80});f.reply={page("initial",total=100)};f.model.reload();val reply=CompletableDeferred<BrowsePage>()
        f.reply={withContext(NonCancellable){reply.await()}};var opened=0;f.model.pickRandom{opened++};runCurrent()
        f.model.options=BrowseOptions(originalLanguages=setOf("fra"));reply.complete(page("old-random",total=100));runCurrent()
        assertEquals(0,opened);assertFalse(f.model.picking)
    }
    @Test fun `queued random under retired profile neither navigates cached row nor reads a new page`() = runTest {
        val f=Fixture(backgroundScope);f.reply={page("cached")};f.model.reload();var opened=0;f.model.pickRandom{opened++}
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();runCurrent();assertEquals(0,opened);assertEquals(1,f.calls.size)
    }
    @Test fun `caller mutable query and language sets cannot change captured transport request`() = runTest {
        val query=mutableMapOf("homeView" to "original");val f=Fixture(backgroundScope,query=query);query["homeView"]="replacement"
        val languages=mutableSetOf("fra");f.model.options=BrowseOptions(originalLanguages=languages);languages.clear();languages+="spa"
        f.reply={page("result")};f.model.reload();assertEquals("original",f.calls.single().extra["homeView"]);assertEquals(setOf("fra"),f.calls.single().options.originalLanguages)
    }
    @Test fun `native does not locally infer original language from audio or discard server selected rows`() = runTest {
        val f=Fixture(backgroundScope);f.model.options=BrowseOptions(originalLanguages=setOf("fra"));f.reply={page("no-native-language-fields")};f.model.reload()
        assertEquals(listOf("no-native-language-fields"),ids(f.model))
    }
    @Test fun `unsupported source cannot advertise catalogue language controls even through additive marker`() = runTest {
        val list=BrowseList.Remote("synthetic","Synthetic",mapOf("source" to "tmdb-list"));val f=Fixture(backgroundScope,list=list)
        f.reply={page("remote",supported=true)};f.model.reload();assertFalse(f.model.originalLanguageFilterSupported)
    }
    @Test fun `canceled current page suppresses even a masked late successful response`() = runTest {
        val f=Fixture(backgroundScope);val reply=CompletableDeferred<BrowsePage>();f.reply={withContext(NonCancellable){reply.await()}}
        val pending=async{f.model.reload()};runCurrent();pending.cancel();reply.complete(page("late"));canceled{pending.await()}
        assertTrue(f.model.entries.isEmpty());assertNull(f.model.loadError);assertFalse(f.model.loadingMore)
    }
}
