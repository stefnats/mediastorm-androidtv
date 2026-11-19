package com.strmr.tv.ui.recordings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.player.RecordingDerivedPlayback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** One original tools producer, independent of replacement list instances during equal polls. */
class RecordingDerivedState(
    private val scope: CoroutineScope, private val context: Long,
    private val selected: () -> Recording?, private val profile: () -> String,
    private val adult: () -> Boolean, private val pageCurrent: () -> Boolean,
    private val gateway: RecordingDerivedGateway = ApiRecordingDerivedGateway,
    private val now: () -> Long = System::nanoTime,
    private val play: (RecordingDerivedPlayback) -> Boolean = PlaybackCoordinator::playJoinedRecording,
) {
    private class Producer(val source: RecordingDerivedSource) { var retired = false }
    private var producer: Producer? = null
    private var request: Job? = null
    private var expiry: Job? = null
    private var open = true
    private var observation by mutableStateOf<RecordingDerivedObservation?>(null)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    private fun current(value: Producer) = runCatching {
        open && value === producer && !value.retired && scope.coroutineContext[Job]?.isActive != false &&
            context == ProfileDataContext.current && pageCurrent() && adult() && profile() == value.source.profile &&
            value.source.matches(selected())
    }.getOrDefault(false)
    fun eligible(recording: Recording): Boolean = runCatching {
        open && pageCurrent() && context == ProfileDataContext.current && adult() && selected() === recording &&
            RecordingDerivedSource.capture(recording, profile()) != null
    }.getOrDefault(false)
    fun ready(recording: Recording): Boolean = producer?.let { eligible(recording) && current(it) && observation?.ready(now()) == true } == true
    fun label(recording: Recording): String? = producer?.let { if (eligible(recording) && current(it)) observation?.let { if (it.descriptor.state == "ready" && !it.ready(now())) "Joined copy expired. Read status or prepare explicitly." else it.descriptor.label() } ?: message else null }
    fun observeCurrent(recording: Recording?) { producer?.let { if (!it.source.matches(recording) || !current(it)) retire() } }
    private val contextJob = scope.launch {
        ProfileDataContext.changes.collect { if (it != context) retire() }
    }
    fun retire() {
        producer?.retired = true; producer = null
        request?.cancel(); request = null; expiry?.cancel(); expiry = null
        observation = null; busy = false; message = null
    }
    fun dispose() { retire(); open = false; contextJob.cancel() }
    private fun original(recording: Recording): Producer? {
        if (!eligible(recording)) return null
        producer?.let { if (current(it)) return it }
        retire()
        return RecordingDerivedSource.capture(recording, profile())?.let { Producer(it).also { producer = it } }
    }
    private fun check(value: Producer) { if (!current(value)) throw CancellationException("Joined recording tools changed") }
    private fun present(value: Producer, result: RecordingDerivedObservation) {
        check(value); expiry?.cancel(); observation = result; message = result.descriptor.label()
        val deadline = result.deadlineNanos ?: return
        expiry = scope.launch {
            while (current(value) && observation === result) {
                val remaining = deadline - now()
                if (remaining <= 0) { observation = null; message = "Joined copy expired. Read status or prepare explicitly."; return@launch }
                delay((remaining / 1_000_000L + 1).coerceAtLeast(1))
            }
        }
    }
    fun read(recording: Recording, prepare: Boolean) {
        if (busy) return
        val value = original(recording) ?: return
        busy = true; message = if (prepare) "Preparing joined copy…" else "Reading joined-copy status…"
        val previous = observation
        observation = null; expiry?.cancel(); expiry = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive(); check(value)
                val result = gateway.read(value.source, prepare, context).constrain(previous)
                currentCoroutineContext().ensureActive(); check(value); present(value, result)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (current(value)) { observation = null; message = "Joined-copy request did not finish. Read status or retry explicitly." } }
            finally { if (producer === value && request === currentCoroutineContext()[Job]) { busy = false; request = null } }
        }
        request = job; job.start()
    }
    fun playFromStart(recording: Recording) {
        if (busy || !ready(recording)) return
        val value = requireNotNull(producer); val original = requireNotNull(observation)
        val owner = PlaybackSessionContext.capture(context)
        busy = true; message = "Checking the original joined copy…"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var handoff: RecordingDerivedPlayback? = null
            try {
                currentCoroutineContext().ensureActive(); check(value); owner.requireCurrent()
                val checked = gateway.read(value.source, false, context).constrain(original)
                currentCoroutineContext().ensureActive(); check(value); owner.requireCurrent()
                require(original.sameArtifact(checked) && checked.ready(now()))
                handoff = RecordingDerivedPlayback.launch(owner, checked, { current(value) && observation === original }, now)
                observation = null; expiry?.cancel(); expiry = null
                message = if (play(requireNotNull(handoff))) "Opening joined copy from start…" else "Joined copy could not be opened. Read status again."
            } catch (error: CancellationException) { handoff?.retirePending(); throw error }
            catch (_: Exception) { handoff?.retirePending(); if (current(value)) { observation = null; message = "Joined copy changed or is unavailable. Read status or prepare explicitly." } }
            finally { if (producer === value && request === currentCoroutineContext()[Job]) { busy = false; request = null } }
        }
        request = job; job.start()
    }
}
