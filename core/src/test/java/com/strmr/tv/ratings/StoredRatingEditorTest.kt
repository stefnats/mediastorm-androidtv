package com.strmr.tv.ratings

import com.strmr.tv.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StoredRatingEditorTest {
    private val target = DetailsTarget("tmdb:movie:2", "Synthetic", true)
    private class Gateway : TitleRatingGateway {
        var current = true
        var reads = 0
        var writes = 0
        var failure: Exception? = null
        override fun isCurrent() = current
        override suspend fun read(target: DetailsTarget): TitleRatingReply { reads++; failure?.let { throw it }; return TitleRatingReply("love") }
        override suspend fun write(target: DetailsTarget, rating: PersonalRating): TitleRatingReply { writes++; failure?.let { throw it }; return TitleRatingReply(rating.wire) }
    }
    @Test fun `validated initial value is consumed once without duplicating necessary gateway read`() = runTest {
        val g = Gateway(); var changes = 0
        val editor = TitleRatingEditor(target, g, initialRating = TitleRatingReply("love"), receiptReloadHint = "Choose Rate again.", onSaved = { changes++ })
        editor.load(); assertEquals(0, g.reads); assertEquals(PersonalRating.LOVE, editor.state.value.rating)
        editor.save(PersonalRating.LIKE); assertEquals(1, g.writes); assertEquals(1, changes)
    }
    @Test fun `stored 409 blocks save and retry until fresh explicit Rate editor`() = runTest {
        val g = Gateway(); var changes = 0
        val editor = TitleRatingEditor(target, g, initialRating = TitleRatingReply("love"), receiptReloadHint = "Close this prompt and choose Rate again.", onSaved = { changes++ })
        editor.load(); g.failure = ApiError.Http(HttpFailure(409, "expired")); editor.save(PersonalRating.LIKE)
        assertTrue(editor.state.value.requiresReopen); assertTrue(editor.state.value.error!!.contains("Rate again")); assertFalse(editor.state.value.saving)
        editor.load(); editor.save(PersonalRating.LIKE); assertEquals(0, g.reads); assertEquals(1, g.writes); assertEquals(0, changes)
        editor.close(); g.failure = null
        val renewed = TitleRatingEditor(target, g, initialRating = TitleRatingReply("love"), receiptReloadHint = "Rate again")
        renewed.load(); renewed.save(PersonalRating.LIKE); assertEquals(2, g.writes); assertFalse(renewed.state.value.requiresReopen)
    }
    @Test fun `legacy editor keeps ordinary 409 retry behavior`() = runTest {
        val g = Gateway(); val editor = TitleRatingEditor(target, g); editor.load()
        g.failure = ApiError.Http(HttpFailure(409, "expired")); editor.save(PersonalRating.LIKE)
        assertFalse(editor.state.value.requiresReopen); g.failure = null; editor.save(PersonalRating.LIKE)
        assertEquals(2, g.writes); assertEquals(PersonalRating.LIKE, editor.state.value.rating)
    }
    @Test fun `ordinary stored error permits explicit retry while owner loss blocks supplied initial`() = runTest {
        val g = Gateway(); val editor = TitleRatingEditor(target, g, initialRating = TitleRatingReply("love"), receiptReloadHint = "Rate again")
        editor.load(); g.failure = java.io.IOException("private diagnostic"); editor.save(PersonalRating.LIKE)
        assertFalse(editor.state.value.requiresReopen); assertFalse(editor.state.value.error!!.contains("private"))
        g.current = false
        val stale = TitleRatingEditor(target, g, initialRating = TitleRatingReply("love"))
        assertTrue(runCatching { stale.load() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(stale.state.value.rating); assertEquals(0, g.reads)
    }
}
