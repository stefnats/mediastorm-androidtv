package com.strmr.tv.ui.details

import com.strmr.tv.api.CardEpisode
import com.strmr.tv.api.ArtworkKind
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

    @Test fun confirmedStandardReplacesCachedLearnedImagesAndNewManualChoiceClearsOnlyItsKind() {
        val store = PersonalArtworkStore { 1 }
        store.activate(1)
        val cached = HomeCard.from(title.copy(poster = ImageRef("learned-poster"), textPoster = ImageRef("learned-text"),
            backdrop = ImageRef("learned-background")))
        store.saved(1, title.id, chosen.copy(posterUrl = null, backdropUrl = null))
        store.restoredStandard(1, title.id, ArtworkKind.POSTER, "original-poster")
        store.restoredStandard(1, title.id, ArtworkKind.BACKDROP, "original-background")
        store.replace(1, listOf(chosen.copy(posterUrl = null, backdropUrl = null)))
        assertEquals("original-poster", store.card(cached).posterUrl)
        assertEquals("original-background", store.card(cached).backdropUrl)
        assertEquals("original-background", store.card(cached).artworkUrl)
        assertEquals("original-poster", store.poster(title.id, "cached-auto"))
        assertEquals("original-background", store.backdrop(title.id, "cached-auto"))
        val episode = cached.copy(episode = CardEpisode(1, 2, "Episode", null), artworkUrl = "episode-still")
        assertEquals("episode-still", store.card(episode).artworkUrl)
        store.saved(1, title.id, chosen.copy(posterUrl = "new-manual-poster", backdropUrl = null))
        assertEquals("new-manual-poster", store.card(cached).posterUrl)
        assertEquals("original-background", store.card(cached).backdropUrl)
        store.replace(1, listOf(chosen.copy(posterUrl = null, backdropUrl = "new-server-background")))
        assertEquals("learned-text", store.card(cached).posterUrl)
        assertEquals("new-server-background", store.card(cached).backdropUrl)
        store.replace(1, emptyList())
        assertEquals("learned-background", store.card(cached).backdropUrl)
    }

    @Test fun knownStandardRejectsStaleReadsAndProfileChangesWhileUnknownLegacyResetKeepsFallback() {
        var current = 1L
        val store = PersonalArtworkStore { current }
        store.activate(1)
        val oldRevision = store.revision
        store.saved(1, title.id, chosen.copy(posterUrl = null, backdropUrl = null))
        store.restoredStandard(1, title.id, ArtworkKind.POSTER, "original-poster")
        store.replace(1, listOf(chosen), oldRevision)
        assertEquals("original-poster", store.poster(title.id, "cached-auto"))
        val freshRevision = store.revision
        store.replace(1, emptyList(), freshRevision)
        store.replace(1, listOf(chosen.copy(posterUrl = null, backdropUrl = null)), freshRevision)
        assertEquals("fresh-automatic-picture", store.poster(title.id, "fresh-automatic-picture"))
        store.restoredStandard(1, title.id, ArtworkKind.BACKDROP, null)
        assertEquals("cached-auto", store.backdrop(title.id, "cached-auto"))
        current = 2
        assertEquals("second-profile", store.poster(title.id, "second-profile"))
        store.activate(2)
        store.restoredStandard(1, title.id, ArtworkKind.POSTER, "late-original")
        store.saved(1, title.id, chosen)
        store.replace(1, listOf(chosen))
        assertEquals("second-profile", store.poster(title.id, "second-profile"))
    }
}
