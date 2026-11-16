package com.strmr.mobile.downloads

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadResumeDenialTest {
    @Test fun `server resume authorization rejection cannot fall through to device progress`()=runTest {
        var reads=0
        try {resolveDownloadResume(ProfileDataContext.current,{throw ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied"}"""))},{reads++;StartPoint.Seconds(100.0)});fail()}catch(_:ApiError.Http){}
        assertEquals(0,reads)
    }
    @Test fun `late resumed metadata cannot read local progress after A to B to A`()=runTest {
        val context=ProfileDataContext.current;val gate=CompletableDeferred<Unit>();var offline=0
        val job=backgroundScope.launch {resolveDownloadResume(context,{withContext(NonCancellable){gate.await()};null},{offline++;StartPoint.Seconds(100.0)})}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();gate.complete(Unit);runCurrent();assertTrue(job.isCancelled);assertEquals(0,offline)
    }
    @Test fun `a missing resume record retains the ordinary local resume behavior in the same context`()=runTest {
        val at=resolveDownloadResume(ProfileDataContext.current,{null},{StartPoint.Seconds(100.0)})
        assertEquals(StartPoint.Seconds(100.0),at)
    }
}
