package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Epoch invalidations simulate profile retirement; no Android profile picker or decoder. */
@OptIn(ExperimentalCoroutinesApi::class)
class SkipSegmentsOwnershipTest {
    private val request=PlaybackRequest(DetailsTarget("tmdb:series:41","Synthetic",false,imdbId="tt0000041"),PlaybackRequest.EpisodeInfo(1,2))
    private val intro=SkipSegment("intro",0.0,90.0)
    private val outro=SkipSegment("outro",900.0,1000.0)
    private fun denied(status:Int=403)=ApiError.Http(HttpFailure(status,"{\"code\":\"kids_vod_access_denied\"}"))
    private inner class Fixture(val scope:CoroutineScope,val item:PlaybackRequest=request) {
        val expected=ProfileDataContext.current
        val bodies=mutableListOf<JsonObject>();val paths=mutableListOf<String>();val contexts=mutableListOf<Long>();val denials=mutableListOf<VodAccessDenial>()
        var segment:suspend (JsonObject,Long)->List<SkipSegment> = {_,_->emptyList()}
        var chapters:suspend (String,Long)->List<VideoChapter> = {_,_->listOf(VideoChapter("End Credits",900.0,1000.0))}
        val value=SkipSegments(item,"synthetic-owned-file",expected,{denials+=it},
            {body,context->bodies+=body;contexts+=context;segment(body,context)},
            {path,context->paths+=path;contexts+=context;chapters(path,context)},log={})
        fun refresh(duration:Long=1_000_000)=value.refresh(scope,duration)
    }
    @Test fun `ordinary episode keeps existing segments chapters repost sequence and original context`()=runTest {
        val f=Fixture(backgroundScope)
        f.segment={body,_->if(body["chapters"]!!.jsonArray.isEmpty())emptyList()else listOf(outro)}
        f.refresh();runCurrent()
        assertEquals(2,f.bodies.size);assertEquals(listOf("synthetic-owned-file"),f.paths);assertTrue(f.contexts.all{it==f.expected})
        assertEquals(1000.0,f.bodies.last()["duration"]!!.jsonPrimitive.double,0.0)
        assertEquals("tt0000041",f.bodies.last()["imdbId"]!!.jsonPrimitive.content);assertFalse(f.bodies.last().containsKey("profileId"))
        assertEquals(outro,f.value.active(950_000));assertTrue(f.value.hasOutro)
    }
    @Test fun `existing outro movie and empty chapters do not add new requests`()=runTest {
        val known=Fixture(backgroundScope);known.segment={_,_->listOf(outro)};known.refresh();runCurrent();assertTrue(known.paths.isEmpty());assertEquals(1,known.bodies.size)
        val movie=Fixture(backgroundScope,PlaybackRequest(DetailsTarget("tmdb:movie:41","Synthetic",true)));movie.refresh();runCurrent();assertTrue(movie.paths.isEmpty());assertEquals(1,movie.bodies.size)
        val empty=Fixture(backgroundScope);empty.chapters={_,_->emptyList()};empty.refresh();runCurrent();assertEquals(1,empty.bodies.size);assertEquals(1,empty.paths.size)
    }
    @Test fun `ordinary initial failure retains chapter fallback without changing request count`()=runTest {
        val f=Fixture(backgroundScope);var calls=0
        f.segment={_,_->if(++calls==1)throw IOException("Synthetic unavailable")else listOf(outro)}
        f.refresh();runCurrent();assertEquals(2,f.bodies.size);assertEquals(1,f.paths.size);assertEquals(outro,f.value.active(950_000));assertTrue(f.denials.isEmpty())
    }
    @Test fun `terminal denial at each stage stops later demand clears ranges and retires refresh`()=runTest {
        for(stage in 1..3) {
            val f=Fixture(backgroundScope);var reads=0
            f.segment={_,_->reads++;if(reads==1 && stage==1 || reads==2 && stage==3)throw denied();listOf(intro)}
            f.chapters={_,_->if(stage==2)throw denied();listOf(VideoChapter("End Credits",900.0,1000.0))}
            f.refresh();runCurrent()
            assertEquals(if(stage==3)2 else 1,f.bodies.size);assertEquals(if(stage==1)0 else 1,f.paths.size)
            assertEquals(listOf(VodAccessDenial.BLOCKED),f.denials);assertNull(f.value.active(30_000));assertFalse(f.value.hasOutro)
            f.refresh(1_100_000);f.value.detectedCredits(900.0,1100.0);runCurrent()
            assertEquals(if(stage==3)2 else 1,f.bodies.size);assertNull(f.value.active(950_000))
        }
    }
    @Test fun `generic own backend unauthorized and forbidden responses are terminal too`()=runTest {
        for(status in listOf(401,403,400)) {
            val f=Fixture(backgroundScope)
            f.segment={_,_->throw ApiError.Http(HttpFailure(status,if(status==400)"{\"code\":\"kids_vod_profile_required\"}"else"{}"))}
            f.refresh();runCurrent();assertEquals(1,f.denials.size);assertTrue(f.paths.isEmpty());assertEquals(1,f.bodies.size)
        }
    }
    @Test fun `cancellation is never treated as permission for chapter fallback`()=runTest {
        val f=Fixture(backgroundScope);f.segment={_,_->throw CancellationException("Synthetic cancellation")}
        f.refresh();runCurrent();assertEquals(1,f.bodies.size);assertTrue(f.paths.isEmpty());assertTrue(f.denials.isEmpty())
    }
    @Test fun `queued old refresh performs zero demand after simulated A B A`()=runTest {
        val f=Fixture(backgroundScope);f.refresh();ProfileDataContext.invalidate();ProfileDataContext.invalidate();runCurrent()
        assertTrue(f.bodies.isEmpty());assertTrue(f.paths.isEmpty());assertNull(f.value.active(30_000))
    }
    @Test fun `late first reply cannot publish or start chapters after context retirement`()=runTest {
        val f=Fixture(backgroundScope);val release=CompletableDeferred<Unit>()
        f.segment={_,_->withContext(NonCancellable){release.await()};listOf(intro)}
        f.refresh();runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.complete(Unit);runCurrent()
        assertTrue(f.paths.isEmpty());assertNull(f.value.active(30_000));assertTrue(f.denials.isEmpty())
    }
    @Test fun `late chapter reply cannot repost after original owner retires`()=runTest {
        val f=Fixture(backgroundScope);val release=CompletableDeferred<Unit>()
        f.chapters={_,_->withContext(NonCancellable){release.await()};listOf(VideoChapter("End Credits",900.0,1000.0))}
        f.refresh();runCurrent();ProfileDataContext.invalidate();release.complete(Unit);runCurrent()
        assertEquals(1,f.bodies.size);assertEquals(1,f.paths.size);assertNull(f.value.active(30_000))
    }
    @Test fun `duration replacement rejects all late previous request stages including denial`()=runTest {
        for(stage in 1..3) {
            val f=Fixture(backgroundScope);val release=CompletableDeferred<Unit>();var firstPosts=0
            f.segment={body,_->
                if(body["duration"]!!.jsonPrimitive.double==1100.0)listOf(outro)
                else {firstPosts++;if(stage==1 && firstPosts==1 || stage==3 && firstPosts==2){withContext(NonCancellable){release.await()};throw denied()};listOf(intro)}
            }
            f.chapters={_,_->if(stage==2)withContext(NonCancellable){release.await()};listOf(VideoChapter("End Credits",900.0,1000.0))}
            f.refresh();runCurrent();f.refresh(1_100_000);runCurrent();assertEquals(outro,f.value.active(950_000))
            release.complete(Unit);runCurrent();assertEquals(outro,f.value.active(950_000));assertTrue(f.denials.isEmpty())
            assertEquals(if(stage==3)3 else 2,f.bodies.size)
        }
    }
    @Test fun `cancel retires late response manual detected credits and later refresh`()=runTest {
        val f=Fixture(backgroundScope);val release=CompletableDeferred<Unit>()
        f.segment={_,_->withContext(NonCancellable){release.await()};listOf(outro)}
        f.refresh();runCurrent();f.value.cancel();release.complete(Unit);runCurrent()
        f.refresh(1_100_000);f.value.detectedCredits(800.0,1100.0);runCurrent()
        assertEquals(1,f.bodies.size);assertTrue(f.paths.isEmpty());assertNull(f.value.active(950_000));assertFalse(f.value.hasOutro)
    }
    @Test fun `same duration ticks remain idempotent`()=runTest {
        val f=Fixture(backgroundScope);f.segment={_,_->listOf(outro)}
        f.refresh();f.refresh();runCurrent();f.refresh();runCurrent();assertEquals(1,f.bodies.size)
    }
    @Test fun `Up Next explicit old player context prevents settings demand even when constructed after switch`()=runTest {
        val expected=ProfileDataContext.current;ProfileDataContext.invalidate();ProfileDataContext.invalidate();var reads=0;var advances=0
        val next=UpNext(request,null,backgroundScope,{null},readSettings={reads++;PlaybackSettings()},creditsEnabled={false},prepareNext={},log={},expectedContext=expected){advances++}
        runCurrent();next.prefetchIfNear(990_000,1_000_000);assertEquals(0,reads);assertEquals(0,advances);next.cancel()
    }
}
