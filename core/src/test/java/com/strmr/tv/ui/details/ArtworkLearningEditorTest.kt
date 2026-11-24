package com.strmr.tv.ui.details

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ArtworkKind
import com.strmr.tv.api.ArtworkLearningState
import com.strmr.tv.api.ContentArtworkPreference
import com.strmr.tv.api.Title
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtworkLearningEditorTest {
    private val title = ApiJson.decodeFromString<Title>("""{"id":"tmdb:movie:1","name":"Synthetic","poster":{"url":"poster"},"posters":[{"url":"alternative"}]}""")
    private class Gateway : ContentArtworkGateway {
        var current = true
        var manual = ContentArtworkPreference("tmdb:movie:1", true, posterUrl = "poster", backdropUrl = "manual-background")
        var learning: ArtworkLearningState? = ArtworkLearningState("profile-one", true, false, 3, 128, 0, 0)
        var learningReadError: Exception? = null
        var failure: Exception? = null
        var reply: ArtworkLearningState? = null
        var hold: CompletableDeferred<Unit>? = null
        val learningWrites = mutableListOf<Pair<Boolean?, Boolean>>()
        val manualWrites = mutableListOf<Pair<ArtworkKind, String?>>()
        override fun isCurrent() = current
        override suspend fun read() = manual
        override suspend fun write(kind: ArtworkKind, value: String?): ContentArtworkPreference {
            manualWrites += kind to value
            manual = if (kind == ArtworkKind.POSTER) manual.copy(posterUrl = value) else manual.copy(backdropUrl = value)
            if (learning?.enabled == true && value != null) learning = learning!!.copy(posterSamples = learning!!.posterSamples + 1)
            return manual
        }
        override suspend fun readLearning(): ArtworkLearningState? { learningReadError?.let { throw it }; return learning }
        override suspend fun writeLearning(enabled: Boolean?, reset: Boolean): ArtworkLearningState {
            learningWrites += enabled to reset
            hold?.await(); failure?.let { throw it }; reply?.let { return it }
            learning = requireNotNull(learning).let { if (reset) it.copy(enabled = false, posterSamples = 0, backdropSamples = 0) else it.copy(enabled = requireNotNull(enabled)) }
            return learning!!
        }
    }
    @Test fun learningStartsOffAndWaitsForConfirmationWithoutDuplicateWrites() = runTest {
        val gateway = Gateway().apply { hold = CompletableDeferred() }
        val editor = ContentArtworkEditor(title, gateway) { }
        editor.load()
        assertFalse(editor.state.value.learning!!.enabled); assertTrue(gateway.learningWrites.isEmpty())
        val write = launch { editor.setLearningEnabled(true) }; runCurrent()
        assertTrue(editor.state.value.learningSaving); assertFalse(editor.state.value.learning!!.enabled)
        assertNull(editor.state.value.message)
        editor.setLearningEnabled(true); assertEquals(1, gateway.learningWrites.size)
        gateway.hold!!.complete(Unit); write.join()
        assertTrue(editor.state.value.learning!!.enabled)
        assertTrue(editor.state.value.message!!.contains("Only future picture choices"))
    }
    @Test fun optionalMissingOrFailedLearningDoesNotDisableManualChoices() = runTest {
        for (failed in listOf(false, true)) {
            val gateway = Gateway().apply { learning = null; if (failed) learningReadError = IOException("Synthetic offline") }
            val editor = ContentArtworkEditor(title, gateway) { }
            editor.load(); editor.save("alternative"); editor.setLearningEnabled(true)
            assertTrue(editor.state.value.supported); assertNull(editor.state.value.error)
            assertEquals("alternative", editor.state.value.selected)
            assertTrue(gateway.learningWrites.isEmpty())
            if (failed) assertNotNull(editor.state.value.learningError)
        }
    }
    @Test fun failedLearningWriteHasNoOptimismOrRetryAndManualArtworkStillWorks() = runTest {
        val gateway = Gateway().apply { failure = IOException("Synthetic failed write") }
        val editor = ContentArtworkEditor(title, gateway) { }
        editor.load(); editor.setLearningEnabled(true)
        assertFalse(editor.state.value.learning!!.enabled); assertNotNull(editor.state.value.learningError)
        assertNull(editor.state.value.message)
        editor.setLearningEnabled(true); assertEquals(1, gateway.learningWrites.size)
        editor.save("alternative"); assertEquals("alternative", editor.state.value.selected)
        gateway.failure = null; editor.load(); editor.setLearningEnabled(true)
        assertTrue(editor.state.value.learning!!.enabled); assertEquals(2, gateway.learningWrites.size)
    }
    @Test fun forgetDisablesAndErasesLearnedCountersWithoutChangingManualArtwork() = runTest {
        val gateway = Gateway().apply { learning = learning!!.copy(enabled = true, posterSamples = 5, backdropSamples = 3) }
        var manualCallbacks = 0
        val editor = ContentArtworkEditor(title, gateway) { manualCallbacks++ }
        editor.load(); editor.forgetLearning()
        assertEquals(listOf(null to true), gateway.learningWrites)
        assertFalse(editor.state.value.learning!!.enabled)
        assertEquals(0, editor.state.value.learning!!.posterSamples); assertEquals(0, editor.state.value.learning!!.backdropSamples)
        assertEquals("poster", editor.state.value.preference!!.posterUrl)
        assertEquals("manual-background", editor.state.value.preference!!.backdropUrl)
        assertTrue(gateway.manualWrites.isEmpty()); assertEquals(0, manualCallbacks)
    }
    @Test fun staleProfileOrClosedEditorCannotAdoptLearningWrite() = runTest {
        for (close in listOf(false, true)) {
            val gateway = Gateway().apply { hold = CompletableDeferred() }
            val editor = ContentArtworkEditor(title, gateway) { }
            editor.load()
            val write = launch { editor.setLearningEnabled(true) }; runCurrent()
            if (close) editor.close() else gateway.current = false
            gateway.hold!!.complete(Unit); write.join()
            assertTrue(write.isCancelled); assertFalse(editor.state.value.learning!!.enabled)
            assertNull(editor.state.value.message)
        }
    }
    @Test fun foreignOrUnconfirmedLearningAcknowledgementIsRejected() = runTest {
        val valid = ArtworkLearningState("profile-one", true, true, 3, 128, 3, 0)
        for (bad in listOf(valid.copy(profileId = "another"), valid.copy(supported = false), valid.copy(enabled = false),
            valid.copy(minimumSamples = 2), valid.copy(maxSamples = 256), valid.copy(posterSamples = 129), valid.copy(backdropSamples = -1),
            valid.copy(posterSamples = 80, backdropSamples = 80))) {
            val gateway = Gateway().apply { reply = bad }
            val editor = ContentArtworkEditor(title, gateway) { }
            editor.load(); editor.setLearningEnabled(true)
            assertFalse(editor.state.value.learning!!.enabled); assertNotNull(editor.state.value.learningError)
            assertNull(editor.state.value.message); assertEquals(1, gateway.learningWrites.size)
        }
    }
    @Test fun successfulManualChoiceRefreshesConfirmedLearningCounters() = runTest {
        val gateway = Gateway().apply { learning = learning!!.copy(enabled = true, posterSamples = 2) }
        val editor = ContentArtworkEditor(title, gateway) { }
        editor.load(); editor.save("alternative")
        assertEquals(3, editor.state.value.learning!!.posterSamples)
        assertEquals("alternative", editor.state.value.selected)
        assertEquals("manual-background", editor.state.value.preference!!.backdropUrl)
    }
    @Test fun learnedProjectionKeepsOriginalStandardPreviewAndDeliveredVariants() {
        val projected = ApiJson.decodeFromString<Title>("""{"id":"tmdb:movie:1","name":"Synthetic","poster":{"url":"learned-poster"},"textPoster":{"url":"learned-text"},"backdrop":{"url":"learned-background"},"standardPoster":{"url":"original-poster"},"standardBackdrop":{"url":"original-background"},"posters":[{"url":"alternative"}]}""")
        val posters = artworkOptions(projected, ArtworkKind.POSTER)
        assertNull(posters.first().url); assertEquals("original-poster", posters.first().previewUrl)
        assertEquals("original-background", artworkOptions(projected, ArtworkKind.BACKDROP).first().previewUrl)
        assertTrue(posters.any { it.url == "alternative" }); assertTrue(posters.any { it.url == "learned-poster" })
        assertEquals("learned-poster", projected.poster!!.url)
        assertNull(title.standardPoster); assertNull(title.standardBackdrop)
    }

    @Test fun onlyConfirmedNullResetPublishesKnownStandardDisplayWithoutFakingServerPreference() = runTest {
        val projected = ApiJson.decodeFromString<Title>("""{"id":"tmdb:movie:1","name":"Synthetic","poster":{"url":"learned"},"standardPoster":{"url":"original"}}""")
        for (confirmed in listOf(false, true)) {
            val gateway = object : ContentArtworkGateway {
                override fun isCurrent() = true
                override suspend fun read() = ContentArtworkPreference(projected.id, true, posterUrl = "manual")
                override suspend fun write(kind: ArtworkKind, value: String?) =
                    ContentArtworkPreference(projected.id, true, posterUrl = if (confirmed) value else "manual")
            }
            val saved = mutableListOf<ContentArtworkPreference>()
            val standards = mutableListOf<Pair<ArtworkKind, String>>()
            val editor = ContentArtworkEditor(projected, gateway, saved::add, { kind, url -> standards += kind to url })
            editor.load(); editor.save(null)
            if (confirmed) {
                assertEquals(listOf(ArtworkKind.POSTER to "original"), standards)
                assertNull(saved.single().posterUrl)
                assertNull(editor.state.value.preference!!.posterUrl)
            } else {
                assertTrue(standards.isEmpty()); assertTrue(saved.isEmpty())
                assertNotNull(editor.state.value.error)
            }
        }
    }
}
