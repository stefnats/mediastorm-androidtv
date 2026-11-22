package com.strmr.tv.player

import com.strmr.tv.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenAudioCheckTest {
    private class Gateway : ReleaseReportGateway {
        var current = true
        var available = true
        var version = "original-version"
        var checks = 0
        var reports = 0
        var gate: CompletableDeferred<Unit>? = null
        var waitMs = 0L
        var input: SpokenAudioInput? = null
        var reply = SpokenAudioReply("likely_mismatch", requestedLanguage = "eng", detectedLanguage = "spa",
            confidence = 0.93, samples = 2, sourceVersion = version, audioTrackIndex = 2)
        override fun isCurrent() = current
        override suspend fun options(id: String) = ReleaseReportOptions(version, audioLanguage = "eng",
            audioTracks = listOf(ProbedAudioTrack(2, "eng", "aac")), audioLanguageCheckAvailable = available)
        override suspend fun checkAudioLanguage(id: String, input: SpokenAudioInput): SpokenAudioReply {
            assertEquals("original-source", id)
            checks++; this.input = input
            gate?.let { withContext(NonCancellable) { it.await() } }
            delay(waitMs)
            return reply
        }
        override suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply {
            reports++; return ReleaseReportReply()
        }
        override suspend fun status(id: String): PrequeueStatus = error("Language check must not prepare")
        override suspend fun cancel(id: String) { error("No alternative was prepared") }
        override suspend fun undo(id: String) { error("No report was saved") }
    }

    private fun flow(gateway: Gateway, scope: CoroutineScope, track: () -> Int? = { 2 }) =
        ReleaseReports("original-source", gateway, scope, { 42_500 }, { _, _, _ -> fail("Language check adopted a source") },
            clientId = "synthetic-device", currentAudioTrack = { track() })

    @Test fun capabilityIsOptionalAndNoSamplingStartsUntilExplicitClick() = runTest {
        assertFalse(ApiJson.decodeFromString<ReleaseReportOptions>("""{"sourceVersion":"old"}""").audioLanguageCheckAvailable)
        val gateway = Gateway().apply { available = false }
        val flow = flow(gateway, this)
        flow.load()
        assertFalse(flow.canCheckAudioLanguage)
        assertTrue(releaseReportChoices(flow, flow.state.value, {}).none { it.title == "Check spoken audio language" })
        flow.checkAudioLanguage(); advanceUntilIdle()
        assertEquals(0, gateway.checks)
        flow.close(); advanceUntilIdle()
    }

    @Test fun explicitCheckSamplesCurrentPositionWithoutReportSwitchOrBlock() = runTest {
        val gateway = Gateway(); val flow = flow(gateway, this)
        flow.load(); assertEquals(0, gateway.checks)
        val choice = releaseReportChoices(flow, flow.state.value, {}).single { it.title == "Check spoken audio language" }
        choice.onClick(); flow.checkAudioLanguage(); advanceUntilIdle()
        assertEquals(1, gateway.checks)
        assertEquals(42.5, gateway.input!!.startOffset, 0.0)
        assertEquals(2, gateway.input!!.audioTrackIndex)
        assertEquals("original-version", gateway.input!!.sourceVersion)
        assertEquals(0, gateway.reports)
        assertNull(flow.state.value.reportId); assertNull(flow.state.value.ready)
        assertFalse(flow.state.value.progressStopped)
        assertTrue(flow.state.value.audioLanguageCheckMessage!!.contains("likely"))
        assertTrue(releaseReportChoices(flow, flow.state.value, {}).any { it.title == "Prepare an alternative without reporting" })
        flow.close(); advanceUntilIdle()
    }

    @Test fun unknownAndWeakOrContradictoryObservationsRemainUnknown() {
        val base = SpokenAudioReply("likely_mismatch", requestedLanguage = "eng", detectedLanguage = "spa", confidence = .95,
            samples = 2, sourceVersion = "v", audioTrackIndex = 2)
        for (reply in listOf(base.copy(status = "unknown"), base.copy(samples = 1), base.copy(confidence = .4),
            base.copy(status = "likely_match"), base.copy(detectedLanguage = "und"), base.copy(requestedLanguage = "fra"))) {
            assertTrue(spokenAudioMessage(reply, "eng").contains("unknown"))
        }
        assertTrue(spokenAudioMessage(base.copy(status = "likely_match", detectedLanguage = "eng"), "eng").contains("only a hint"))
    }

    @Test fun closeTrackABAAndOwnerChangeCannotAdoptLateReply() = runTest {
        for (kind in listOf("close", "track ABA", "owner")) {
            val gateway = Gateway().apply { gate = CompletableDeferred() }
            var track = 2
            val flow = flow(gateway, this) { track }
            flow.load(); flow.checkAudioLanguage(); runCurrent()
            assertEquals(1, gateway.checks)
            when (kind) {
                "close" -> flow.close()
                "track ABA" -> { track = 3; flow.cancelAudioLanguageCheck(); track = 2 }
                else -> gateway.current = false
            }
            gateway.gate!!.complete(Unit); runCurrent()
            assertNull(flow.state.value.audioLanguageCheckMessage)
            assertEquals(0, gateway.reports)
            flow.close(); advanceUntilIdle()
        }
    }

    @Test fun changedSourceOrUnmappedTrackCannotSampleAnything() = runTest {
        for (kind in listOf("source", "track")) {
            val gateway = Gateway(); val flow = flow(gateway, this) { if (kind == "track") null else 2 }
            flow.load()
            if (kind == "source") gateway.version = "replacement-version"
            flow.checkAudioLanguage(); advanceUntilIdle()
            assertEquals(0, gateway.checks)
            assertTrue(flow.state.value.audioLanguageCheckMessage!!.contains("Could not check"))
            flow.close(); advanceUntilIdle()
        }
    }

    @Test fun timeoutAndWrongEchoNeverBecomeAConfirmedLanguageHint() = runTest {
        for (kind in listOf("timeout", "source echo", "track echo")) {
            val gateway = Gateway().apply {
                if (kind == "timeout") waitMs = 200_000
                if (kind == "source echo") reply = reply.copy(sourceVersion = "other")
                if (kind == "track echo") reply = reply.copy(audioTrackIndex = 3)
            }
            val flow = flow(gateway, this); flow.load(); flow.checkAudioLanguage(); advanceUntilIdle()
            assertEquals(1, gateway.checks)
            assertFalse(flow.state.value.audioLanguageCheckBusy)
            assertFalse(flow.state.value.audioLanguageCheckMessage!!.contains("Both samples likely"))
            assertEquals(0, gateway.reports)
            flow.close(); advanceUntilIdle()
        }
    }

    @Test fun currentAudioMappingRequiresBothContainerNumberAndContainerOrder() {
        val options = ReleaseReportOptions("original", audioTracks = listOf(ProbedAudioTrack(2, "eng", "aac"), ProbedAudioTrack(4, "spa", "aac")))
        val formatId = "3"
        val language = "eng"
        assertEquals(2, spokenAudioContainerIndex(formatId, language, 0, options))
        assertNull(spokenAudioContainerIndex(formatId, language, 1, options))
        assertNull(spokenAudioContainerIndex("audio-group", language, 0, options))
        assertNull(spokenAudioContainerIndex(formatId, "spa", 0, options))
        assertNull(spokenAudioContainerIndex(null, 0, options))
    }
}
