package com.strmr.tv.ui.live.multiscreen

import android.content.Intent
import com.strmr.tv.app.App
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.LiveChannel
import com.strmr.tv.live.LiveStream
import com.strmr.tv.live.SavedMultiscreen
import com.strmr.tv.player.PlaybackSessionContext
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Original player handover, not a server content permission. */
class MultiscreenHandover(val channel: LiveChannel, val stream: LiveStream?, val ownership: PlaybackSessionContext)

/** Only an in-memory launch receipt can supply the original owner to an activity. */
class MultiscreenLaunch internal constructor(
    val ownership: PlaybackSessionContext, val handover: MultiscreenHandover?, val resume: SavedMultiscreen?,
)

/** Intent strings correlate with a private once-only object; they never mint playback authority. */
internal class MultiscreenPending {
    private val pending = mutableMapOf<String, MultiscreenLaunch>()
    @Synchronized fun put(launch: MultiscreenLaunch): String {
        launch.ownership.requireCurrent()
        pending.clear()
        return UUID.randomUUID().toString().also { pending[it] = launch }
    }
    @Synchronized fun take(id: String?): MultiscreenLaunch? {
        val launch = id?.let { pending.remove(it) } ?: return null
        return launch.takeIf { it.ownership.isCurrent() }
    }
}

object MultiscreenLauncher {
    const val LAUNCH_ID = "multiscreenLaunch"
    private val pending = MultiscreenPending()

    fun open(handover: MultiscreenHandover? = null, expectedContext: Long = ProfileDataContext.current) {
        try {
            val owner = handover?.ownership ?: PlaybackSessionContext.capture(expectedContext)
            owner.requireCurrent()
            if (owner.epoch != expectedContext) return
            start(MultiscreenLaunch(owner, handover, null))
        } catch (_: CancellationException) { }
    }

    fun resume(saved: SavedMultiscreen, expectedContext: Long = ProfileDataContext.current) {
        try {
            val owner = PlaybackSessionContext.capture(expectedContext)
            start(MultiscreenLaunch(owner, null, saved))
        } catch (_: CancellationException) { }
    }

    private fun start(launch: MultiscreenLaunch) = launch.ownership.use {
        val activity = App.platform.multiscreenActivity ?: return@use
        val id = pending.put(launch)
        App.context.startActivity(Intent(App.context, activity).putExtra(LAUNCH_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun take(id: String?): MultiscreenLaunch? = pending.take(id)
}
