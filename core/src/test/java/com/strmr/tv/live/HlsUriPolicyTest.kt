package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.castMediaUrl
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Pure production URI policy and actual stale URL wrappers, without Android credential storage. */
@OptIn(ExperimentalCoroutinesApi::class)
class HlsUriPolicyTest {
    private val profile="synthetic-owner"
    private val base="https://example.invalid/proxy/".toHttpUrl()
    private fun policy()=HlsUriPolicy(base,profile)
    @Test fun `API-less API-prefixed and proxy paths keep query encoding and never duplicate prefix`() {
        for(root in listOf("https://example.invalid/proxy/","https://example.invalid/proxy")) {
            val policy=HlsUriPolicy(root.toHttpUrl(),profile)
            for(path in listOf("/video/hls/synthetic/stream.m3u8","video/hls/synthetic/stream.m3u8","/api/video/hls/synthetic/stream.m3u8","api/video/hls/synthetic/stream.m3u8","/proxy/api/video/hls/synthetic/stream.m3u8","https://example.invalid/proxy/api/video/hls/synthetic/stream.m3u8")) {
                val url=policy.media("$path?x=a%2Bb&x=c%26d&flag"){emptyList()}.toHttpUrl()
                assertEquals("/proxy/api/video/hls/synthetic/stream.m3u8",url.encodedPath);assertEquals("x=a%2Bb&x=c%26d&flag&profileId=synthetic-owner",url.encodedQuery)
            }
        }
    }
    @Test fun `only registered HLS assets receive a profile selector`() {
        for(file in listOf("master.m3u8","stream.m3u8","init.mp4","segment0.ts","segment37.m4s","segment2.mp4","subtitle-0.m3u8","subtitles-7.vtt","subtitles.vtt","captions.srt")) {
            val url=base.resolve("api/video/hls/synthetic/$file")!!;assertTrue(file,policy().asset(url));assertEquals(profile,policy().withProfile(url).queryParameter("profileId"))
        }
        for(path in listOf("api/video/hls/start","api/video/hls/synthetic/status","api/video/hls/synthetic/cc-status","api/video/hls/synthetic/stop","api/video/hls/synthetic/seek","api/video/hls/synthetic/keepalive","api/video/hls/synthetic/unknown.json","api/video/hls/synthetic/extra/segment0.ts","api/video/hls/id%2Fother/stream.m3u8","api/video/hls/synthetic/file%2Fsegment0.ts","api/video/hls/synthetic/segment-1.ts")) {
            val url=base.resolve(path)!!;assertFalse(path,policy().asset(url));assertEquals(url,policy().withProfile(url))
        }
    }
    @Test fun `scheme host port and reverse proxy namespace must all match`() {
        for(raw in listOf("http://example.invalid/proxy/api/video/hls/id/stream.m3u8","https://example.invalid:444/proxy/api/video/hls/id/stream.m3u8","https://provider.invalid/proxy/api/video/hls/id/stream.m3u8","https://example.invalid/api/video/hls/id/stream.m3u8","https://example.invalid/proxy-other/api/video/hls/id/stream.m3u8")) {
            val url=raw.toHttpUrl();assertEquals(url,policy().withProfile(url));var credentials=0
            assertEquals(raw,policy().media(raw){credentials++;listOf("token" to "synthetic-backend")});assertEquals(0,credentials)
        }
    }
    @Test fun `all case variant and duplicate owner selectors must agree before credential reads`() {
        for(query in listOf("profileId=foreign","PROFILEID=foreign","userId=foreign","profileId","profileId=","profileId=synthetic-owner&profileId=foreign","profileId=synthetic-owner&USERID=foreign")) {
            var credentials=0
            try{policy().media("/video/hls/id/stream.m3u8?$query"){credentials++;emptyList()};fail(query)}catch(error:IOException){assertEquals("HLS profile context changed",error.message)}
            assertEquals(0,credentials)
        }
        val query="profileId=synthetic-owner&PROFILEID=synthetic-owner&userId=synthetic-owner&x=a%2Bb"
        assertEquals(query,policy().media("/video/hls/id/stream.m3u8?$query"){emptyList()}.toHttpUrl().encodedQuery)
    }
    @Test fun `existing authentication and unrelated query are preserved without new duplicate auth`() {
        val url=policy().media("/video/hls/id/master.m3u8?token=synthetic-server-token&profileUnlock=synthetic-server-pin&x=a%2Bb&x=two") {listOf("token" to "synthetic-captured-token","profileUnlock" to "synthetic-captured-pin")}.toHttpUrl()
        assertEquals(listOf("synthetic-server-token"),url.queryParameterValues("token"));assertEquals(listOf("synthetic-server-pin"),url.queryParameterValues("profileUnlock"));assertEquals(listOf("a+b","two"),url.queryParameterValues("x"))
        val fresh=policy().media("/video/hls/id/stream.m3u8"){listOf("token" to "synthetic+a&b","profileUnlock" to "synthetic-pin")}.toHttpUrl()
        assertEquals("synthetic+a&b",fresh.queryParameter("token"));assertEquals("synthetic-pin",fresh.queryParameter("profileUnlock"));assertEquals(profile,fresh.queryParameter("profileId"))
    }
    @Test fun `external provider URL query and ownerlike fields remain unchanged and never read credentials`() {
        val raw="https://provider.invalid/frame?token=synthetic-provider&profileId=provider-owner&x=a%2Bb&x=c"
        var credentials=0;assertEquals(raw,policy().media(raw){credentials++;error("External credential read")});assertEquals(0,credentials)
        assertEquals("https://provider.invalid/frame?x=a%2Bb",policy().media("//provider.invalid/frame?x=a%2Bb"){error("External credential read")})
        // This actual wrapper succeeds without Android Config/secure stores because the URL is external.
        assertEquals(raw,ApiClient.mediaUrl(raw))
    }
    @Test fun `malformed media input fails with fixed message before credential reads`() {
        for(raw in listOf(""," ","?x=one","#fragment","ftp://provider.invalid/file","/video/hls/id/stream.m3u8\n")) {
            var credentials=0
            try{policy().media(raw){credentials++;emptyList()};fail("Malformed media URL accepted")}catch(error:IOException){assertEquals("Media URL is unavailable",error.message)}
            assertEquals(0,credentials)
        }
    }
    @Test fun `actual old context URL builder cannot look up replacement route token or profile`()=runTest {
        val expected=ProfileDataContext.current;val reply=CompletableDeferred<String>();var published=0
        val job=async{val path=reply.await();ApiClient.mediaUrl(path,expected);published++}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();reply.complete("/video/hls/old/stream.m3u8")
        try{job.await();fail("Old recording URL adopted")}catch(_:CancellationException){}
        try{ApiClient.castMediaUrl("/api/video/hls/old/stream.m3u8",expected);fail("Old Cast URL adopted")}catch(_:CancellationException){}
        assertEquals(0,published)
    }
}
