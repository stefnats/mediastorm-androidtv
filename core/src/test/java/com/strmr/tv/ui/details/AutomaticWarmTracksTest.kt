package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.WarmPrequeue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AutomaticWarmTracksTest {
    private val title = DetailsTarget("tmdb:series:41", "Synthetic", false)
    private val episode = Episode("tvdb:episode:123", seasonNumber = 1, episodeNumber = 2,
        numbering = EpisodeNumbering("tvdb:series:74852", "official"))
    private suspend fun model(test: kotlinx.coroutines.test.TestScope): DetailsModel {
        val model = DetailsModel(title, test.backgroundScope)
        model.reloadForReminder { DetailsBundle(seriesDetails = SeriesDetails(Title(title.titleId, title.name, mediaType = "series"),
            listOf(Season("season", number = 1, episodes = listOf(episode))))) }
        return model
    }
    private fun status() = PrequeueStatus("synthetic", "ready", streamPath = "plexmedia:synthetic",
        automaticLibrarySelection = true, targetEpisode = PrequeueStatus.TargetEpisode(1,2,episode.id,episode.numbering),
        audioTracks = listOf(ProbedAudioTrack(1,"fra")))
    private fun warm(reply: PrequeueStatus = status()) = WarmPrequeue(title.titleId, ApiClient.profileId,
        WarmPrequeue.Phase.READY, reply, automaticLibrarySelection = true)

    @Test fun `actual details tracks hide stale leaf owner and alternate ordering despite equal coordinates`() = runTest {
        val tracks = PrequeueTracks(model(this))
        assertNotNull(tracks.visible(warm()))
        val target = status().targetEpisode!!
        for (different in listOf(target.copy(episodeId = "tvdb:episode:999"), target.copy(numbering = EpisodeNumbering("tvdb:series:999","official")),
            target.copy(numbering = EpisodeNumbering("tvdb:series:74852","dvd")), target.copy(numbering = null))) {
            assertNull(tracks.visible(warm(status().copy(targetEpisode = different))))
        }
    }
    @Test fun `known official default aliases match without inferring an alternate owner`() = runTest {
        val tracks = PrequeueTracks(model(this))
        val target = status().targetEpisode!!
        assertNotNull(tracks.visible(warm(status().copy(targetEpisode = target.copy(numbering = EpisodeNumbering("tvdb:series:74852","default"))))))
    }
    @Test fun `warm preview is never reused across server or profile generations with equal IDs`() = runTest {
        val model = model(this); val tracks = PrequeueTracks(model); val previous = warm()
        assertNotNull(tracks.visible(previous))
        ProfileDataContext.invalidate()
        assertNull(tracks.visible(previous))
        val nextModel = model(this)
        assertNull(PrequeueTracks(nextModel).visible(previous))
        assertNotNull(PrequeueTracks(nextModel).visible(warm()))
    }
}
