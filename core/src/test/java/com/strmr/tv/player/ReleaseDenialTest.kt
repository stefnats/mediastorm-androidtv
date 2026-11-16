package com.strmr.tv.player

import com.strmr.tv.api.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReleaseDenialTest {
    private class Gateway(val failAt:String):ReleaseReportGateway {
        var submits=0;var polls=0;var cancels=0;var options=0
        private fun deny():Nothing=throw ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied","error":"private"}"""))
        override fun isCurrent()=true
        override suspend fun options(id:String):ReleaseReportOptions {options++;if(failAt=="options")deny();return ReleaseReportOptions("version","Synthetic","fra")}
        override suspend fun submit(id:String,reporting:Boolean,body:JsonObject):ReleaseReportReply {submits++;if(failAt=="submit")deny();return ReleaseReportReply(replacement=PrequeueStartResponse("replacement"))}
        override suspend fun status(id:String):PrequeueStatus {polls++;deny()}
        override suspend fun cancel(id:String) {cancels++}
        override suspend fun undo(id:String) {error("No undo")}
    }
    @Test fun `denied options never start alternative preparation or repeated options`()=runTest {
        val gateway=Gateway("options");val flow=ReleaseReports("playing",gateway,backgroundScope,{0},{_,_,_->fail()},clientId="synthetic")
        flow.load();flow.submit(false,true);flow.load();runCurrent()
        assertEquals(1,gateway.options);assertEquals(0,gateway.submits);assertNull(flow.state.value.options);assertEquals(VodAccessDenial.BLOCKED,flow.state.value.denial);assertFalse(flow.state.value.message!!.contains("private"))
        flow.close()
    }
    @Test fun `denied retry submit clears options and cannot be retried through the same panel`()=runTest {
        val gateway=Gateway("submit");val flow=ReleaseReports("playing",gateway,backgroundScope,{0},{_,_,_->fail()},clientId="synthetic")
        flow.load();flow.submit(false,true);runCurrent();flow.submit(false,true);runCurrent()
        assertEquals(1,gateway.submits);assertEquals(0,gateway.polls);assertNull(flow.state.value.ready);assertNull(flow.state.value.options);assertEquals(VodAccessDenial.BLOCKED,flow.state.value.denial);flow.close()
    }
    @Test fun `denied replacement polling stops after one status and releases only its existing preparation`()=runTest {
        val gateway=Gateway("status");var adopted=0;val flow=ReleaseReports("playing",gateway,backgroundScope,{0},{_,_,_->adopted++},clientId="synthetic",pollDelayMs=0)
        flow.load();flow.submit(false,true);runCurrent();flow.take();flow.submit(false,true);runCurrent()
        assertEquals(1,gateway.polls);assertEquals(1,gateway.submits);assertEquals(1,gateway.cancels);assertEquals(0,adopted);assertNull(flow.state.value.ready);flow.close()
    }
}
