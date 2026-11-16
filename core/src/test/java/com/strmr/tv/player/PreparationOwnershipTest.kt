package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparationOwnershipTest {
    private class Requests : ReleaseReportRequests {
        val calls = mutableListOf<Triple<String,String,Long>>()
        var hold: suspend (String,Long) -> Unit = { _, _ -> }
        private suspend fun begin(kind: String, id: String, expected: Long) {
            calls += Triple(kind,id,expected)
            hold(kind,expected)
            // The real API helpers construct their selector under this same expected epoch.
            ApiClient.preparationQuery(expected)
        }
        override suspend fun options(id: String, expected: Long): ReleaseReportOptions { begin("options",id,expected); return ReleaseReportOptions("synthetic-version") }
        override suspend fun submit(id: String, reporting: Boolean, body: JsonObject, expected: Long): ReleaseReportReply { begin(if(reporting)"report" else "retry",id,expected); return ReleaseReportReply(replacement=PrequeueStartResponse("owned-replacement")) }
        override suspend fun status(id: String, expected: Long): PrequeueStatus { begin("status",id,expected); return PrequeueStatus(id,"ready",streamPath="synthetic-owned-file") }
        override suspend fun cancel(id: String, expected: Long) { begin("cancel",id,expected) }
        override suspend fun undo(id: String, expected: Long) { begin("undo",id,expected) }
    }
    private suspend fun cancelled(action: suspend () -> Unit) {
        try { action(); fail("Old preparation context was accepted") } catch (_: CancellationException) { }
    }
    @Test fun `actual server gateway forwards one owner epoch and stable IDs through all operations`() = runTest {
        val owner=PlaybackSessionContext.capture();val requests=Requests();val gateway=ServerReleaseReportGateway(owner,requests)
        gateway.options("owned-playing");gateway.submit("owned-playing",true,buildJsonObject {});gateway.submit("owned-playing",false,buildJsonObject {})
        gateway.status("owned-replacement");gateway.cancel("owned-replacement");gateway.undo("owned-report")
        assertEquals(listOf("options","report","retry","status","cancel","undo"),requests.calls.map {it.first})
        assertTrue(requests.calls.all {it.third==owner.epoch});assertEquals(listOf("owned-playing","owned-playing","owned-playing","owned-replacement","owned-replacement","owned-report"),requests.calls.map {it.second})
    }
    @Test fun `same profile A B A never revives gateway or stale cleanup`() = runTest {
        val requests=Requests();val gateway=ServerReleaseReportGateway(PlaybackSessionContext.capture(),requests)
        ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        assertFalse(gateway.isCurrent())
        cancelled {gateway.options("owned")};cancelled {gateway.submit("owned",false,buildJsonObject {})};cancelled {gateway.status("owned")};cancelled {gateway.undo("report")}
        gateway.cancel("owned");assertTrue(requests.calls.isEmpty())
    }
    @Test fun `late operations cannot construct replacement selectors or publish after old precheck`() = runTest {
        for(kind in listOf("options","report","retry","status","undo","cancel")) {
            val owner=PlaybackSessionContext.capture();val requests=Requests();val arrived=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var selectors=0
            requests.hold={_,expected -> arrived.complete(Unit);withContext(NonCancellable){release.await()};ApiClient.preparationQuery(expected);selectors++}
            val gateway=ServerReleaseReportGateway(owner,requests)
            val job=async {
                when(kind) {
                    "options" -> gateway.options("owned")
                    "report" -> gateway.submit("owned",true,buildJsonObject {})
                    "retry" -> gateway.submit("owned",false,buildJsonObject {})
                    "status" -> gateway.status("owned")
                    "undo" -> gateway.undo("report")
                    else -> gateway.cancel("owned")
                }
            }
            arrived.await();ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.complete(Unit)
            cancelled {job.await()};assertEquals(0,selectors);assertEquals(owner.epoch,requests.calls.single().third)
            gateway.cancel("owned");assertEquals(1,requests.calls.size)
        }
    }
    @Test fun `postcheck rejects even a transport that masks context checks`() = runTest {
        val owner=PlaybackSessionContext.capture();val arrived=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val requests=object:ReleaseReportRequests {
            override suspend fun options(id:String,expected:Long):ReleaseReportOptions {arrived.complete(Unit);withContext(NonCancellable){release.await()};return ReleaseReportOptions("old")}
            override suspend fun submit(id:String,reporting:Boolean,body:JsonObject,expected:Long)=error("Unexpected")
            override suspend fun status(id:String,expected:Long)=error("Unexpected")
            override suspend fun cancel(id:String,expected:Long)=error("Stale cleanup")
            override suspend fun undo(id:String,expected:Long)=error("Unexpected")
        }
        val gateway=ServerReleaseReportGateway(owner,requests);val job=async {gateway.options("owned")}
        arrived.await();ProfileDataContext.invalidate();release.complete(Unit)
        cancelled {job.await()};gateway.cancel("owned")
    }
    @Test fun `real report controller cannot adopt or clean with a replacement owner after late retry`() = runTest {
        val owner=PlaybackSessionContext.capture();val requests=Requests();val release=CompletableDeferred<Unit>();val arrived=CompletableDeferred<Unit>();var adopted=0
        requests.hold={kind,_ -> if(kind=="retry"){arrived.complete(Unit);withContext(NonCancellable){release.await()}}}
        val flow=ReleaseReports("owned-playing",ServerReleaseReportGateway(owner,requests),backgroundScope,{0},{_,_,_->adopted++},clientId="synthetic-client")
        flow.load();flow.submit(false,true);runCurrent();arrived.await();ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.complete(Unit);runCurrent()
        flow.take();flow.close();runCurrent()
        assertEquals(0,adopted);assertNull(flow.state.value.ready);assertEquals(listOf("options","retry"),requests.calls.map {it.first})
    }
    @Test fun `stale original constructor refuses to capture new profile epoch`() {
        val expected=ProfileDataContext.current;ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        try {ServerReleaseReportGateway(expected);fail("Original owner replaced")}catch(_:CancellationException){}
    }
}
