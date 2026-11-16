package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Real production adapter; fake stages/side effects, no Android PlayerController or profile picker. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleAccessOwnershipTest {
    private val row=SubtitleSearchResult("synthetic-row","synthetic-provider",language="fra",release="Synthetic.Release")
    private fun access(denied:(VodAccessDenial)->Unit={})=SubtitlePlaybackAccess(PlaybackSessionContext.capture(),"tmdb:movie:41",onDenied=denied)
    private suspend fun cancelled(action:suspend()->Unit){try{action();fail("Retired subtitle action accepted")}catch(_:CancellationException){}}
    @Test fun `all successful stages retain original epoch before result publication`()=runTest {
        val expected=ProfileDataContext.current;val access=access();val run=access.begin()!!;val contexts=mutableListOf<Long>();var published=0
        for(stage in listOf("settings","search","download","extract","translate","refresh")) {
            assertEquals(stage,access.request(run){contexts+=it;stage});access.use(run){published++}
        }
        assertTrue(contexts.all{it==expected});assertEquals(6,published)
    }
    @Test fun `queued operation after simulated A B A never calls its request`()=runTest {
        val access=access();val run=access.begin()!!;var calls=0
        val job=async{access.request(run){calls++;"old"}}
        ProfileDataContext.invalidate();ProfileDataContext.invalidate();runCurrent();cancelled{job.await()};assertEquals(0,calls)
    }
    @Test fun `late network and parsing replies cannot publish after owner epoch changes`()=runTest {
        for(network in listOf(true,false)) {
            val access=access();val run=access.begin()!!;val release=CompletableDeferred<Unit>();var published=0
            val job=async{
                if(network)access.request(run){withContext(NonCancellable){release.await()};"old"}
                else access.guarded(run){withContext(NonCancellable){release.await()};"parsed"}
                access.use(run){published++}
            }
            runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();release.complete(Unit);cancelled{job.await()};assertEquals(0,published)
        }
    }
    @Test fun `closed player with unchanged profile rejects old UI requests and cached values`()=runTest {
        var active=true;val access=SubtitlePlaybackAccess(PlaybackSessionContext.capture(),"tmdb:movie:41",{active});val run=access.begin()!!;var calls=0
        active=false
        cancelled{access.request(run){calls++;"old"}};cancelled{access.guarded{calls++;"cached"}}
        assertEquals(0,calls);assertNull(access.begin())
    }
    @Test fun `closing instance rejects pending results and cannot reopen same epoch`()=runTest {
        val access=access();val run=access.begin()!!;val release=CompletableDeferred<Unit>();var published=0
        val job=async{access.request(run){withContext(NonCancellable){release.await()};"old"};published++}
        runCurrent();access.close();release.complete(Unit);cancelled{job.await()};assertEquals(0,published);assertNull(access.begin())
    }
    @Test fun `new intent retires older automatic translation or download even when cancellation is masked`()=runTest {
        val access=access();val old=access.begin()!!;val release=CompletableDeferred<Unit>();var oldPublished=0
        val job=async{access.request(old){withContext(NonCancellable){release.await()};"old"};access.use(old){oldPublished++}}
        runCurrent();val fresh=access.begin()!!;assertEquals("fresh",access.request(fresh){"fresh"});release.complete(Unit)
        cancelled{job.await()};assertEquals(0,oldPublished);assertTrue(access.current(fresh))
    }
    @Test fun `late old denial cannot retire fresh intent or publish to denial handler`()=runTest {
        val denials=mutableListOf<VodAccessDenial>();val access=access{denials+=it};val old=access.begin()!!;val release=CompletableDeferred<Unit>()
        val job=async{access.request(old){withContext(NonCancellable){release.await()};throw ApiError.Http(HttpFailure(403,"{}"))}}
        runCurrent();val fresh=access.begin()!!;release.complete(Unit);cancelled{job.await()}
        assertTrue(denials.isEmpty());assertEquals("new",access.request(fresh){"new"})
    }
    @Test fun `terminal responses close all later requests and English selection actions with safe UI cancellation`()=runTest {
        for(code in listOf(null,"kids_vod_profile_required","kids_vod_access_denied","kids_vod_classification_unknown","kids_vod_parent_approval_required","kids_vod_approval_expired","kids_vod_context_changed","kids_vod_unsupported_source")) {
            val denials=mutableListOf<VodAccessDenial>();val access=access{denials+=it};val run=access.begin()!!;var requests=0;var english=0
            val error=ApiError.Http(HttpFailure(if(code=="kids_vod_profile_required")400 else 403,if(code==null)"{}"else"{\"code\":\"$code\"}"))
            try{access.request(run){requests++;throw error};fail()}catch(cancelled:CancellationException){assertEquals("Subtitle access denied",cancelled.message);assertSame(error,cancelled.cause)}
            for(stage in listOf("search","extract","translate","download"))cancelled{access.request(run){requests++;stage}}
            cancelled{access.use(run){english++}}
            assertEquals(1,requests);assertEquals(0,english);assertEquals(1,denials.size);assertNull(access.begin())
        }
    }
    @Test fun `ordinary provider error leaves normal next stage or explicit fallback available`()=runTest {
        val access=access();val run=access.begin()!!;var fallback=0
        try{access.request(run){throw IOException("Synthetic unavailable")};fail()}catch(_:IOException){}
        assertTrue(access.current(run));access.use(run){fallback++};assertEquals("next",access.request(run){"next"});assertEquals(1,fallback)
    }
    @Test fun `cancellation stays cancellation and never becomes a default or denial`()=runTest {
        val denials=mutableListOf<VodAccessDenial>();val access=access{denials+=it};val run=access.begin()!!
        cancelled{access.request(run){throw CancellationException("Stopped")}};assertTrue(denials.isEmpty())
        val release=CompletableDeferred<Unit>();var published=0
        val job=async{access.request(run){withContext(NonCancellable){release.await()};"late"};published++}
        runCurrent();job.cancel();release.complete(Unit);cancelled{job.await()};assertEquals(0,published)
    }
    @Test fun `manual intent Off and close actually cancel an in flight refresh and its transport hook`()=runTest {
        for(action in listOf("manual","off","close")) {
            val access=access();val run=access.begin()!!;var arrived=false;var cancelled=0;var published=0
            val job=access.launchRefresh(backgroundScope,run) {
                access.request(run) { suspendCancellableCoroutine<Unit> { continuation ->
                    arrived=true
                    continuation.invokeOnCancellation {cancelled++}
                } }
                access.use(run){published++}
            }
            runCurrent();assertTrue(arrived)
            when(action){"manual"->access.begin();"off"->access.invalidate();else->access.close()}
            runCurrent();assertTrue(job.isCancelled);assertEquals(1,cancelled);assertEquals(0,published)
        }
    }
    @Test fun `masked refresh reply cannot update after its registered job was canceled by new intent`()=runTest {
        val access=access();val run=access.begin()!!;val release=CompletableDeferred<Unit>();var published=0
        val job=access.launchRefresh(backgroundScope,run) {access.request(run){withContext(NonCancellable){release.await()};"old"};published++}
        runCurrent();val fresh=access.begin()!!;assertTrue(job.isCancelled);release.complete(Unit);runCurrent()
        assertEquals(0,published);assertTrue(access.current(fresh))
    }
    @Test fun `actual sorted result instances bind one language and copies never rebind`() {
        val access=access();val run=access.begin()!!;val sorted=SubtitleMatching.sortForDisplay(listOf(row),"Synthetic.Release")
        assertSame(row,sorted.single());access.rememberSearch(run,"fra",sorted)
        assertNull(access.chooseSearch(row.copy(),"fra"));assertNull(access.chooseSearch(row,"spa"))
        val choice=access.chooseSearch(row,"fra");assertNotNull(choice);assertFalse(access.current(run));assertNull(access.chooseSearch(row,"fra"))
    }
    @Test fun `identical row in later search cannot revive an old object or retired ledger`() {
        val access=access();val first=access.begin()!!;access.rememberSearch(first,"fra",listOf(row))
        val fresh=access.begin()!!;val newer=row.copy();access.rememberSearch(fresh,"fra",listOf(newer))
        assertNull(access.chooseSearch(row,"fra"));assertNotNull(access.chooseSearch(newer,"fra"))
        val last=access.begin()!!;access.rememberSearch(last,"fra",listOf(row));access.invalidate();assertNull(access.chooseSearch(row,"fra"))
    }
    @Test fun `refresh requires exact shown object not same labels or equal cues`() {
        val access=access();val run=access.begin()!!;val old=ExternalTrack("Translated French","From English",emptyList());var shown=old.copy();var changes=0
        assertNull(access.updateExpected(run,old,{shown}){changes++;shown});assertEquals(0,changes)
        shown=old
        assertSame(old,access.updateExpected(run,old,{shown}){changes++;shown});assertEquals(1,changes)
        access.begin();try{access.updateExpected(run,old,{shown}){changes++;shown};fail()}catch(_:CancellationException){};assertEquals(1,changes)
    }
}
