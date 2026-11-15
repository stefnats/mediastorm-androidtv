package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.strmr.tv.ui.reminders.ReminderTarget
import com.strmr.tv.ui.reminders.reminderChoices
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderDetailsReloadTest {
    private val target=DetailsTarget("tmdb:series:41","Synthetic",false)
    private fun bundle(number: Int)=DetailsBundle(seriesDetails=SeriesDetails(Title(target.titleId,"Synthetic",mediaType="series"),listOf(Season("season",number=2,episodes=listOf(Episode("episode-$number",seasonNumber=2,episodeNumber=number,numbering=EpisodeNumbering("tvdb:series:74852","official")))))))

    @Test fun `strict reminder reload propagates transport failure despite an existing title`()=runTest {
        val model=DetailsModel(target,backgroundScope)
        model.reloadForReminder {bundle(3)}
        val previous=model.title
        var failed=false
        try {model.reloadForReminder {throw IOException("synthetic-private-detail")}} catch(_: IOException) {failed=true}
        assertTrue(failed)
        assertEquals(previous,model.title)
        assertEquals("episode-3",model.primaryEpisode!!.id)
    }
    @Test fun `strict reminder reload rejects an empty successful response rather than confirming stale metadata`()=runTest {
        val model=DetailsModel(target,backgroundScope)
        model.reloadForReminder {bundle(3)}
        var failed=false
        try {model.reloadForReminder {DetailsBundle()}} catch(_: ApiError.NotReady) {failed=true}
        assertTrue(failed)
        assertEquals("episode-3",model.primaryEpisode!!.id)
    }
    @Test fun `strict reminder reload applies fresh exact episode tuples under its captured context`()=runTest {
        val model=DetailsModel(target,backgroundScope)
        model.reloadForReminder {bundle(3)}
        var captured: Long?=null
        model.reloadForReminder {expected->captured=expected;bundle(4)}
        assertEquals(model.dataContext,captured)
        assertEquals("episode-4",model.currentSeason!!.episodes.single().id)
        assertEquals(4,model.primaryEpisode!!.episodeNumber)
        assertEquals("tvdb:series:74852",model.primaryEpisode!!.numbering!!.seriesId)
        assertEquals("episode-4",reminderChoices(ReminderTarget(model.target,model.primaryEpisode)).last().episode!!.episodeId)
    }
    @Test fun `strict reminder reload cannot apply a late response after profile or server replacement`()=runTest {
        val model=DetailsModel(target,backgroundScope)
        model.reloadForReminder {bundle(3)}
        var cancelled=false
        try {model.reloadForReminder {ProfileDataContext.invalidate();bundle(99)}} catch(_: CancellationException) {cancelled=true}
        assertTrue(cancelled)
        assertEquals("episode-3",model.primaryEpisode!!.id)
        var requested=false
        try {model.reloadForReminder {requested=true;bundle(99)}} catch(_: CancellationException) {}
        assertFalse(requested)
    }
    @Test fun `strict reminder reload does not swallow cancellation with an already loaded title`()=runTest {
        val model=DetailsModel(target,backgroundScope)
        model.reloadForReminder {bundle(3)}
        var cancelled=false
        try {model.reloadForReminder {throw CancellationException("synthetic page closed")}} catch(_: CancellationException) {cancelled=true}
        assertTrue(cancelled)
        assertEquals("episode-3",model.primaryEpisode!!.id)
    }
    @Test fun `a cancelled reminder page cannot apply metadata from a cancellation masked transport`()=runTest {
        val model=DetailsModel(target,backgroundScope)
        model.reloadForReminder {bundle(3)}
        val reply=CompletableDeferred<DetailsBundle>()
        val job=backgroundScope.launch {model.reloadForReminder {withContext(NonCancellable) {reply.await()}}}
        runCurrent();job.cancel();reply.complete(bundle(99));runCurrent()
        assertTrue(job.isCancelled)
        assertEquals("episode-3",model.primaryEpisode!!.id)
    }
}
