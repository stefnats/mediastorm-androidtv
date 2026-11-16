package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** URL and cache boundaries are the production helper consumed by both platform scrubbers. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailImageAccessTest {
    private val headers=mapOf("Authorization" to "Bearer synthetic-backend-token","X-Profile-Unlock" to "synthetic-unlock","X-Remote-Access-Credential" to "synthetic-pairing","X-Client-ID" to "synthetic-client")
    private fun access(base:String="https://example.invalid/proxy/")=ThumbnailImageAccess(base.toHttpUrl(),headers,PlaybackSessionContext.capture())
    @Test fun `server relative queries and reverse proxy paths roundtrip without lost or duplicate prefix`() {
        val access=access();val profile=PlaybackSessionContext.capture().profileId
        for(raw in listOf("/api/video/thumbnails/image/key/frame.jpg?view=a%2Bb&size=one&size=two","/proxy/api/video/thumbnails/image/key/frame.jpg?view=a%2Bb&size=one&size=two","api/video/thumbnails/image/key/frame.jpg?view=a%2Bb&size=one&size=two","https://example.invalid/proxy/api/video/thumbnails/image/key/frame.jpg?view=a%2Bb&size=one&size=two")) {
            val url=access.image(raw)!!.url.toHttpUrl()
            assertEquals("/proxy/api/video/thumbnails/image/key/frame.jpg",url.encodedPath);assertEquals("a+b",url.queryParameter("view"));assertEquals(listOf("one","two"),url.queryParameterValues("size"));assertEquals(profile,url.queryParameter("profileId"));assertFalse(url.encodedPath.contains("/proxy/proxy/"))
        }
    }
    @Test fun `configured proxy path without trailing slash still acts as a directory`() {
        val url=access("https://example.invalid/proxy").image("api/video/thumbnails/image/key/frame.jpg?view=synthetic")!!.url.toHttpUrl()
        assertEquals("/proxy/api/video/thumbnails/image/key/frame.jpg",url.encodedPath);assertEquals("synthetic",url.queryParameter("view"))
    }
    @Test fun `external provider queries preserve original duplicates order and credential values distinct from backend`() {
        val raw="https://provider.invalid/frame.jpg?token=synthetic-provider-token&profileId=provider-profile&x=a%2Bb&x=c"
        assertEquals(raw,access().image(raw)!!.url)
    }
    @Test fun `foreign empty and conflicting own selectors fail closed without rewriting another profile`() {
        val access=access();val own=PlaybackSessionContext.capture().profileId
        for(query in listOf("profileId=synthetic-other","profileId=","profileId=$own&userId=synthetic-other","PROFILEID=synthetic-other","profileId=$own&profileId=synthetic-other","userId")) {
            assertNull(access.image("/api/video/thumbnails/image/key/frame.jpg?$query"))
        }
        val raw="/api/video/thumbnails/image/key/frame.jpg?userId=$own&profileId=$own&view=one"
        assertEquals("userId=$own&profileId=$own&view=one",access.image(raw)!!.url.toHttpUrl().encodedQuery)
    }
    @Test fun `external URLs containing exact captured secrets are rejected while ordinary provider tokens remain`() {
        for(secret in listOf("synthetic-backend-token","synthetic-unlock","synthetic-pairing"))assertNull(access().image("https://provider.invalid/frame.jpg?opaque=$secret"))
        assertNotNull(access().image("https://provider.invalid/frame.jpg?token=synthetic-provider-token"))
    }
    @Test fun `cache keys are opaque stable within player and isolated between player snapshots`() {
        val raw="/api/video/thumbnails/image/key/frame.jpg?token=synthetic-backend-token";val a=access();val first=a.image(raw)!!
        assertEquals(first.cacheKey,a.image(raw)!!.cacheKey);assertNotEquals(first.cacheKey,access().image(raw)!!.cacheKey)
        for(secret in headers.values+listOf("synthetic-backend-token",PlaybackSessionContext.capture().profileId))assertFalse(first.cacheKey.contains(secret))
    }
    @Test fun `old request creation and cached read are unavailable synchronously on A B A`()=runTest {
        val access=access();val raw="/api/video/thumbnails/image/key/frame.jpg";assertNotNull(access.image(raw));var cacheReads=0
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();assertNull(access.image(raw))
        try {access.read {cacheReads++;"cached adult frame"};fail("Stale cache was read")}catch(_:CancellationException){}
        assertEquals(0,cacheReads)
    }
    @Test fun `a cached result that completes after context change is never displayed`()=runTest {
        val access=access();val value=CompletableDeferred<String>();var displayed=0
        val old=async {access.read {value.await()};displayed++};runCurrent();ProfileDataContext.invalidate();value.complete("cached frame")
        try {old.await();fail("Late cache displayed")}catch(_:CancellationException){};assertEquals(0,displayed)
    }
    @Test fun `closed image pipeline cannot recreate loader work or return cached frames in the same epoch`()=runTest {
        val access=access();val value=CompletableDeferred<String>();var displayed=0
        val old=async {access.read {value.await()};displayed++};runCurrent();access.retire();value.complete("cached frame")
        try {old.await();fail("Retired frame displayed")}catch(_:CancellationException){}
        assertEquals(0,displayed);assertNull(access.image("/api/video/thumbnails/image/key/frame.jpg"));assertFalse(access.isCurrent())
    }
}
