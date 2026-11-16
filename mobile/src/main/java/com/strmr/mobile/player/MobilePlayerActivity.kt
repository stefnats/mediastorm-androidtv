package com.strmr.mobile.player

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.strmr.mobile.player.ui.PlayerScreen
import com.strmr.mobile.player.ui.PostPlayScreen
import com.strmr.mobile.ui.theme.MediaStormTheme
import kotlinx.coroutines.CancellationException
import com.strmr.tv.api.vodDenial
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.playbackSettings
import com.strmr.tv.app.AppLog
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.player.RecordingPartHandoff
import com.strmr.tv.player.PlayerController
import com.strmr.tv.player.recordingPartReady
import com.strmr.tv.player.canOpenPlayer
import com.strmr.tv.player.SubtitleStyleStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The phone and tablet player: [PlayerController] on [PlaybackCoordinator.pending], full screen
 * with the system bars hidden, with the same lifecycle contract as the TV player (open,
 * started, closed with the last progress report; Up Next and Switch Source close it and start
 * the next playback).
 *
 * Unlike the TV player, leaving isn't the end: the viewer leaving while it plays takes it into
 * picture in picture, and with Background Audio on it keeps playing behind other apps (the
 * media notification controls it); otherwise it pauses (a live channel closes). Only Back, the close button, the end
 * of playback or closing the PiP window finish it.
 */
@OptIn(UnstableApi::class)
class MobilePlayerActivity : ComponentActivity() {
    private var controller: PlayerController? = null
    private var recordingNext:RecordingPartHandoff?=null
    private var pip: PictureInPicture? = null
    private val inPip = mutableStateOf(false)
    /** Another title was played over this one: it starts once this player has let go. */
    private var relaunch = false

    private val tablet: Boolean get() = resources.configuration.smallestScreenWidthDp >= 600

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val session = PlaybackCoordinator.pending
        if (session == null || !session.canOpenPlayer() || !session.recordingPartReady()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        applyOrientation()
        PlaybackCoordinator.playerOpen = true
        val c = PlayerController(this, session) { }
        controller = c
        onBackPressedDispatcher.addCallback(this) {c.cancelRecordingParts();finish()}
        PlayerMediaSession.start(this, c)
        val p = PictureInPicture(this, c).also { it.register() }
        pip = p
        // The PiP window's play / pause action, auto-enter and aspect follow the player.
        lifecycleScope.launch {
            combine(c.isPlaying, c.videoAspect, c.error) { _, _, _ -> }.collect { p.update() }
        }
        lifecycleScope.launch {
            snapshotFlow { PlayerPrefs.orientation }.collect { applyOrientation() }
        }
        setContent {
            MediaStormTheme {
                val postPlay by c.postPlay.collectAsState()
                LaunchedEffect(postPlay) { if (postPlay != null) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val ended by c.ended.collectAsState()
                val next by c.advanceTo.collectAsState()
                val switch by c.switchTo.collectAsState()
                val partNext = c.nextRecordingPart?.collectAsState()?.value
                LaunchedEffect(ended,partNext) { if (ended) {recordingNext=partNext;finish()} }
                LaunchedEffect(Unit) {
                    try {
                        val owner=requireNotNull(session.ownership)
                        val style=ApiClient.playbackSettings(owner.epoch).subtitleStyle
                        owner.use {SubtitleStyleStore.adopt(style)}
                    } catch(cancelled:CancellationException) {throw cancelled}
                    catch(error:Exception) {if(error.vodDenial!=null)c.cancelRecordingParts()}
                }
                // Up Next: close this episode (saved as finished) and start the next over its loading art.
                LaunchedEffect(next) {
                    next?.takeIf {session.canOpenPlayer()}?.let {
                        finish()
                        PlaybackCoordinator.play(it,expectedContext=requireNotNull(session.ownership).epoch)
                    }
                }
                // Switch Source: close this stream (saved at its position) and resume on the other.
                LaunchedEffect(switch) {
                    switch?.takeIf {session.canOpenPlayer()}?.let {
                        finish()
                        PlaybackCoordinator.play(it.request, it.source, carried = it.carried, resolved = it.resolved, prepared = it.prepared,expectedContext=requireNotNull(session.ownership).epoch)
                    }
                }
                if (postPlay != null && !inPip.value) PostPlayScreen(c) { finish() }
                else PlayerScreen(
                    controller = c,
                    inPip = inPip.value,
                    tablet = tablet,
                    onExit = { c.cancelRecordingParts();finish() },
                    onPictureInPicture = { p.enter(); Unit }.takeIf { p.supported },
                )
            }
        }
    }

    /** A title played from elsewhere while this one is open (paused behind the app, say): hand over to it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (PlaybackCoordinator.pending?.let {it.canOpenPlayer() && it.recordingPartReady()}==true && controller != null) {
            AppLog.i("Player", "new playback over the open player; restarting")
            relaunch = true
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!relaunch) PlaybackCoordinator.playerStarted()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Below Android 12 there's no auto-enter: leaving while it plays enters picture in picture here. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = controller ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && c.player.playWhenReady && c.error.value == null) pip?.enter()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // The PiP window was closed (not expanded): the activity is left stopped, so that's the end.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            AppLog.i("Player", "picture in picture closed")
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        val c = controller ?: return
        if (isFinishing || isInPictureInPictureMode) return
        // In the background: audio carries on only when the viewer asked for it. A paused live
        // channel is no use to come back to (and holds a tuner): it closes, as on the TV.
        when {
            PlayerPrefs.backgroundAudio && c.player.playWhenReady -> AppLog.i("Player", "background audio: playing on")
            c.session.isLive -> finish()
            else -> c.player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pip?.unregister()
        pip = null
        // Before the player goes: the notification and media buttons let go of it.
        PlayerMediaSession.stop(this)
        val saving = controller?.release()
        val hadController = controller != null
        controller = null
        if (hadController) {
            PlaybackCoordinator.playerOpen = false
            if (isFinishing) PlaybackCoordinator.playerClosed(saving)
        }
        recordingNext?.let(PlaybackCoordinator::continueRecordingParts)
        recordingNext=null
        if (relaunch) {
            relaunch = false
            startActivity(Intent(applicationContext, MobilePlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun applyOrientation() {
        requestedOrientation = playerOrientation(tablet, PlayerPrefs.orientation)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
