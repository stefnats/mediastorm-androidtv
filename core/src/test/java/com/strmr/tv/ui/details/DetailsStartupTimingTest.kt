package com.strmr.tv.ui.details

import com.strmr.tv.api.DetailsBundle
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Title
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsStartupTimingTest {
    private val target = DetailsTarget("tmdb:movie:42", "Synthetic", true)
    private inner class Fixture(test: TestScope) {
        val owner = Job()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + owner)
        var currentPage = true
        var nanos = 0L
        val recorder = PlaybackStartupRecorder({ nanos })
        var receipt: PlaybackStartupIntent? = null
        val model = DetailsModel(target, scope, retireWarm = { _, _ -> },
            captureStartup = { context, value -> recorder.intent(context, value, StartupEntry.DETAILS_PLAY).also { receipt = it } },
            startupCurrent = { currentPage }, lookupLibrary = { _, _ -> error("No library transport expected") })
        suspend fun load() { model.load { DetailsBundle(movieDetails = Title(target.titleId, "Synthetic", mediaType = "movie")) } }
    }
    @Test fun `actual Details model captures while library await is pending and records original cancellation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture(this)
        try {
            f.load(); f.model.play(null, null); runCurrent()
            assertNotNull(f.receipt); assertEquals(1, f.recorder.inFlight())
            // No loadLibrary call: the original model is suspended before coordinator/provider IO.
            f.nanos = 30_000_000; f.owner.cancel(); runCurrent()
            assertEquals(StartupEntry.DETAILS_PLAY, f.recorder.records().single().entry)
            assertEquals(StartupOutcome.CANCELLED, f.recorder.snapshot().single().outcome)
            assertEquals(30L, f.recorder.snapshot().single().elapsedMillis)
        } finally { f.owner.cancel(); runCurrent(); Dispatchers.resetMain(); ProfileDataContext.invalidate() }
    }
    @Test fun `actual model guard retires covered page receipt before coordinator adoption`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture(this)
        try {
            f.load(); f.model.play(null, null); runCurrent(); val original = requireNotNull(f.receipt)
            f.currentPage = false
            // This is the exact production recorder adoption used by PlaybackCoordinator.begin.
            assertNull(f.recorder.adopt(original, target, original.owner.epoch, StartupInput.UNKNOWN))
            assertEquals(StartupOutcome.CANCELLED, f.recorder.snapshot().single().outcome)
            assertTrue(f.model.playable) // Instrumentation does not become a playback/access gate.
        } finally { f.owner.cancel(); runCurrent(); Dispatchers.resetMain(); ProfileDataContext.invalidate() }
    }
    @Test fun `programmatic Details autoplay does not manufacture a user click before library await`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture(this)
        try {
            f.load(); f.model.play(null, null, measureStartup = false); runCurrent()
            assertNull(f.receipt); assertEquals(0, f.recorder.inFlight()); assertTrue(f.recorder.snapshot().isEmpty())
        } finally { f.owner.cancel(); runCurrent(); Dispatchers.resetMain(); ProfileDataContext.invalidate() }
    }
}
