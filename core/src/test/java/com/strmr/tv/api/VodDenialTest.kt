package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodDenialTest {
    private fun denied(code:String="kids_vod_access_denied",status:Int=403)=ApiError.Http(HttpFailure(status,"""{"code":"$code","error":"synthetic-private-provider-url"}"""))
    private val row=SourceResult.from(buildJsonObject {put("title","Synthetic");put("guid","synthetic")},0)!!
    @Test fun `all approved future denial codes are typed sanitized and never interpreted as profile unlock`() {
        for(code in listOf("profile_required","access_denied","classification_unknown","parent_approval_required","approval_expired","context_changed","unsupported_source")) {
            val error=denied("kids_vod_$code",if(code=="profile_required")400 else 403)
            assertNotNull(error.vodDenial);assertEquals(error.vodDenial!!.message,error.message)
            assertFalse(error.message!!.contains("private"));assertFalse(error.isProfileLocked);assertFalse(error.invalidatesSession)
        }
        assertTrue(denied("profile_locked").isProfileLocked)
        assertEquals(VodAccessDenial.PROFILE_LOCKED,denied("profile_locked").vodDenial)
    }
    @Test fun `missing optional data is distinct from authentication or access denial`() = runTest {
        for(status in listOf(404,405,500))assertNull(vodOptionalRead<String> {throw ApiError.Http(HttpFailure(status,"synthetic"))})
        for(error in listOf(denied(),denied("",401),ApiError.SignedOut(),CancellationException("closed"))) {
            var caught:Throwable?=null
            try {vodOptionalRead<String> {throw error}} catch(value:Throwable) {caught=value}
            assertSame(error,caught)
        }
    }
    @Test fun `manual resolution binds actual body and every queue query to the captured identity`() = runTest {
        val expected=ProfileDataContext.current;var posts=0;var polls=0
        val result=resolveManualSource(row,"tmdb:movie:41",12.0,"adult","synthetic-client",expected,
            post={body->posts++;assertEquals("adult",body["profileId"]!!.jsonPrimitive.content);assertEquals(row.raw,body["result"]);PlaybackResolution(queueId=7)},
            poll={id,query->polls++;assertEquals(7L,id);assertEquals("adult",query["profileId"]);assertEquals("synthetic-client",query["clientId"]);PlaybackResolution(webdavPath="synthetic/movie.mkv")},pause={})
        assertEquals("synthetic/movie.mkv",result.first);assertEquals(1,posts);assertEquals(1,polls)
    }
    @Test fun `profile A to B to A during manual queue wait cannot poll under a replacement epoch`() = runTest {
        val expected=ProfileDataContext.current;val waiting=CompletableDeferred<Unit>();var polls=0
        val job=backgroundScope.launch {resolveManualSource(row,"tmdb:movie:41",null,"profile-a","synthetic",expected,
            post={PlaybackResolution(queueId=7)},poll={_,_->polls++;PlaybackResolution(webdavPath="wrong")},pause={withContext(NonCancellable){waiting.await()}})}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();waiting.complete(Unit);runCurrent()
        assertTrue(job.isCancelled);assertEquals(0,polls)
    }
    @Test fun `masked late initial resolution is rejected before publishing a stream`() = runTest {
        val expected=ProfileDataContext.current;val gate=CompletableDeferred<Unit>();var published=false
        val job=backgroundScope.launch {
            resolveManualSource(row,"tmdb:movie:41",null,"adult","synthetic",expected,
                post={withContext(NonCancellable){gate.await()};PlaybackResolution(webdavPath="old-profile.mkv")},poll={_,_->error("No poll")})
            published=true
        }
        runCurrent();ProfileDataContext.invalidate();gate.complete(Unit);runCurrent();assertTrue(job.isCancelled);assertFalse(published)
    }
    @Test fun `queue denial propagates once without reposting or waiting for another candidate`() = runTest {
        var posts=0;var polls=0;val error=denied();var caught:Throwable?=null
        try {resolveManualSource(row,"tmdb:movie:41",null,"adult","synthetic",ProfileDataContext.current,
            post={posts++;PlaybackResolution(queueId=7)},poll={_,_->polls++;throw error},pause={})} catch(value:Throwable){caught=value}
        assertSame(error,caught);assertEquals(1,posts);assertEquals(1,polls)
    }
    @Test fun `stale manual calls are rejected before any transport or private identity read`() = runTest {
        val expected=ProfileDataContext.current;ProfileDataContext.invalidate();var posts=0
        try {resolveManualSource(row,"tmdb:movie:41",null,"adult","synthetic",expected,post={posts++;error("stale")},poll={_,_->error("stale")});fail()} catch(_:CancellationException){}
        try {ApiClient.resolveSource(row,"tmdb:movie:41",null,expected);fail()} catch(_:CancellationException){}
        assertEquals(0,posts)
    }
}
