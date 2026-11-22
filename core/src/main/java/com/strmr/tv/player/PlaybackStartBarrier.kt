package com.strmr.tv.player

/** One original start, after both the display gate and current playback checks. */
internal class PlaybackStartBarrier(private val allowed: () -> Boolean, private val start: () -> Unit) {
    private var displayReady = false
    private var started = false

    fun releaseDisplay() {
        displayReady = true
        startIfAllowed()
    }

    fun startIfAllowed() {
        if (!displayReady || started || !allowed()) return
        started = true
        start()
    }
}
