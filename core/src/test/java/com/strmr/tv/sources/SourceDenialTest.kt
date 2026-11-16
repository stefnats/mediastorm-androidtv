package com.strmr.tv.sources

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceDenialTest {
    private fun denied()=ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied","error":"private"}"""))
    private class Transport:SourceSearchTransport {
        var searches=0;var healthReads=0
        var reply:SourceSearchReply=SourceSearchReply.Supported(filterPage())
        override suspend fun search(title:DetailsTarget,episode:Episode?,context:Long):SourceSearchReply {searches++;return reply}
        override suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long):SourceSearchPage=error("No fallback")
        override suspend fun health(token:String,context:Long):SourceHealthSnapshot {healthReads++;throw ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied"}"""))}
    }
    @Test fun `legacy source probe denial blocks source and library fallbacks without a capability marker`()=runTest {
        val row=filterRow(0).let {it.copy(result=SourceResult.from(it.result.raw,0)!!)}
        val transport=Transport().apply {reply=SourceSearchReply.Legacy(listOf(row))}
        val state=SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,{filterAt},{"synthetic"})
        state.reload();runCurrent();val intent=state.beginManual(state.rows.single())!!;val action=state.selection(state.rows.single())!!
        state.probeFailed(row.result,denied());var library=0;state.manualLibrary {library++};state.retry();runCurrent()
        assertEquals(0,library);assertEquals(1,transport.searches);assertFalse(state.accepts(action));assertFalse(state.acceptsManual(intent));assertEquals(VodAccessDenial.BLOCKED,state.vodDenied);assertTrue(state.rows.isEmpty());state.dispose()
    }
    @Test fun `health authorization denial cancels all remaining queued token reads`()=runTest {
        val rows=(0..1).map {index->val row=filterRow(index);val raw=JsonObject(row.result.raw+("sourceHealth" to buildJsonObject {put("state","pending");put("token","token-$index")}));row.copy(result=SourceResult.from(raw,index,"snapshot-a")!!)}
        val transport=Transport().apply {reply=SourceSearchReply.Supported(filterPage(indices=listOf(0,1)).copy(rows=rows))}
        val state=SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,{filterAt},{"synthetic"})
        state.reload();runCurrent();assertEquals(1,transport.healthReads);assertEquals(VodAccessDenial.BLOCKED,state.vodDenied);assertTrue(state.rows.isEmpty());state.dispose()
    }
}
