package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.live.RecordingDerivedObservation
import com.strmr.tv.live.RecordingRules
import com.strmr.tv.live.RecordingProgressWriteObservation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Transient explicit raw-copy handoff, never an imported or offline permission. */
class RecordingDerivedPlayback private constructor(
    val owner: PlaybackSessionContext, val observation: RecordingDerivedObservation, private val now: () -> Long,
    internal val progressReceipt: RecordingProgressWriteObservation? = null,
    internal val progressResource: RecordingProgressResource? = null,
    private val verifiedDeadline: Long? = null,
) {
    @Volatile private var claimed = false
    @Volatile private var closed = false
    private var pendingWatch: Job? = null
    internal val startPositionMs: Long get() = progressReceipt?.data?.positionMs ?: 0L
    fun allowed() = !closed && owner.isCurrent() && observation.ready(now()) && verifiedDeadline?.let { now() < it } != false
    fun canStart() = !claimed && allowed() && progressReceipt?.adoptable(now()) != false
    @Synchronized internal fun claim() {
        if (!canStart()) throw RecordingDerivedRetired()
        claimed = true; pendingWatch?.cancel(); pendingWatch = null
    }
    @Synchronized internal fun retirePending() { if (!claimed) release() }
    fun release() { closed = true; pendingWatch?.cancel(); pendingWatch = null; progressResource?.close() }
    internal fun deny() { release() }
    override fun toString() = "RecordingDerivedPlayback(opaque)"
    companion object {
        internal fun launchProgress(owner: PlaybackSessionContext, verified: RecordingDerivedObservation,
            receipt: RecordingProgressWriteObservation, resource: RecordingProgressResource,
            current: () -> Boolean, now: () -> Long = System::nanoTime): RecordingDerivedPlayback {
            owner.requireCurrent()
            check(current() && owner.profileId == verified.source.profile && receipt.expectedContext == owner.epoch &&
                receipt.original.sameArtifact(verified) && verified.ready(now()) && receipt.adoptable(now()))
            resource.transfer()
            val source = RecordingDerivedPlayback(owner, receipt.original, now, receipt, resource,
                minOf(requireNotNull(verified.deadlineNanos), requireNotNull(receipt.original.deadlineNanos)))
            source.pendingWatch = resource.scope.launch {
                while (isActive && !source.claimed && !source.closed) {
                    if (!source.canStart()) { source.retirePending(); return@launch }
                    delay(100)
                }
            }
            return source
        }
        /** Transfer only from the still-current original explicit play action. */
        internal fun launch(owner: PlaybackSessionContext, observation: RecordingDerivedObservation,
            current: () -> Boolean, now: () -> Long = System::nanoTime): RecordingDerivedPlayback {
            owner.requireCurrent(); check(owner.profileId == observation.source.profile && current() && observation.ready(now())) { "Joined recording changed" }
            return RecordingDerivedPlayback(owner, observation, now)
        }
    }
}

internal fun recordingDerivedSession(source: RecordingDerivedPlayback, url: String): PlayerSession {
    check(source.canStart())
    val value = source.observation.descriptor
    val title = RecordingRules.title(source.observation.source.recording) + " · Joined copy"
    return PlayerSession(PlaybackRequest(DetailsTarget("recording-derived:${value.recordingId}/${value.id}", title, true)),
        url, null, reportsProgress = false, sourceLabel = "Recording · Joined copy", ownership = source.owner,
        recordingDerived = source)
}

/** Both actual activities check before constructing the decoder; claim happens once there. */
fun PlayerSession.recordingDerivedReady() = recordingDerived?.canStart() != false

internal class RecordingDerivedRetired : kotlinx.coroutines.CancellationException("Joined recording changed")

/** The actual Controller uses this integer-only gate before allowing joined-copy playback. */
internal class RecordingProgressStart(private val source: RecordingDerivedPlayback) {
    val positionMs = source.startPositionMs
    private var installed = false
    var started = false; private set
    fun install(setMediaItem: (Long) -> Unit) {
        check(!installed && source.progressReceipt != null && source.allowed())
        require(positionMs in 0..requireNotNull(source.observation.descriptor.durationMs))
        installed = true
        setMediaItem(positionMs)
        check(source.allowed())
    }
    fun ready(seekable: Boolean, actualDurationMs: Long, actualPositionMs: Long): Boolean {
        check(installed && source.allowed())
        if (started) return true
        if (actualDurationMs <= 0) return false
        check(positionMs == 0L || seekable) { "Joined recording cannot seek to the confirmed position" }
        check(positionMs <= actualDurationMs && actualPositionMs == positionMs) { "Joined recording did not accept the exact position" }
        started = true
        return true
    }
}
