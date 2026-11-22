package com.strmr.tv.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SpokenAudioPlayback(
    val nowMs: Long, val positionMs: Long, val playing: Boolean, val buffering: Boolean,
    val videoSelected: Boolean, val audioKey: String?, val audioEnabled: Boolean,
    val volume: Float, val speed: Float,
)

/** One automatic opportunity per actual source/player, never from a resume position alone. */
internal class SpokenAudioAutoPolicy(private val afterMs: Long = 45_000) {
    private var attempted = false
    private var first: SpokenAudioPlayback? = null
    private var previous: SpokenAudioPlayback? = null

    fun resetWindow() { first = null; previous = null }

    fun observe(value: SpokenAudioPlayback, eligible: Boolean): Boolean {
        if (attempted) return false
        if (!eligible || !value.playing || value.buffering || !value.videoSelected || value.audioKey == null ||
            !value.audioEnabled || value.volume <= 0 || !value.speed.isFinite() || value.speed <= 0) {
            resetWindow(); return false
        }
        val old = previous
        val elapsed = old?.let { value.nowMs - it.nowMs } ?: 0
        val delta = old?.let { value.positionMs - it.positionMs } ?: 0
        if (old == null || value.audioKey != old.audioKey || elapsed !in 1..2_000 || delta <= 0 ||
            delta > elapsed * value.speed * 1.5 + 1_000) {
            first = value; previous = value; return false
        }
        previous = value
        val start = first ?: value.also { first = it }
        if (value.nowMs - start.nowMs < afterMs || value.positionMs - start.positionMs < afterMs) return false
        attempted = true
        resetWindow()
        return true
    }
}

/** Reuses the explicit pipeline. Match/unknown are silent; a mismatch only offers review. */
internal class AutomaticSpokenAudio(
    private val scope: CoroutineScope,
    private val eligible: () -> Boolean,
    private val createFlow: () -> ReleaseReports,
    private val offerReview: () -> Unit,
    private val policy: SpokenAudioAutoPolicy = SpokenAudioAutoPolicy(),
) {
    private var job: Job? = null

    fun observe(value: SpokenAudioPlayback) {
        if (!eligible() || !value.playing || value.buffering || !value.videoSelected ||
            value.audioKey == null || !value.audioEnabled || value.volume <= 0) { cancel(); return }
        if (!policy.observe(value, true)) return
        job = scope.launch {
            val flow = createFlow()
            try {
                flow.load()
                val options = flow.state.value.options ?: return@launch
                if (!eligible() || !options.autoCheckAudioLanguage || !options.audioLanguageCheckAvailable) return@launch
                val check = flow.checkAudioLanguage(requireAutomaticPreference = true) ?: return@launch
                check.join()
                if (!eligible()) return@launch
                val reply = flow.state.value.audioLanguageCheckResult ?: return@launch
                if (spokenAudioAssessment(reply, options.audioLanguage) == SpokenAudioAssessment.LIKELY_MISMATCH) offerReview()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional observations never block playback or retry themselves. */ }
            finally { withContext(NonCancellable) { flow.stopAudioLanguageCheck(); flow.close() } }
        }
    }

    fun cancel() { policy.resetWindow(); job?.cancel() }
    suspend fun cancelAndJoin() { val original = job; cancel(); original?.cancelAndJoin() }
}
