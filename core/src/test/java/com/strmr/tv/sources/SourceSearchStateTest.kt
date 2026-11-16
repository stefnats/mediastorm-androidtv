package com.strmr.tv.sources

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSearchStateTest {
    private class Transport:SourceSearchTransport {
        var initial:suspend ()->SourceSearchReply={SourceSearchReply.Supported(filterPage())}
        var pages:suspend (String,SourceFilters,String?)->SourceSearchPage={id,f,_->filterPage(f,snapshot=id,view="filtered-view")}
        var checks:suspend (String)->SourceHealthSnapshot={SourceHealthSnapshot(true)}
        var searches=0;val requests=mutableListOf<Triple<String,SourceFilters,String?>>()
        val healthCalls=mutableListOf<String>()
        override suspend fun search(title:DetailsTarget,episode:Episode?,context:Long):SourceSearchReply {searches++;return initial()}
        override suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long):SourceSearchPage {requests+=Triple(snapshotId,filters,cursor);return pages(snapshotId,filters,cursor)}
        override suspend fun health(token:String,context:Long):SourceHealthSnapshot {healthCalls+=token;return checks(token)}
    }
    private fun TestScope.state(transport:Transport,clock:()->Instant={filterAt.plusMillis(testScheduler.currentTime)})=
        SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,clock,clientIdentity={"synthetic-client"})
    private fun viewError()=ApiError.Http(HttpFailure(409,"""{"code":"source_view_changed","error":"private server diagnostics"}"""))

    @Test fun `opening sources performs one normal search while edits and filters use retained server pool`() = runTest {
        val transport=Transport().apply {pages={id,f,_->filterPage(f,listOf(75),snapshot=id,view="french-view")}}
        val state=state(transport);state.reload();runCurrent();assertEquals(1,transport.searches)
        state.edit(SourceFilters(audioLanguage="fra"));runCurrent();assertTrue(transport.requests.isEmpty());assertTrue(state.filterPending)
        state.apply();runCurrent();assertEquals(1,transport.searches);assertEquals(75,state.rows.single().sourceIndex);assertEquals("fra",state.applied.audioLanguage);assertFalse(state.filterPending)
        assertEquals(listOf(Triple("snapshot-a",SourceFilters(audioLanguage="fra"),null)),transport.requests)
    }
    @Test fun `legacy response hides filters and cannot call cache-only endpoint`() = runTest {
        val legacy=filterRow(0).copy(result=SourceResult.from(filterRow(0).result.raw,0)!!)
        val transport=Transport().apply {initial={SourceSearchReply.Legacy(listOf(legacy))}}
        val state=state(transport);state.reload();runCurrent();assertFalse(state.supported);state.edit(SourceFilters(audioLanguage="fra"));state.apply();runCurrent();assertTrue(transport.requests.isEmpty());assertNotNull(state.selection(state.rows.single()))
    }
    @Test fun `filtered paging preserves global indices and never re-searches or filters only local rows`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(indices=listOf(7),total=2,cursor="cursor-a"))};pages={id,f,cur->assertEquals("cursor-a",cur);filterPage(f,listOf(42),2,snapshot=id)}}
        val state=state(transport);state.reload();runCurrent();state.loadMore();runCurrent()
        assertEquals(listOf(7,42),state.rows.map {it.result.index});assertEquals(1,transport.searches);assertFalse(state.hasMore);assertEquals(2,state.total)
    }
    @Test fun `quiet changed view cannot append or reorder old cursor results`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(total=2,cursor="cursor-a"))};pages={id,f,cur->if(cur!=null)filterPage(f,listOf(42),2,snapshot=id,view="changed-view")else filterPage(f,listOf(75),1,snapshot=id,view="fresh-view")}}
        val state=state(transport);state.reload();runCurrent();state.loadMore();runCurrent();assertEquals(listOf(0),state.rows.map {it.sourceIndex});assertTrue(state.expired);assertFalse(state.requiresReload)
        state.retry();runCurrent();assertEquals(listOf(75),state.rows.map {it.sourceIndex});assertEquals(1,transport.searches);assertNull(transport.requests.last().third)
    }
    @Test fun `duplicate or cycling pagination is rejected without an automatic retry loop`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(total=3,cursor="cursor-a"))};pages={id,f,_->filterPage(f,listOf(0),3,"cursor-a",snapshot=id)}}
        val state=state(transport);state.reload();runCurrent();state.loadMore();runCurrent();assertEquals(1,transport.requests.size);assertEquals(1,state.rows.size);assertNotNull(state.pageError);assertTrue(state.expired)
    }
    @Test fun `late masked page completion cannot enter a replacement filter view`() = runTest {
        val gate=CompletableDeferred<Unit>();val transport=Transport().apply {
            initial={SourceSearchReply.Supported(filterPage(total=2,cursor="cursor-a"))}
            pages={id,f,cur->if(cur!=null){withContext(NonCancellable){gate.await()};filterPage(f,listOf(42),2,snapshot=id)}else filterPage(f,listOf(75),snapshot=id,view="fresh-view")}
        }
        val state=state(transport);state.reload();runCurrent();state.loadMore();runCurrent();state.edit(SourceFilters(audioLanguage="fra"));state.apply();runCurrent();gate.complete(Unit);runCurrent()
        assertEquals(listOf(75),state.rows.map {it.sourceIndex});assertEquals("fra",state.applied.audioLanguage);assertEquals(1,transport.searches)
    }
    @Test fun `old row actions do not start playback or probes after applying a new view`() = runTest {
        val transport=Transport();val state=state(transport);state.reload();runCurrent();val old=state.selection(state.rows.single())!!;var actions=0
        state.edit(SourceFilters(audioLanguage="fra"));state.apply();runCurrent();state.use(old){actions++};assertEquals(0,actions)
        state.use(state.selection(state.rows.single())!!){actions++};assertEquals(1,actions)
    }
    @Test fun `identical apply observably recreates callbacks rather than silently disabling them`() = runTest {
        val same=filterPage();val transport=Transport().apply {initial={SourceSearchReply.Supported(same)};pages={_,_,_->same}}
        val state=state(transport);state.reload();runCurrent();val observer=androidx.compose.runtime.snapshots.SnapshotStateObserver {it()};observer.start();val scope=Any();var changes=0;var action:SourceSearchState.Selection?=null
        observer.observeReads(scope,{_:Any->changes++}){action=state.selection(state.rows.single())};androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();changes=0
        val old=action!!;state.apply();runCurrent();androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();assertTrue(changes>0);assertFalse(state.accepts(old))
        observer.observeReads(scope,{_:Any->changes++}){action=state.selection(state.rows.single())};assertTrue(state.accepts(action!!));observer.stop();observer.clear()
    }
    @Test fun `view expiry performs no requests and explicit apply can create a fresh cached view`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(expiry=filterAt.plusSeconds(1)))};pages={id,f,_->filterPage(f,snapshot=id,view="new-view",expiry=filterAt.plusSeconds(600))}}
        val state=state(transport);state.reload();runCurrent();val old=state.selection(state.rows.single())!!;advanceTimeBy(1001);runCurrent()
        assertTrue(state.expired);assertFalse(state.requiresReload);assertNull(state.selection(state.rows.single()));assertFalse(state.accepts(old));assertEquals(1,transport.searches);assertTrue(transport.requests.isEmpty())
        state.apply();runCurrent();assertFalse(state.expired);assertEquals(1,transport.searches);assertEquals(1,transport.requests.size)
    }
    @Test fun `clock expiry blocks old action even before expiry job resumes`() = runTest {
        var clock=filterAt;val transport=Transport();val state=state(transport){clock};state.reload();runCurrent();val action=state.selection(state.rows.single())!!;clock=filterAt.plusSeconds(601)
        assertFalse(state.accepts(action));assertTrue(state.expiredNow);assertEquals("Filter data expired",SourceFilterMenus.rowLabel(state.rows.single(),state));assertEquals(1,transport.searches)
    }
    @Test fun `view invalidation recovery remains cache-only while snapshot loss needs explicit reload`() = runTest {
        var failure:Exception?=viewError();val transport=Transport().apply {pages={id,f,_->failure?.let {throw it};filterPage(f,snapshot=id,view="new-view")}}
        val state=state(transport);state.reload();runCurrent();state.apply();runCurrent();assertTrue(state.expired);assertFalse(state.requiresReload);assertFalse(state.error!!.contains("private"));assertEquals(1,transport.searches)
        failure=null;state.retry();runCurrent();assertFalse(state.expired);assertEquals(1,transport.searches)
        failure=ApiError.Http(HttpFailure(410,"""{"code":"source_snapshot_expired","error":"private"}"""));state.apply();runCurrent();assertTrue(state.requiresReload);assertEquals(1,transport.searches)
        state.retry();runCurrent();assertEquals(2,transport.searches)
    }
    @Test fun `health is started once for initial budget and never for filter pages or hidden indices`() = runTest {
        val row=filterRow(0).let {r->r.copy(result=SourceResult.from(JsonObject(r.result.raw+("sourceHealth" to buildJsonObject {put("state","pending");put("token","health-a")})),0,"snapshot-a")!!)}
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage().copy(rows=listOf(row),sourceHealthToken="health-a"))};checks={SourceHealthSnapshot(true,listOf(SourceHealthSnapshot.Entry(0,SourceHealth("cached")),SourceHealthSnapshot.Entry(75,SourceHealth("cached"))))};pages={id,f,_->filterPage(f,listOf(75),snapshot=id,view="filtered-view")}}
        val state=state(transport);state.reload();runCurrent();assertEquals(listOf("health-a"),transport.healthCalls);assertEquals("cached",state.healthFor(state.rows.single())!!.state)
        state.apply();runCurrent();assertNull(state.healthFor(state.rows.single()));assertEquals(listOf("health-a"),transport.healthCalls)
    }
    @Test fun `profile change rejects late source results and queued requests without replacement profile reads`() = runTest {
        val gate=CompletableDeferred<Unit>();val transport=Transport().apply {initial={withContext(NonCancellable){gate.await()};SourceSearchReply.Supported(filterPage())}}
        val state=state(transport);state.reload();runCurrent();ProfileDataContext.invalidate();runCurrent();gate.complete(Unit);runCurrent();assertTrue(state.rows.isEmpty());assertFalse(state.isCurrent);state.reload();runCurrent();assertEquals(1,transport.searches)
    }
    @Test fun `page disposal rejects stale library callbacks and source selections`() = runTest {
        val transport=Transport();val state=state(transport);state.reload();runCurrent();val selection=state.selection(state.rows.single())!!;var calls=0;state.dispose();state.use(selection){calls++};state.manualLibrary {calls++};state.apply();state.reload();runCurrent();assertEquals(0,calls);assertEquals(1,transport.searches)
    }
    @Test fun `explicit probe completion marks metadata dirty without applying filters or adding a request`() = runTest {
        val transport=Transport().apply {pages={id,f,_->filterPage(f,snapshot=id,view="file-view")}}
        val state=state(transport);state.reload();runCurrent();state.edit(SourceFilters(SourceFilterBasis.PROBED,audioLanguage="fra"));state.apply();runCurrent();val before=transport.requests.size
        state.probeChanged(state.rows.single().result);runCurrent();assertTrue(state.metadataChanged);assertEquals(before,transport.requests.size);assertEquals(1,transport.searches)
        state.probeChanged(SourceResult.from(state.rows.single().result.raw,0,"other-snapshot")!!);assertEquals(before,transport.requests.size)
    }
    @Test fun `wrong echoed target fails before publishing rows or starting health`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(target=SourceSearchTarget("tmdb:movie:99","movie"),cursor=null))}}
        val state=state(transport);state.reload();runCurrent();assertTrue(state.rows.isEmpty());assertNotNull(state.error);assertTrue(transport.healthCalls.isEmpty());assertTrue(state.requiresReload)
    }
    @Test fun `manual library choices remain deliberate and independent of stream filter expiry`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(expiry=filterAt.plusSeconds(1)))}}
        val state=state(transport);state.reload();runCurrent();advanceTimeBy(1001);runCurrent();var picks=0;state.manualLibrary {picks++};assertEquals(1,picks);assertEquals(1,transport.searches);assertTrue(transport.requests.isEmpty())
    }
    @Test fun `fresh manual panel survives old view proof expiry but not report context revocation`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(expiry=filterAt.plusSeconds(1)))}}
        val state=state(transport);state.reload();runCurrent();val row=state.rows.single();val intent=state.beginManual(row)!!
        state.probeChanged(row.result);assertTrue(state.metadataChanged);assertNull(state.selection(row));assertTrue(state.acceptsManual(intent))
        advanceTimeBy(1001);runCurrent();assertTrue(state.expiredNow);assertTrue(state.acceptsManual(intent));assertEquals(1,transport.searches);assertTrue(transport.requests.isEmpty())
        state.probeFailed(row.result,ApiError.Http(HttpFailure(409,"""{"code":"source_snapshot_context_changed","error":"private"}""")))
        assertTrue(state.requiresReload);assertFalse(state.acceptsManual(intent));assertFalse(state.error!!.contains("private"))
    }
    @Test fun `manual panel action consumes once and cannot cross source or profile generations`() = runTest {
        val transport=Transport();val state=state(transport);state.reload();runCurrent();val first=state.beginManual(state.rows.single())!!
        val second=state.beginManual(state.rows.single())!!;assertFalse(state.acceptsManual(first));var plays=0
        state.useManual(second){plays++};state.useManual(second){plays++};assertEquals(1,plays)
        val reloaded=state.beginManual(state.rows.single())!!;state.reload();runCurrent();assertFalse(state.acceptsManual(reloaded))
        val old=state.beginManual(state.rows.single())!!;ProfileDataContext.invalidate();assertFalse(state.acceptsManual(old));runCurrent();assertEquals(2,transport.searches)
    }
    @Test fun `client change rejects normal and manual actions without reading new client data`() = runTest {
        var client="synthetic-a";val transport=Transport()
        val state=SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,{filterAt},clientIdentity={client})
        state.reload();runCurrent();val row=state.rows.single();val selection=state.selection(row)!!;val manual=state.beginManual(row)!!
        client="synthetic-b";assertFalse(state.accepts(selection));assertFalse(state.acceptsManual(manual));state.apply();state.reload();runCurrent();assertEquals(1,transport.searches);assertTrue(transport.requests.isEmpty())
    }

    @Test fun `a continuation cannot silently renew the frozen view proof expiry`() = runTest {
        val transport=Transport().apply {initial={SourceSearchReply.Supported(filterPage(total=2,cursor="cursor-a",expiry=filterAt.plusSeconds(30)))};pages={id,f,_->filterPage(f,listOf(42),2,snapshot=id,expiry=filterAt.plusSeconds(600))}}
        val state=state(transport);state.reload();runCurrent();state.loadMore();runCurrent()
        assertEquals(listOf(0),state.rows.map {it.sourceIndex});assertTrue(state.expired);assertFalse(state.requiresReload);assertNotNull(state.pageError)
        assertEquals(1,transport.searches);assertEquals(1,transport.requests.size)
    }

}
