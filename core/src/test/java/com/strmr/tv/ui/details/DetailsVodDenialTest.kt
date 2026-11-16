package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsVodDenialTest {
    private val movie=DetailsTarget("tmdb:movie:41","Synthetic",true)
    private fun denied()=ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied","error":"synthetic-private-path"}"""))
    private fun bundle()=DetailsBundle(movieDetails=Title(movie.titleId,"Synthetic",mediaType="movie"))
    private fun TestScope.model(retired:()->Unit={})=DetailsModel(movie,backgroundScope,retireWarm={_,_->retired()},lookupLibrary={_,_->emptyList()})
    @Test fun `a movie is not playable before its first metadata reply and initial denial stays terminal`() = runTest {
        val model=model();assertNull(model.primary);assertFalse(model.playable)
        model.load {throw denied()};assertNull(model.primary);assertNull(model.title);assertEquals(VodAccessDenial.BLOCKED,model.vodDenial)
        assertFalse(model.loadError!!.contains("private"))
    }
    @Test fun `metadata reload denial retires old playable state and all old action generations`() = runTest {
        var retired=0;val model=model {retired++};model.load {bundle()};val generation=model.accessGeneration
        assertNotNull(model.primary);assertTrue(model.acceptsPlaybackAction(generation))
        model.load {throw denied()}
        assertNull(model.title);assertNull(model.primary);assertNull(model.trailers);assertTrue(model.seasons.isEmpty());assertNull(model.playChoice)
        assertFalse(model.acceptsPlaybackAction(generation));assertEquals(1,retired)
        model.play(null,null);model.streamInstead(DetailsModel.PlayChoice(null,null,null));runCurrent();assertEquals(1,retired)
    }
    @Test fun `ordinary transport error retains existing metadata without converting it into a denial`() = runTest {
        val model=model();model.load {bundle()};val previous=model.title
        model.load {throw java.io.IOException("synthetic")};assertSame(previous,model.title);assertNull(model.vodDenial);assertTrue(model.playable)
    }
    @Test fun `a late earlier metadata success cannot restore a title after a newer denial`() = runTest {
        val model=model();val gate=CompletableDeferred<Unit>()
        val old=backgroundScope.launch {model.load {withContext(NonCancellable){gate.await()};bundle()}}
        runCurrent();model.load {throw denied()};gate.complete(Unit);runCurrent()
        assertTrue(old.isCancelled);assertNull(model.title);assertEquals(VodAccessDenial.BLOCKED,model.vodDenial)
    }
    @Test fun `strict reminder recovery propagates denial and retires the existing title`() = runTest {
        val model=model();model.reloadForReminder {bundle()};var caught=false
        try {model.reloadForReminder {throw denied()}} catch(_:ApiError.Http) {caught=true}
        assertTrue(caught);assertNull(model.primary);assertEquals(VodAccessDenial.BLOCKED,model.vodDenial)
    }
    @Test fun `a fresh authorized read permits new actions without reviving old callbacks`() = runTest {
        val model=model();model.load {bundle()};val old=model.accessGeneration;model.load {throw denied()}
        model.load {bundle()};runCurrent();assertNull(model.vodDenial);assertTrue(model.playable);assertFalse(model.acceptsPlaybackAction(old));assertTrue(model.acceptsPlaybackAction(model.accessGeneration))
    }
    @Test fun `a late denied reply cannot retire replacement profile work`() = runTest {
        var retired=0;val model=model {retired++};val gate=CompletableDeferred<Unit>()
        val job=backgroundScope.launch {model.load {withContext(NonCancellable){gate.await()};throw denied()}}
        runCurrent();ProfileDataContext.invalidate();ProfileDataContext.invalidate();gate.complete(Unit);runCurrent()
        assertEquals(0,retired);assertNull(model.vodDenial);assertFalse(model.playable)
    }
}
