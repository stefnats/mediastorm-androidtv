package com.strmr.tv.player

import java.nio.ByteBuffer

internal enum class ObservedPcmEncoding(val bytesPerSample: Int) { PCM16(2), FLOAT32(4) }

/** Counters only: decoded input is neither retained nor proof of audible device output. */
internal data class DecodedAudioSnapshot(
    val generation: Long,
    val sampleRate: Int,
    val frames: Long,
    val zeroFrames: Long,
    val breaks: Long,
    val lastBufferMs: Long?,
)

/** Renderer callbacks and the player clock exchange bounded, copied observations. */
internal class DecodedAudioObservation {
    private var generation = 0L
    private var sampleRate = 0
    private var channels = 0
    private var encoding: ObservedPcmEncoding? = null
    private var closed = false
    private var frames = 0L
    private var zeroFrames = 0L
    private var breaks = 0L
    private var lastBufferMs: Long? = null
    private var sampleBits = 0
    private var sampleBytes = 0
    private var frameSamples = 0
    private var frameZero = true

    @Synchronized fun configure(rate: Int, count: Int, value: ObservedPcmEncoding?) {
        restart()
        sampleRate = rate.takeIf { !closed && value != null && it in 8_000..192_000 && count in 1..8 } ?: 0
        channels = if (sampleRate > 0) count else 0
        encoding = value.takeIf { sampleRate > 0 }
    }

    @Synchronized fun restart() {
        generation++
        frames = 0; zeroFrames = 0; breaks = 0; lastBufferMs = null
        sampleBits = 0; sampleBytes = 0; frameSamples = 0; frameZero = true
    }

    @Synchronized fun close() { closed = true; configure(0, 0, null) }
    @Synchronized fun token(): Long = generation
    @Synchronized fun snapshot() = DecodedAudioSnapshot(generation, sampleRate, frames, zeroFrames, breaks, lastBufferMs)

    /** [start, end) is the delegate's newly consumed range, including partial sample writes. */
    @Synchronized fun consumed(buffer: ByteBuffer, start: Int, end: Int, token: Long, nowMs: Long) {
        val pcm = encoding ?: return
        if (closed || token != generation || end == start) return
        if (start < 0 || end < start || end > buffer.limit() || end - start > MAX_CALLBACK_BYTES || nowMs < 0 || lastBufferMs?.let { nowMs < it } == true) {
            configure(0, 0, null)
            return
        }
        // PCM byte order is little endian, independently of the caller's ByteBuffer order.
        for (index in start until end) {
            sampleBits = sampleBits or ((buffer.get(index).toInt() and 255) shl (8 * sampleBytes))
            sampleBytes++
            if (sampleBytes != pcm.bytesPerSample) continue
            if (pcm == ObservedPcmEncoding.FLOAT32 && (sampleBits and 0x7f800000) == 0x7f800000) {
                configure(0, 0, null)
                return
            }
            val zero = if (pcm == ObservedPcmEncoding.FLOAT32) (sampleBits and 0x7fffffff) == 0 else sampleBits == 0
            frameZero = frameZero && zero
            sampleBits = 0; sampleBytes = 0; frameSamples++
            if (frameSamples != channels) continue
            frames++
            if (frameZero) zeroFrames++ else { zeroFrames = 0; breaks++ }
            frameSamples = 0; frameZero = true
        }
        lastBufferMs = nowMs
    }

    companion object { const val MAX_CALLBACK_BYTES = 1_048_576 }
}

internal data class DecodedAudioPlayback(
    val nowMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val eligible: Boolean,
    val playing: Boolean,
    val buffering: Boolean,
    val videoSelected: Boolean,
    val audioGroup: Any?,
    val audioIndex: Int,
    val audioEnabled: Boolean,
    val volume: Float,
    val speed: Float,
)

/** A possible intentional silence, once per playback; never an automatic file diagnosis. */
internal class DecodedAudioSilencePolicy {
    private var offered = false
    private var group: Any? = null
    private var index = -1
    private var generation = -1L
    private var breaks = -1L
    private var settledSince: Long? = null
    private var since: Long? = null
    private var startPosition = 0L
    private var startFrames = 0L
    private var previousMs: Long? = null
    private var previousPosition = 0L

    fun reset() { settledSince = null; since = null; previousMs = null }

    fun observe(sample: DecodedAudioSnapshot, value: DecodedAudioPlayback): Boolean {
        val elapsed = previousMs?.let { value.nowMs - it }
        val movement = value.positionMs - previousPosition
        val valid = !offered && value.eligible && value.playing && !value.buffering && value.videoSelected &&
            value.audioGroup != null && value.audioIndex >= 0 && value.audioEnabled && value.volume.isFinite() && value.volume > 0f &&
            value.speed == 1f && value.positionMs >= SETTLE_MS && value.durationMs > value.positionMs &&
            value.durationMs - value.positionMs >= END_GUARD_MS && sample.sampleRate > 0 &&
            sample.lastBufferMs?.let { value.nowMs - it in 0..FRESH_MS } == true
        if (!valid) { reset(); return false }
        val changed = group !== value.audioGroup || index != value.audioIndex || generation != sample.generation || breaks != sample.breaks
        if (changed || elapsed == null || elapsed !in 0..FRESH_MS || movement < 0 || elapsed > 0 && movement == 0L || movement > elapsed + 1_000) reset()
        group = value.audioGroup; index = value.audioIndex; generation = sample.generation; breaks = sample.breaks
        previousMs = value.nowMs; previousPosition = value.positionMs
        val settled = settledSince ?: value.nowMs.also { settledSince = it }
        if (value.nowMs - settled < SETTLE_MS) return false
        val start = since ?: value.nowMs.also { since = it; startPosition = value.positionMs; startFrames = sample.frames }
        val activeMs = value.nowMs - start
        if (activeMs < SILENCE_MS || value.positionMs - startPosition < activeMs - 2_000) return false
        val requiredFrames = sample.sampleRate.toLong() * SILENCE_MS / 1_000
        if (sample.zeroFrames < requiredFrames || sample.frames - startFrames < requiredFrames) return false
        offered = true
        reset()
        return true
    }

    companion object {
        const val SILENCE_MS = 45_000L
        const val SETTLE_MS = 5_000L
        const val END_GUARD_MS = 60_000L
        const val FRESH_MS = 2_000L
    }
}
