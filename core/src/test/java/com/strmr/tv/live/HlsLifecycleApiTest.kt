package com.strmr.tv.live

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual pure error/producer helpers; Android credential stores and real services are not used. */
@OptIn(ExperimentalCoroutinesApi::class)
class HlsLifecycleApiTest {
    private suspend fun cancelled(action:suspend()->Unit){try{action();fail("Old context accepted")}catch(_:CancellationException){}}
    @Test fun `normal keepalive growth and legacy empty object retain existing semantics`()=runTest {
        val expected=ProfileDataContext.current
        assertEquals(true,readHlsKeepalive(expected,false,true){"{\"growing\":true}"})
        assertEquals(false,readHlsKeepalive(expected,false,true){"{\"growing\":false}"})
        assertEquals(false,readHlsKeepalive(expected,false,true){"{}"})
    }
    @Test fun `ordinary unavailable or malformed keepalive stays unknown in strict mode`()=runTest {
        val expected=ProfileDataContext.current
        assertNull(readHlsKeepalive(expected,false,true){throw IOException("Synthetic unavailable")})
        assertNull(readHlsKeepalive(expected,false,true){"invalid JSON"})
    }
    @Test fun `strict opt in propagates terminal denial while untouched legacy caller stays compatible`()=runTest {
        val expected=ProfileDataContext.current
        for(status in listOf(401,403,400)) {
            val error=ApiError.Http(HttpFailure(status,if(status==400)"{\"code\":\"kids_vod_profile_required\"}"else"{}"))
            assertNull(readHlsKeepalive(expected,false,false){throw error})
            try{readHlsKeepalive(expected,false,true){throw error};fail()}catch(actual:ApiError){assertSame(error,actual)}
        }
    }
    @Test fun `capacity rejection keeps existing opt in behavior separately from authorization`()=runTest {
        val error=ApiError.Http(HttpFailure(409,"{\"code\":\"LIVE_STREAM_LIMIT_REACHED\",\"error\":\"Synthetic limit\"}"));val expected=ProfileDataContext.current
        assertNull(readHlsKeepalive(expected,false,true){throw error})
        try{readHlsKeepalive(expected,true,true){throw error};fail()}catch(actual:ApiError){assertSame(error,actual)}
    }
    @Test fun `real stale control guide stream list and catchup URL wrappers reject before Android IO`()=runTest {
        val expected=ProfileDataContext.current;ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        val channel=LiveChannel("synthetic","Synthetic","https://example.invalid/synthetic")
        val program=EpgProgram("Synthetic",start="2026-11-16T10:00:00Z",stop="2026-11-16T11:00:00Z")
        cancelled{ApiClient.keepLiveSessionAlive("owned",expectedContext=expected,throwOnAccessDenial=true)}
        cancelled{ApiClient.epgSchedule(listOf("synthetic"),-60,4,expected)};cancelled{ApiClient.liveStremioStreams(channel,expected)}
        cancelled{ApiClient.catchupStreamUrl(LiveCatchupStart("synthetic-owned-path"),channel,program,expected)}
    }
    @Test fun `queued identity producer stops before request after simulated A B A`()=runTest {
        val expected=ProfileDataContext.current;var reads=0;var cache=0
        val job=async{readLiveIdentity(expected,{reads++;"identity"},{cache++})}
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();cancelled{job.await()};assertEquals(0,reads);assertEquals(0,cache)
    }
    @Test fun `late old identity result cannot overwrite a new current cache publication`()=runTest {
        val expected=ProfileDataContext.current;val release=CompletableDeferred<String?>();var cache="initial"
        val job=async{readLiveIdentity(expected,{withContext(NonCancellable){release.await()}},{cache=it.orEmpty()})};runCurrent()
        ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        readLiveIdentity(ProfileDataContext.current,{"fresh"},{cache=it.orEmpty()});release.complete("old");cancelled{job.await()};assertEquals("fresh",cache)
    }
    @Test fun `identity cancellation never publishes a masked same epoch reply`()=runTest {
        val expected=ProfileDataContext.current;val release=CompletableDeferred<String?>();var cache=0
        val job=async{readLiveIdentity(expected,{withContext(NonCancellable){release.await()}},{cache++})};runCurrent();job.cancel();release.complete("old");cancelled{job.await()};assertEquals(0,cache)
    }
}
