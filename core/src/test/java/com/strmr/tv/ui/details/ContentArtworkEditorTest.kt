package com.strmr.tv.ui.details

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ArtworkKind
import com.strmr.tv.api.ContentArtworkPreference
import com.strmr.tv.api.ImageRef
import com.strmr.tv.api.Title
import com.strmr.tv.api.contentArtworkPreferenceBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContentArtworkEditorTest {
    private val title = Title("tmdb:movie:1", "Synthetic", poster = ImageRef("poster-primary"), textPoster = ImageRef("poster-text"),
        posters = listOf(ImageRef("poster-primary"), ImageRef("poster-alternative")), backdrop = ImageRef("background-primary"),
        textBackdrop = ImageRef("background-text"), backdrops = listOf(ImageRef("background-alternative")))
    private class Gateway : ContentArtworkGateway {
        var current = true
        var value = ContentArtworkPreference("tmdb:movie:1", true, backdropUrl = "background-alternative")
        var hold: CompletableDeferred<Unit>? = null
        var reply: ContentArtworkPreference? = null
        val writes = mutableListOf<Pair<ArtworkKind, String?>>()
        override fun isCurrent() = current
        override suspend fun read() = value
        override suspend fun write(kind: ArtworkKind, value: String?): ContentArtworkPreference {
            writes += kind to value
            hold?.await()
            this.value = if (kind == ArtworkKind.POSTER) this.value.copy(posterUrl = value) else this.value.copy(backdropUrl = value)
            return reply ?: this.value
        }
    }

    @Test fun olderServerDoesNotOfferOrSendAnArtworkMutation() = runTest {
        val gateway = Gateway().apply { value = ApiJson.decodeFromString<ContentArtworkPreference>("""{"audioLanguage":"spa"}""") }
        val editor = ContentArtworkEditor(title, gateway) { fail("Unsupported artwork published") }
        editor.load(); editor.save("poster-alternative")
        assertFalse(editor.state.value.supported)
        assertNotNull(editor.state.value.error)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun deliveredVariantsAreBoundedDeduplicatedAndKeepStandardTextPoster() {
        val posterOptions = artworkOptions(title, ArtworkKind.POSTER)
        assertNull(posterOptions.first().url)
        assertEquals("poster-text", posterOptions.first().previewUrl)
        assertEquals(listOf(null, "poster-primary", "poster-text", "poster-alternative"), posterOptions.map { it.url })
        val backgrounds = artworkOptions(title, ArtworkKind.BACKDROP)
        assertEquals("background-primary", backgrounds.first().previewUrl)
        assertTrue(backgrounds.any { it.url == "background-text" })
        assertTrue(backgrounds.any { it.url == "background-alternative" })
        val many = title.copy(posters = (1..100).map { ImageRef("poster-$it") })
        assertEquals(41, artworkOptions(many, ArtworkKind.POSTER).size)
        assertEquals("poster-primary", title.poster!!.url)
        assertEquals("poster-text", title.textPoster!!.url)
    }

    @Test fun choosingAndResettingOneImagePreservesOtherPreferences() = runTest {
        val gateway = Gateway()
        val saved = mutableListOf<ContentArtworkPreference>()
        val editor = ContentArtworkEditor(title, gateway, saved::add)
        editor.load(); editor.save("not-a-delivered-variant")
        assertTrue(gateway.writes.isEmpty())
        editor.save("poster-alternative")
        assertEquals("poster-alternative", editor.state.value.selected)
        assertEquals("background-alternative", saved.last().backdropUrl)
        editor.save(null)
        assertNull(editor.state.value.selected)
        assertEquals("background-alternative", saved.last().backdropUrl)
        assertTrue(editor.state.value.message!!.contains("restored"))
        val body = contentArtworkPreferenceBody(title.id, "movie", ArtworkKind.POSTER, null)
        assertEquals(setOf("contentId", "contentType", "posterUrl"), body.keys)
        assertEquals(JsonNull, body["posterUrl"])
        assertTrue(ApiJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body).contains("\"posterUrl\":null"))
    }

    @Test fun heldWriteWaitsForConfirmationAndCannotPublishIntoAnotherProfile() = runTest {
        val gateway = Gateway().apply { hold = CompletableDeferred() }
        var published = 0
        val editor = ContentArtworkEditor(title, gateway) { published++ }
        editor.load()
        val write = launch { editor.save("poster-alternative") }
        runCurrent()
        assertTrue(editor.state.value.saving)
        assertNull(editor.state.value.selected)
        assertEquals(0, published)
        gateway.current = false
        gateway.hold!!.complete(Unit); write.join()
        assertTrue(write.isCancelled)
        assertEquals(0, published)
        assertNull(editor.state.value.selected)
    }

    @Test fun unconfirmedWriteLeavesPreviousDisplayAndRequiresReload() = runTest {
        val gateway = Gateway().apply { reply = value }
        var published = 0
        val editor = ContentArtworkEditor(title, gateway) { published++ }
        editor.load(); editor.save("poster-alternative")
        assertNotNull(editor.state.value.error)
        assertNull(editor.state.value.selected)
        assertEquals(0, published)
        editor.save(null)
        assertEquals(1, gateway.writes.size)
        gateway.reply = null; editor.load()
        assertNull(editor.state.value.error)
        assertEquals("poster-alternative", editor.state.value.selected)
    }
}
