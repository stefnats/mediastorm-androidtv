package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.settings.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSettingsContextTest {
    private fun decode(text:String)=subtitleSettingsFrom(ApiJson.parseToJsonElement(text).jsonObject)
    private suspend fun cancelled(action:suspend()->Unit){try{action();fail("Canceled settings published")}catch(_:CancellationException){}}
    @Test fun `missing malformed or unavailable sections do not enable translation`() {
        for(raw in listOf("{}","{\"subtitles\":null}","{\"subtitles\":true}","{\"subtitles\":[]}"))assertEquals(SubtitleServerSettings(false,false),decode(raw))
    }
    @Test fun `successful legacy section keeps default while malformed known flag is never true`() {
        assertEquals(SubtitleServerSettings(false,true),decode("{\"subtitles\":{}}"))
        assertEquals(SubtitleServerSettings(true,true),decode("{\"subtitles\":{\"subdlApiKey\":\"synthetic-masked\",\"enableTranslatedSubs\":true}}"))
        for(flag in listOf("false","null","\"true\"","\"false\"","1","{}","[]"))assertFalse(decode("{\"subtitles\":{\"enableTranslatedSubs\":$flag}}").translationEnabled)
    }
    @Test fun `ordinary old server errors return unavailable not a translation grant`()=runTest {
        for(error in listOf(IOException("Synthetic unavailable"),ApiError.Http(HttpFailure(404,"{}")))) {
            assertEquals(SubtitleServerSettings(false,false),readSubtitleSettings(ProfileDataContext.current){throw error})
        }
    }
    @Test fun `auth denial and cancellation retain their identity instead of becoming defaults`()=runTest {
        val expected=ProfileDataContext.current
        for(status in listOf(401,403)) {
            val denied=ApiError.Http(HttpFailure(status,"{}"))
            try{readSubtitleSettings(expected){throw denied};fail()}catch(actual:ApiError){assertSame(denied,actual)}
        }
        cancelled{readSubtitleSettings(expected){throw CancellationException("Stopped")}}
    }
    @Test fun `late settings under replacement epoch cannot return or publish even if callback masks cancellation`()=runTest {
        val expected=ProfileDataContext.current;val release=CompletableDeferred<Unit>();var published=0
        val job=async{readSubtitleSettings(expected){withContext(NonCancellable){release.await()};SubtitleServerSettings(true,true)};published++}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.complete(Unit);cancelled{job.await()};assertEquals(0,published)
    }
    @Test fun `masked same epoch cancellation cannot return enabled settings`()=runTest {
        val release=CompletableDeferred<Unit>();var published=0;val expected=ProfileDataContext.current
        val job=async{readSubtitleSettings(expected){withContext(NonCancellable){release.await()};SubtitleServerSettings(true,true)};published++}
        runCurrent();job.cancel();release.complete(Unit);cancelled{job.await()};assertEquals(0,published)
    }
    @Test fun `actual settings page helper clears terminal playback failure without reading more settings`()=runTest {
        var subtitles=0;val published=mutableListOf<ServerTrackDefaults>()
        loadOwnedTrackDefaults(ProfileDataContext.current,{throw ApiError.Http(HttpFailure(403,"{}"))},{subtitles++;SubtitleServerSettings(true,true)},{published+=it})
        assertEquals(0,subtitles);assertEquals(listOf(ServerTrackDefaults(null,false)),published)
    }
    @Test fun `terminal subtitle settings clear earlier playback defaults safely`()=runTest {
        val published=mutableListOf<ServerTrackDefaults>()
        loadOwnedTrackDefaults(ProfileDataContext.current,{PlaybackSettings(preferredAudioLanguage="fra")},{throw ApiError.Http(HttpFailure(403,"{}"))},{published+=it})
        assertEquals(listOf(ServerTrackDefaults(null,false)),published)
    }
    @Test fun `ordinary optional failure preserves successful defaults and does not infer translation`()=runTest {
        val playback=PlaybackSettings(preferredAudioLanguage="fra");val published=mutableListOf<ServerTrackDefaults>()
        loadOwnedTrackDefaults(ProfileDataContext.current,{playback},{throw IOException("Unavailable")},{published+=it})
        assertEquals(listOf(ServerTrackDefaults(playback,false)),published)
    }
    @Test fun `old settings page cannot publish or read second stage after epoch change`()=runTest {
        val expected=ProfileDataContext.current;val release=CompletableDeferred<Unit>();var subtitles=0;var published=0
        val job=async{loadOwnedTrackDefaults(expected,{withContext(NonCancellable){release.await()};PlaybackSettings()},{subtitles++;SubtitleServerSettings(true,true)},{published++})}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.complete(Unit);cancelled{job.await()};assertEquals(0,subtitles);assertEquals(0,published)
    }
}
