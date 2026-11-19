package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.live.RecordingDerivedObservation
import com.strmr.tv.live.RecordingRules

/** Transient explicit raw-copy handoff, never an imported or offline permission. */
class RecordingDerivedPlayback private constructor(
    val owner: PlaybackSessionContext, val observation: RecordingDerivedObservation, private val now: () -> Long,
) {
    @Volatile private var claimed = false
    @Volatile private var closed = false
    fun allowed() = !closed && owner.isCurrent() && observation.ready(now())
    fun canStart() = !claimed && allowed()
    @Synchronized internal fun claim() { if (!canStart()) throw RecordingDerivedRetired(); claimed = true }
    @Synchronized internal fun retirePending() { if (!claimed) closed = true }
    fun release() { closed = true }
    internal fun deny() { closed = true }
    override fun toString() = "RecordingDerivedPlayback(opaque)"
    companion object {
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
