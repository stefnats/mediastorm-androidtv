package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrailerOwnershipTest {
    private val trailer=Trailer(url="https://youtube.com/watch?v=synthetic")
    private suspend fun cancelled(action:suspend ()->Unit){try {action();fail("Old trailer context was accepted")}catch(_:CancellationException){}}
    @Test fun `queued old trailer cannot prepare poll or look up a replacement token`()=runTest {
        val expected=ProfileDataContext.current;var posts=0;var polls=0;var tokens=0
        val old=async {resolveTrailerUrl(trailer,expected,"synthetic-a",{_,_->posts++;TrailerStatus("synthetic-id","pending")},{polls++;TrailerStatus("synthetic-id","ready")},{tokens++;"synthetic-url"})}
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();cancelled {old.await()}
        assertEquals(0,posts);assertEquals(0,polls);assertEquals(0,tokens)
    }
    @Test fun `late POST reply cannot start a poll under B or resurrect after A B A`()=runTest {
        val expected=ProfileDataContext.current;val reply=CompletableDeferred<TrailerStatus>();var polls=0;var tokens=0;var postedProfile:String?=null
        val old=async {resolveTrailerUrl(trailer,expected,"synthetic-a",{body,query->assertEquals(trailer.url,body["url"].toString().trim('"'));postedProfile=query["profileId"];reply.await()},{polls++;TrailerStatus("synthetic-id","ready")},{tokens++;"synthetic-url"})}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();reply.complete(TrailerStatus("synthetic-id","pending"));cancelled {old.await()}
        assertEquals("synthetic-a",postedProfile);assertEquals(0,polls);assertEquals(0,tokens)
    }
    @Test fun `late ready poll never constructs serve URL with replacement profile or token`()=runTest {
        val expected=ProfileDataContext.current;val reply=CompletableDeferred<TrailerStatus>();var tokens=0;var polledProfile:String?=null
        val old=async {resolveTrailerUrl(trailer,expected,"synthetic-a",{_,_->TrailerStatus("synthetic-id","pending")},{query->polledProfile=query["profileId"];reply.await()},{tokens++;"synthetic-url"})}
        runCurrent();ProfileDataContext.invalidate();reply.complete(TrailerStatus("synthetic-id","ready"));cancelled {old.await()}
        assertEquals("synthetic-a",polledProfile);assertEquals(0,tokens)
    }
    @Test fun `poll delay retains original owner and cancellation does not become a null success`()=runTest {
        val expected=ProfileDataContext.current;val release=CompletableDeferred<Unit>();var polls=0;var serves=0
        val old=async {resolveTrailerUrl(trailer,expected,"synthetic-a",{_,_->TrailerStatus("synthetic-id","pending")},{polls++;TrailerStatus("synthetic-id","pending")},{serves++;"synthetic-url"},{release.await()})}
        runCurrent();assertEquals(1,polls);ProfileDataContext.invalidate();release.complete(Unit);cancelled {old.await()};assertEquals(1,polls);assertEquals(0,serves)
    }
    @Test fun `successful preparation preserves original profile without adding any requests`()=runTest {
        val expected=ProfileDataContext.current;var posts=0;var polls=0;var serves=0
        val value=resolveTrailerUrl(trailer,expected,"synthetic-a",{_,query->posts++;assertEquals("synthetic-a",query["profileId"]);TrailerStatus("synthetic-id","pending")},{query->polls++;assertEquals("synthetic-a",query["profileId"]);TrailerStatus("synthetic-id","ready")},{id->serves++;"synthetic:$id"})
        assertEquals("synthetic:synthetic-id",value);assertEquals(1,posts);assertEquals(1,polls);assertEquals(1,serves)
    }
    @Test fun `direct external trailer is never adopted by a stale caller and does not prequeue`()=runTest {
        val expected=ProfileDataContext.current;ProfileDataContext.invalidate()
        cancelled {resolveTrailerUrl(Trailer(url="https://example.invalid/synthetic.mp4"),expected,"synthetic-a",{_,_->error("Unexpected demand")},{error("Unexpected poll")},{error("Unexpected token")})}
    }
}
