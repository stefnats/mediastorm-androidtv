package com.strmr.tv.ui.details

import com.strmr.tv.api.CardEpisode
import com.strmr.tv.api.ContentArtworkPreference
import com.strmr.tv.api.ImageRef
import com.strmr.tv.api.Title
import com.strmr.tv.home.HomeCard
import org.junit.Assert.*
import org.junit.Test

class PersonalArtworkStoreTest {
    private val title = Title("tmdb:movie:1", "Synthetic", poster = ImageRef("textless-poster"), textPoster = ImageRef("text-poster"),
        backdrop = ImageRef("provider-background"))
    private val chosen = ContentArtworkPreference(title.id, true, "personal-poster", "personal-background")

    @Test fun cardAndDetailChoicesDoNotChangeOriginalProviderImages() {
        val store = PersonalArtworkStore { 1 }
        store.activate(1); store.replace(1, listOf(chosen))
        val original = HomeCard.from(title)
        val displayed = store.card(original)
        assertEquals("personal-poster", displayed.posterUrl)
        assertEquals("personal-background", displayed.artworkUrl)
        assertEquals("personal-background", displayed.backdropUrl)
        assertEquals("personal-background", store.backdrop(title.id, title.backdrop?.url))
        assertEquals("text-poster", original.posterUrl)
        assertEquals("provider-background", original.backdropUrl)
        assertEquals("textless-poster", title.poster!!.url)
        assertEquals("text-poster", title.textPoster!!.url)
    }

    @Test fun defaultRestoresTextPosterAndBackgroundWhileEpisodeStillStaysSpecific() {
        val store = PersonalArtworkStore { 1 }
        store.activate(1); store.replace(1, listOf(chosen))
        val episodeCard = HomeCard.from(title).copy(episode = CardEpisode(1, 2, "Episode", null), artworkUrl = "episode-still")
        assertEquals("episode-still", store.card(episodeCard).artworkUrl)
        assertEquals("personal-background", store.card(episodeCard).backdropUrl)
        store.saved(1, title.id, chosen.copy(posterUrl = null, backdropUrl = null))
        val standard = store.card(HomeCard.from(title))
        assertEquals("text-poster", standard.posterUrl)
        assertEquals("provider-background", standard.backdropUrl)
        assertEquals("provider-background", standard.artworkUrl)
        assertEquals("text-poster", store.poster(title.id, title.textPoster?.url))
    }

    @Test fun profileChangeImmediatelyHidesOldChoicesAndRejectsLateReads() {
        var current = 1L
        val store = PersonalArtworkStore { current }
        store.activate(1); store.replace(1, listOf(chosen))
        current = 2
        assertEquals("text-poster", store.card(HomeCard.from(title)).posterUrl)
        store.activate(2)
        store.replace(1, listOf(chosen))
        store.saved(1, title.id, chosen)
        assertEquals("text-poster", store.card(HomeCard.from(title)).posterUrl)
        store.replace(2, listOf(chosen.copy(posterUrl = "second-profile")))
        assertEquals("second-profile", store.card(HomeCard.from(title)).posterUrl)
    }

    @Test fun listReadStartedBeforeSaveCannotUndoTheConfirmedSelection() {
        val store = PersonalArtworkStore { 1 }
        store.activate(1)
        val startedRevision = store.revision
        store.saved(1, title.id, chosen)
        store.replace(1, emptyList(), startedRevision)
        assertEquals("personal-poster", store.card(HomeCard.from(title)).posterUrl)
        store.replace(1, emptyList())
        assertEquals("text-poster", store.card(HomeCard.from(title)).posterUrl)
    }
}
