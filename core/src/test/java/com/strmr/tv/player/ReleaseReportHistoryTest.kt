package com.strmr.tv.player

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.PrequeueStartResponse
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.ReleaseReportOptions
import com.strmr.tv.api.ReleaseReportReply
import com.strmr.tv.api.SavedReleaseReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseReportHistoryTest {
    private class Gateway : ReleaseReportGateway {
        var supportsReset = true
        var confirmation = true
        var resetError: String? = null
        var failure: Exception? = null
        val submissions = mutableListOf<JsonObject>()
        val removed = mutableListOf<String>()
        val canceled = mutableListOf<String>()
        var polls = 0
        override fun isCurrent() = true
        override suspend fun options(id: String) = ReleaseReportOptions(
            "fixture-version", audioLanguage = "spa", hasEpisode = true, canResetHistory = supportsReset,
        )
        override suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply {
            submissions += body
            failure?.let { throw it }
            return ReleaseReportReply(
                report = if (reporting) SavedReleaseReport("fixture-report") else null,
                replacement = if (body["retry"]!!.jsonPrimitive.boolean) PrequeueStartResponse("replacement") else null,
                historyReset = body["resetHistory"]?.jsonPrimitive?.boolean == true && confirmation,
                historyResetError = resetError,
            )
        }
        override suspend fun status(id: String): PrequeueStatus {
            polls++
            return PrequeueStatus(id, "ready", streamPath = "/fixture/replacement.mkv", duration = 600.0)
        }
        override suspend fun cancel(id: String) { canceled += id }
        override suspend fun undo(id: String) { removed += id }
    }

    @Test fun resetIsOptionalAndLimitedToWrongContent() = runTest {
        var stopped = 0
        val gateway = Gateway()
        val flow = ReleaseReports("playing", gateway, this, { 42_000 }, { _, _, _ -> },
            clientId = "fixture", beforeHistoryReset = { stopped++ })
        flow.load()
        flow.choose(ReleaseProblem.WRONG_TITLE)
        val option = releaseReportChoices(flow, flow.state.value) {}.single { it.title == "Clear saved progress and mark unwatched" }
        assertFalse(option.selected)
        flow.submit(true, false); advanceUntilIdle()
        assertFalse(gateway.submissions.single().containsKey("resetHistory"))
        assertEquals(0, stopped)
        assertFalse(flow.state.value.progressStopped)
        option.onClick()
        assertTrue(flow.state.value.resetHistory)
        flow.choose(ReleaseProblem.LANGUAGE)
        assertFalse(flow.state.value.resetHistory)
        flow.resetHistory(true)
        assertFalse(flow.state.value.resetHistory)
        assertFalse(releaseReportChoices(flow, flow.state.value) {}.any { it.title == option.title })
        flow.choose(ReleaseProblem.WRONG_EPISODE)
        assertTrue(flow.canResetHistory)
        assertFalse(flow.state.value.resetHistory)
        flow.close(); advanceUntilIdle()
    }

    @Test fun olderServersAndFlowsWithoutProgressOwnerDoNotOfferReset() = runTest {
        for (serverSupports in listOf(false, true)) {
            val gateway = Gateway().apply { supportsReset = serverSupports }
            val stopProgress: (suspend () -> Unit)? = if (serverSupports) null else ({ })
            val flow = ReleaseReports("playing", gateway, this, { 0 }, { _, _, _ -> },
                clientId = "fixture", beforeHistoryReset = stopProgress)
            flow.load(); flow.choose(ReleaseProblem.WRONG_TITLE); flow.resetHistory(true)
            assertFalse(flow.canResetHistory)
            assertFalse(flow.state.value.resetHistory)
            assertFalse(releaseReportChoices(flow, flow.state.value) {}.any { it.title == "Clear saved progress and mark unwatched" })
            flow.close()
        }
    }

    @Test fun resetWaitsForActualOldWriteAndUndoDoesNotRestoreHistory() = runTest {
        val oldProgress = ProgressWriteGate { true }
        val releaseWrite = CompletableDeferred<Unit>()
        var finishedWrite = false
        var writeCanceled = false
        val oldWrite = launch {
            oldProgress.write {
                try { releaseWrite.await(); finishedWrite = true }
                finally { writeCanceled = !finishedWrite }
            }
        }
        runCurrent()
        val gateway = Gateway()
        val flow = ReleaseReports("playing", gateway, this, { 42_000 }, { _, _, _ -> },
            clientId = "fixture", beforeHistoryReset = {
                oldProgress.stopNewWrites(); oldProgress.joinStopped()
                assertTrue(finishedWrite)
            })
        flow.load(); flow.choose(ReleaseProblem.WRONG_EPISODE); flow.resetHistory(true)
        flow.submit(true, false); runCurrent()
        assertTrue(flow.state.value.busy)
        assertTrue(flow.state.value.progressStopped)
        assertFalse(oldProgress.accepts)
        assertTrue(gateway.submissions.isEmpty())
        assertFalse(oldWrite.isCompleted)
        assertFalse(writeCanceled)
        releaseWrite.complete(Unit); advanceUntilIdle()
        assertTrue(oldWrite.isCompleted)
        assertFalse(oldWrite.isCancelled)
        assertFalse(writeCanceled)
        assertTrue(gateway.submissions.single()["resetHistory"]!!.jsonPrimitive.boolean)
        assertTrue(flow.state.value.historyReset)
        assertFalse(flow.state.value.historyResetUnconfirmed)
        assertTrue(flow.state.value.message!!.contains("now unwatched"))
        assertTrue(flow.state.value.message!!.contains("paused"))
        assertTrue(releaseReportChoices(flow, flow.state.value) {}.single { it.title == "Undo report" }.detail!!.contains("not undone"))
        flow.undo(); advanceUntilIdle()
        assertEquals(listOf("fixture-report"), gateway.removed)
        assertNull(flow.state.value.reportId)
        assertTrue(flow.state.value.message!!.contains("history reset is not undone"))
        assertFalse(oldProgress.accepts)
        flow.close(); advanceUntilIdle()
    }

    @Test fun canceledCallerCannotHideAnUnfinishedProgressWrite() = runTest {
        val gate = ProgressWriteGate { true }
        val release = CompletableDeferred<Unit>()
        var actualFinished = false
        val original = launch {
            gate.write { withContext(NonCancellable) { release.await(); actualFinished = true } }
        }
        runCurrent()
        original.cancel(); runCurrent()
        gate.discard()
        val joining = launch { gate.joinStopped() }
        runCurrent()
        assertFalse(joining.isCompleted)
        assertFalse(actualFinished)
        release.complete(Unit); advanceUntilIdle()
        assertTrue(original.isCompleted)
        assertTrue(joining.isCompleted)
        assertTrue(actualFinished)
        assertNull(gate.write { fail("Discarded old source wrote again") })
    }

    @Test fun unconfirmedResetKeepsReportUndoAndDoesNotPrepareOrAdopt() = runTest {
        for (error in listOf(null, "history_reset_failed")) {
            val gateway = Gateway().apply { confirmation = false; resetError = error }
            var buffers = 0
            val flow = ReleaseReports("playing", gateway, this, { 42_000 }, { _, _, _ -> fail("Partial failure adopted") },
                clientId = "fixture", beforeHistoryReset = {},
                prepareLocal = { _, _, _ -> buffers++; error("Partial failure buffered") })
            flow.load(); flow.choose(ReleaseProblem.WRONG_TITLE); flow.resetHistory(true)
            flow.submit(true, true); advanceUntilIdle()
            assertEquals("fixture-report", flow.state.value.reportId)
            assertFalse(flow.state.value.historyReset)
            assertTrue(flow.state.value.historyResetUnconfirmed)
            assertTrue(flow.state.value.progressStopped)
            assertFalse(flow.state.value.busy)
            assertNull(flow.state.value.ready)
            assertEquals(0, gateway.polls)
            assertEquals(0, buffers)
            assertEquals(listOf("replacement"), gateway.canceled)
            assertTrue(flow.state.value.message!!.contains("Report saved"))
            assertTrue(flow.state.value.message!!.contains("could not be confirmed"))
            assertFalse(flow.state.value.message!!.contains("now unwatched"))
            flow.undo(); advanceUntilIdle()
            assertEquals(listOf("fixture-report"), gateway.removed)
            assertTrue(flow.state.value.message!!.contains("not undone"))
            flow.close(); advanceUntilIdle()
        }
    }

    @Test fun successfulResetPreservesBufferedReplacementAndFreshProgress() = runTest {
        val gateway = Gateway()
        val oldProgress = ProgressWriteGate { true }
        val replacementProgress = ProgressWriteGate { true }
        var bufferCloses = 0
        var adopted = false
        val buffer = object : PreparedReleaseBuffer {
            override val targetMs = 0L
            override fun isReady() = true
            override suspend fun closeAndJoin() { bufferCloses++ }
        }
        val flow = ReleaseReports("playing", gateway, this, { 42_000 }, { _, _, _ -> fail("Buffer was not transferred") },
            clientId = "fixture", beforeHistoryReset = { oldProgress.stopNewWrites(); oldProgress.joinStopped() },
            prepareLocal = { _, target, _ -> assertEquals(0L, target); buffer },
            adoptLocal = { _, target, wrong, owned ->
                assertEquals(0L, target); assertTrue(wrong); assertSame(buffer, owned); adopted = true
            })
        flow.load(); flow.choose(ReleaseProblem.WRONG_TITLE); flow.resetHistory(true)
        flow.submit(true, true); advanceUntilIdle()
        assertFalse(adopted)
        assertSame(buffer, flow.bufferedPreparation)
        assertTrue(flow.state.value.message!!.contains("history reset remains saved"))
        assertTrue(flow.state.value.message!!.contains("paused"))
        flow.take(); flow.close(); advanceUntilIdle()
        assertTrue(adopted)
        assertEquals(0, bufferCloses)
        assertTrue(gateway.canceled.isEmpty())
        assertNull(oldProgress.write { fail("Old source wrote after correction") })
        var saved = 0
        replacementProgress.write { saved++ }
        assertEquals(1, saved)
    }

    @Test fun progressStopFailureSendsNeitherReportNorReset() = runTest {
        val gateway = Gateway()
        val flow = ReleaseReports("playing", gateway, this, { 0 }, { _, _, _ -> }, clientId = "fixture",
            beforeHistoryReset = { error("Synthetic progress completion failure") })
        flow.load(); flow.choose(ReleaseProblem.WRONG_TITLE); flow.resetHistory(true)
        flow.submit(true, false); advanceUntilIdle()
        assertTrue(gateway.submissions.isEmpty())
        assertNull(flow.state.value.reportId)
        assertFalse(flow.state.value.historyReset)
        assertTrue(flow.state.value.progressStopped)
        assertTrue(flow.state.value.message!!.contains("No report or history reset was sent"))
        flow.close(); advanceUntilIdle()
    }

    @Test fun unknownPostResultDoesNotClaimResetSuccessOrResumeOldProgress() = runTest {
        val gateway = Gateway().apply { failure = IllegalStateException("Synthetic connection lost") }
        val flow = ReleaseReports("playing", gateway, this, { 0 }, { _, _, _ -> }, clientId = "fixture", beforeHistoryReset = {})
        flow.load(); flow.choose(ReleaseProblem.WRONG_EPISODE); flow.resetHistory(true)
        flow.submit(true, false); advanceUntilIdle()
        assertEquals(1, gateway.submissions.size)
        assertFalse(flow.state.value.historyReset)
        assertTrue(flow.state.value.historyResetUnconfirmed)
        assertTrue(flow.state.value.progressStopped)
        assertFalse(flow.state.value.message!!.contains("Report saved"))
        assertTrue(flow.state.value.message!!.contains("could not confirm", ignoreCase = true))
        assertTrue(flow.state.value.message!!.contains("paused"))
        flow.close(); advanceUntilIdle()
    }

    @Test fun wireModelsRemainCompatibleAndReadHistoryResetResults() {
        val oldOptions = ApiJson.decodeFromString<ReleaseReportOptions>("""{"sourceVersion":"fixture"}""")
        assertFalse(oldOptions.canResetHistory)
        assertFalse(ApiJson.decodeFromString<ReleaseReportReply>("{}").historyReset)
        val options = ApiJson.decodeFromString<ReleaseReportOptions>("""{"sourceVersion":"fixture","canResetHistory":true}""")
        assertTrue(options.canResetHistory)
        assertTrue(ApiJson.decodeFromString<ReleaseReportReply>("""{"historyReset":true}""").historyReset)
        val partial = ApiJson.decodeFromString<ReleaseReportReply>("""{"report":{"id":"r"},"historyReset":false,"historyResetError":"history_reset_failed"}""")
        assertEquals("r", partial.report!!.id)
        assertFalse(partial.historyReset)
        assertEquals("history_reset_failed", partial.historyResetError)
    }
}
