package com.strmr.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.Closeable

/** Controller-local leases: one departing menu cannot clear another menu's observation fence. */
internal class PlaybackMenuGate(private val retireAutomatic: () -> Unit) {
    private val leases = mutableSetOf<Any>()
    val active: Boolean get() = leases.isNotEmpty()
    fun acquire(): Closeable {
        val token = Any()
        leases.add(token)
        retireAutomatic()
        return Closeable { if (leases.remove(token)) retireAutomatic() }
    }
}

/** Both actual UI hosts wait for cancellation cleanup before showing competing controls. */
@Composable
fun rememberPlaybackMenuReady(controller: PlayerController, menu: Any?): Boolean {
    var ready by remember(controller, menu) { mutableStateOf(menu == null) }
    DisposableEffect(controller, menu) {
        val lease = if (menu != null) controller.openPlaybackMenu() else null
        onDispose { lease?.close() }
    }
    LaunchedEffect(controller, menu) {
        if (menu != null) controller.awaitAutomaticAudioRetirement()
        ready = true
    }
    return ready
}
