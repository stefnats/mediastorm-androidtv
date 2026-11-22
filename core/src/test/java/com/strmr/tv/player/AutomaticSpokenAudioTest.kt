package com.strmr.tv.player

import com.strmr.tv.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSpokenAudioTest {
    private fun sample(time: Long, position: Long = time) = SpokenAudioPlayback(time, position, true, false, true, "3:0:eng", true, 1f, 1f)
    private fun qualify(automatic: AutomaticSpokenAudio) { for (second in 0..45) automatic.observe(sample(second * 1_000L)) }

    @Test fun requiresContinuousWallTimeAndVideoAdvanceThenSpendsOneOpportunity() {
        val policy = SpokenAudioAutoPolicy()
        for (second in 0..44) assertFalse(policy.observe(sample(second * 1_000L), true))
        assertTrue(policy.observe(sample(45_000), true))
        policy.resetWindow()
        for (second in 46..120) assertFalse(policy.observe(sample(second * 1_000L), true))
    }

    @Test fun resumeSeekPauseMuteBufferAndTrackChangesStartANewWindow() {
        val interruptions: List<(SpokenAudioPlayback) -> SpokenAudioPlayback> = listOf(
            { it.copy(playing = false) }, { it.copy(buffering = true) }, { it.copy(videoSelected = false) },
            { it.copy(audioKey = null) }, { it.copy(audioEnabled = false) }, { it.copy(volume = 0f) },
            { it.copy(audioKey = "other-track") }, { it.copy(positionMs = 500_000) },
        )
        for (interrupt in interruptions) {
            val policy = SpokenAudioAutoPolicy()
            for (second in 0..40) assertFalse(policy.observe(sample(second * 1_000L, 600_000 + second * 1_000L), true))
            assertFalse(policy.observe(interrupt(sample(41_000, 641_000)), true))
            for (second in 42..86) assertFalse(policy.observe(sample(second * 1_000L, 600_000 + second * 1_000L), true))
            assertTrue(policy.observe(sample(87_000, 687_000), true))
        }
        val stopped = SpokenAudioAutoPolicy()
        for (second in 0..60) assertFalse(stopped.observe(sample(second * 1_000L, 500_000), true))
        val details = SpokenAudioAutoPolicy()
        for (second in 0..60) assertFalse(details.observe(sample(second * 1_000L), false))
    }

    private class Gateway : ReleaseReportGateway {
        var current = true
        var optedIn = true
        var optOutOnRefresh = false
        var available = true
        var checks = 0
        var optionsReads = 0
        var gate: CompletableDeferred<Unit>? = null
        var reply = SpokenAudioReply("likely_mismatch", requestedLanguage = "eng", detectedLanguage = "spa",
            confidence = .95, samples = 2, sourceVersion = "original", audioTrackIndex = 2)
        override fun isCurrent() = current
        override suspend fun options(id: String): ReleaseReportOptions {
            optionsReads++
            return ReleaseReportOptions("original", audioLanguage = "eng", audioLanguageCheckAvailable = available,
                autoCheckAudioLanguage = optedIn && !(optOutOnRefresh && optionsReads > 1), audioTracks = listOf(ProbedAudioTrack(2, "eng", "aac")))
        }
        override suspend fun checkAudioLanguage(id: String, input: SpokenAudioInput): SpokenAudioReply {
            assertEquals("source", id); assertEquals(2, input.audioTrackIndex)
            assertEquals(45.0, input.startOffset, 0.0)
            checks++
            gate?.let { withContext(NonCancellable) { it.await() } }
            return reply
        }
        override suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply = error("Automatic check must not report or prepare")
        override suspend fun status(id: String): PrequeueStatus = error("No alternative must be polled")
        override suspend fun cancel(id: String) { error("No alternative exists") }
        override suspend fun undo(id: String) { error("No report exists") }
    }
    private fun flow(gateway: Gateway, scope: CoroutineScope, track: () -> Int? = { 2 }) =
        ReleaseReports("source", gateway, scope, { 45_000 }, { _, _, _ -> fail("Automatic hint switched source") },
            clientId = "synthetic", currentAudioTrack = { track() })

    @Test fun onlyCredibleMismatchOffersReviewAndNeverPreparesReportsOrSwitches() = runTest {
        for (status in listOf("likely_match", "unknown", "likely_mismatch")) {
            val gateway = Gateway().apply { reply = reply.copy(status = status, detectedLanguage = if (status == "likely_match") "eng" else "spa") }
            var offers = 0
            val automatic = AutomaticSpokenAudio(this, { true }, { flow(gateway, this) }, { offers++ })
            qualify(automatic); advanceUntilIdle()
            assertEquals(1, gateway.checks)
            assertEquals(if (status == "likely_mismatch") 1 else 0, offers)
            qualify(automatic); advanceUntilIdle(); assertEquals(1, gateway.checks)
            automatic.cancelAndJoin()
        }
    }

    @Test fun missingOptInCapabilityOrConcreteTrackCannotSample() = runTest {
        for (kind in listOf("disabled player", "server opt-out", "old server", "unmapped")) {
            val gateway = Gateway().apply { optedIn = kind != "server opt-out"; available = kind != "old server" }
            val automatic = AutomaticSpokenAudio(this, { kind != "disabled player" },
                { flow(gateway, this) { if (kind == "unmapped") null else 2 } }, { fail("Unavailable check published a hint") })
            qualify(automatic); advanceUntilIdle()
            assertEquals(0, gateway.checks)
            if (kind == "disabled player") assertEquals(0, gateway.optionsReads)
            automatic.cancelAndJoin()
        }
    }

    @Test fun menuRecoveryPauseSourceOrOwnerRetirementJoinsCheckAndSuppressesLateHint() = runTest {
        for (kind in listOf("menu", "recovery", "source", "owner", "pause")) {
            val gateway = Gateway().apply { gate = CompletableDeferred() }
            var eligible = true
            var offers = 0
            val automatic = AutomaticSpokenAudio(this, { eligible }, { flow(gateway, this) }, { offers++ })
            qualify(automatic); runCurrent(); assertEquals(1, gateway.checks)
            if (kind == "owner") gateway.current = false
            eligible = false
            automatic.observe(sample(46_000).copy(playing = kind != "pause"))
            val closing = launch { automatic.cancelAndJoin() }
            runCurrent(); assertFalse(closing.isCompleted)
            gateway.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(closing.isCompleted); assertEquals(0, offers)
            eligible = true; qualify(automatic); advanceUntilIdle(); assertEquals(1, gateway.checks)
        }
    }

    @Test fun refreshedOptOutPreventsAutomaticPostButKeepsAnExplicitButtonAvailable() = runTest {
        val gateway = Gateway().apply { optOutOnRefresh = true }
        val automatic = AutomaticSpokenAudio(this, { true }, { flow(gateway, this) }, { fail("Opted-out automatic result published") })
        qualify(automatic); advanceUntilIdle()
        assertEquals(2, gateway.optionsReads)
        assertEquals(0, gateway.checks)
        automatic.cancelAndJoin()
        val explicit = flow(gateway, this)
        explicit.load()
        assertFalse(explicit.state.value.options!!.autoCheckAudioLanguage)
        assertTrue(explicit.canCheckAudioLanguage)
        explicit.checkAudioLanguage(); advanceUntilIdle()
        assertEquals(1, gateway.checks)
        explicit.close()
    }

    @Test fun menuLeaseRetiresAndJoinsTheActualCheckBeforeCompetingControlsProceed() = runTest {
        val gateway = Gateway().apply { gate = CompletableDeferred() }
        var offers = 0
        lateinit var automatic: AutomaticSpokenAudio
        val menus = PlaybackMenuGate { automatic.cancel() }
        automatic = AutomaticSpokenAudio(this, { !menus.active }, { flow(gateway, this) }, { offers++ })
        qualify(automatic); runCurrent(); assertEquals(1, gateway.checks)
        val lease = menus.acquire()
        val ready = launch { automatic.cancelAndJoin() }
        runCurrent(); assertFalse(ready.isCompleted)
        gateway.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(ready.isCompleted); assertEquals(0, offers)
        assertTrue(menus.active)
        lease.close()
        qualify(automatic); advanceUntilIdle()
        assertEquals(1, gateway.checks) // A canceled attempt is still spent for this active source.
    }

    @Test fun nestedAndRetiredMenusCannotClearAnotherLeaseOrReuseAnOldObservationWindow() = runTest {
        val gateway = Gateway()
        lateinit var automatic: AutomaticSpokenAudio
        val menus = PlaybackMenuGate { automatic.cancel() }
        automatic = AutomaticSpokenAudio(this, { !menus.active }, { flow(gateway, this) }, { })
        for (second in 0..20) automatic.observe(sample(second * 1_000L))
        val audio = menus.acquire()
        val sources = menus.acquire()
        audio.close(); audio.close()
        assertTrue(menus.active)
        for (second in 21..60) automatic.observe(sample(second * 1_000L))
        runCurrent(); assertEquals(0, gateway.optionsReads)
        sources.close(); assertFalse(menus.active)
        for (second in 61..105) automatic.observe(sample(second * 1_000L))
        runCurrent(); assertEquals(0, gateway.checks)
        automatic.observe(sample(106_000)); advanceUntilIdle()
        assertEquals(1, gateway.checks)
        automatic.cancelAndJoin()
    }

    @Test fun languageSuggestionWaitsForExplicitReviewWithoutLoadingOrPreparing() = runTest {
        val gateway = Gateway()
        val assistant = AudioRecoveryAssistant(this, { flow(gateway, this) }, { true })
        assertTrue(assistant.suggest(AudioRecoveryHint.LANGUAGE)); advanceUntilIdle()
        assertEquals(0, gateway.optionsReads); assertEquals(0, gateway.checks)
        assertTrue(audioRecoveryMessage(AudioRecoveryHint.LANGUAGE, assistant.flow!!.state.value).contains("No alternative or report"))
        assistant.dismiss(); advanceUntilIdle()
    }
}
