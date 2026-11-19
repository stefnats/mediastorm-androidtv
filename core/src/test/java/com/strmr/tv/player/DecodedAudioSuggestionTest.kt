package com.strmr.tv.player

import com.strmr.tv.api.PrequeueStartResponse
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.ReleaseReportOptions
import com.strmr.tv.api.ReleaseReportReply
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DecodedAudioSuggestionTest {
    private class Gateway : ReleaseReportGateway {
        var current = true
        var optionsCalls = 0
        var submissions = 0
        var reports = 0
        var statuses = 0
        var cancels = 0
        override fun isCurrent() = current
        override suspend fun options(id: String): ReleaseReportOptions {
            optionsCalls++
            return ReleaseReportOptions("synthetic-original-source", audioLanguage = "fra")
        }
        override suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply {
            submissions++; if (reporting) reports++
            assertFalse(body.containsKey("reason")); assertFalse(body.containsKey("audioTrackIndex"))
            return ReleaseReportReply(replacement = PrequeueStartResponse("synthetic-alternative"))
        }
        override suspend fun status(id: String): PrequeueStatus {
            statuses++
            return PrequeueStatus(id, "ready", streamPath = "/synthetic/alternative", duration = 600.0)
        }
        override suspend fun cancel(id: String) { cancels++ }
        override suspend fun undo(id: String) { fail("uncertain silence created a persistent report") }
    }

    @Test fun uncertainContentHintDoesNoIoAndSavesNoReportUntilUserExplicitlyOpensReview() = runTest {
        val gateway = Gateway(); var adopted: Long? = null
        val assistant = AudioRecoveryAssistant(this,
            { ReleaseReports("synthetic-original", gateway, this, { 95_000 }, { _, offset, _ -> adopted = offset }, clientId = "synthetic-device") },
            { gateway.current })
        assertTrue(assistant.suggest(AudioRecoveryHint.SILENCE))
        advanceUntilIdle()
        assertEquals(0, gateway.optionsCalls); assertEquals(0, gateway.submissions); assertEquals(0, gateway.statuses)
        assertNull(adopted)
        assertTrue(audioRecoveryMessage(AudioRecoveryHint.SILENCE, assistant.flow!!.state.value).contains("may be intentional"))
        assertTrue(audioRecoveryMessage(AudioRecoveryHint.SILENCE, assistant.flow!!.state.value).contains("No alternative has been requested"))
        val flow = assistant.flow!!
        flow.load() // The same explicit source-review action used by both native menus.
        assertEquals(1, gateway.optionsCalls); assertEquals(0, gateway.submissions)
        flow.submit(false, true); advanceUntilIdle()
        assertEquals(1, gateway.submissions); assertEquals(0, gateway.reports)
        assertNotNull(flow.state.value.ready); assertNull(adopted)
        flow.take()
        assertEquals(85_000L, adopted)
        assistant.dismiss(); advanceUntilIdle()
        assertEquals(0, gateway.cancels)
    }

    @Test fun dismissalOfUnreviewedSilenceDoesNotLoadCancelOrAdoptAnything() = runTest {
        val gateway = Gateway()
        val assistant = AudioRecoveryAssistant(this,
            { ReleaseReports("synthetic-original", gateway, this, { 0 }, { _, _, _ -> fail("dismissal adopted") }, clientId = "synthetic-device") },
            { gateway.current })
        assertTrue(assistant.suggest(AudioRecoveryHint.SILENCE))
        assistant.dismiss(); advanceUntilIdle()
        assertFalse(assistant.active)
        assertEquals(0, gateway.optionsCalls + gateway.submissions + gateway.statuses + gateway.cancels + gateway.reports)
    }

    @Test fun lostOriginalContextCannotLoadTheDeferredReviewOrCreateAPreparation() = runTest {
        val gateway = Gateway()
        val assistant = AudioRecoveryAssistant(this,
            { ReleaseReports("synthetic-original", gateway, this, { 0 }, { _, _, _ -> fail("stale adoption") }, clientId = "synthetic-device") },
            { gateway.current })
        assertTrue(assistant.suggest(AudioRecoveryHint.SILENCE))
        gateway.current = false
        assistant.flow!!.load(); advanceUntilIdle()
        assertEquals(0, gateway.optionsCalls + gateway.submissions + gateway.statuses + gateway.reports)
        assistant.dismiss()
        assertFalse(assistant.suggest(AudioRecoveryHint.SILENCE))
    }

    @Test fun technicalDecoderHintStillPreparesAutomaticallyWithoutSavingAReport() = runTest {
        val gateway = Gateway()
        val assistant = AudioRecoveryAssistant(this,
            { ReleaseReports("synthetic-original", gateway, this, { 0 }, { _, _, _ -> fail("automatic adoption") }, clientId = "synthetic-device") },
            { gateway.current })
        assertTrue(assistant.suggest(AudioRecoveryHint.DECODER)); advanceUntilIdle()
        assertEquals(1, gateway.optionsCalls); assertEquals(1, gateway.submissions)
        assertEquals(0, gateway.reports); assertNotNull(assistant.flow!!.state.value.ready)
        assistant.dismiss(); advanceUntilIdle()
        assertEquals(1, gateway.cancels)
    }
}
