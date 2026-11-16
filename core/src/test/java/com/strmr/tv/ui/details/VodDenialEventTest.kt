package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.sources.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodDenialEventTest {
    private val title=DetailsTarget("tmdb:movie:41","Synthetic",true)
    private fun bundle(target:DetailsTarget=title)=DetailsBundle(movieDetails=Title(target.titleId,target.name,mediaType="movie"))
    private fun TestScope.model(target:DetailsTarget=title,scope:CoroutineScope=backgroundScope)=DetailsModel(target,scope,retireWarm={_,_->},lookupLibrary={_,_->emptyList()})
    private fun TestScope.sources(gate:CompletableDeferred<Unit>?=null):SourceSearchState {
        val transport=object:SourceSearchTransport {
            override suspend fun search(title:DetailsTarget,episode:Episode?,context:Long):SourceSearchReply {
                gate?.let {withContext(NonCancellable){it.await()}}
                throw ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied"}"""))
            }
            override suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long):SourceSearchPage=error("No fallback")
            override suspend fun health(token:String,context:Long):SourceHealthSnapshot=error("No probe")
        }
        return SourceSearchState(backgroundScope,title,null,ProfileDataContext.current,transport,clientIdentity={"synthetic"})
    }
    @Test fun `picker denial retires the underlying title so closing it cannot revive old Play`()=runTest {
        val model=model();model.load {bundle()};val old=model.accessGeneration
        val sources=sources();sources.reload();runCurrent();sources.dispose()
        assertEquals(VodAccessDenial.BLOCKED,model.vodDenial);assertFalse(model.acceptsPlaybackAction(old));assertNull(model.primary)
        model.play(null,null);model.streamInstead(DetailsModel.PlayChoice(null,null,null));runCurrent();assertNull(model.primary)
    }
    @Test fun `observed current player denial affects only its already open exact title and epoch`()=runTest {
        val matching=model();val otherTarget=title.copy(titleId="tmdb:movie:99");val other=model(otherTarget)
        matching.load {bundle()};other.load {bundle(otherTarget)}
        val attempt=VodDenialEvents.begin(title.titleId,ProfileDataContext.current)
        VodDenialEvents.reject(attempt,VodAccessDenial.BLOCKED);runCurrent()
        assertEquals(VodAccessDenial.BLOCKED,matching.vodDenial);assertNull(other.vodDenial);assertTrue(other.playable)
        val reopened=model();reopened.load {bundle()};runCurrent();assertTrue(reopened.playable);assertNull(reopened.vodDenial)
        VodDenialEvents.reject(attempt,VodAccessDenial.BLOCKED);runCurrent();assertNull(reopened.vodDenial)
    }
    @Test fun `late older picker denial cannot retire a newer successful metadata authorization`()=runTest {
        val model=model();model.load {bundle()};val gate=CompletableDeferred<Unit>();val sources=sources(gate)
        sources.reload();runCurrent();model.reloadForReminder {bundle()};gate.complete(Unit);runCurrent()
        assertEquals(VodAccessDenial.BLOCKED,sources.vodDenied);assertNull(model.vodDenial);assertTrue(model.playable);sources.dispose()
    }
    @Test fun `unmounted pages and replacement profile epochs do not consume denial events`()=runTest {
        val owned=CoroutineScope(backgroundScope.coroutineContext+Job(backgroundScope.coroutineContext[Job]))
        val unmounted=model(scope=owned);unmounted.load {bundle()};owned.cancel()
        val old=VodDenialEvents.begin(title.titleId,ProfileDataContext.current);VodDenialEvents.reject(old,VodAccessDenial.BLOCKED);runCurrent()
        assertNull(unmounted.vodDenial);assertFalse(unmounted.playable)
        ProfileDataContext.invalidate();val current=model();current.load {bundle()}
        VodDenialEvents.reject(old,VodAccessDenial.BLOCKED);runCurrent();assertNull(current.vodDenial);assertTrue(current.playable)
    }
    @Test fun `an older metadata read finishing later cannot hide a newer operation denial`()=runTest {
        val model=model();model.load {bundle()};val gate=CompletableDeferred<Unit>()
        val read=backgroundScope.launch {model.load {gate.await();bundle()}}
        runCurrent();val newer=VodDenialEvents.begin(title.titleId,ProfileDataContext.current)
        gate.complete(Unit);runCurrent();assertTrue(model.playable)
        VodDenialEvents.reject(newer,VodAccessDenial.BLOCKED);runCurrent();assertEquals(VodAccessDenial.BLOCKED,model.vodDenial)
        read.join()
    }

}
