package com.strmr.tv.update

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Process-lifetime listeners and the actual whole-app activity counter. Main thread only. */
internal class ForegroundEvents(private val entered: () -> Unit) {
    private class Listener(val owner: Any, var callback: () -> Unit)
    private val leftListeners = mutableListOf<Listener>()
    private var started = 0
    val visible: Boolean get() = started > 0

    /** Reinstalling one consumer replaces only that consumer's callback, without duplicates. */
    fun onLeft(owner: Any, callback: () -> Unit) {
        val existing = leftListeners.firstOrNull { it.owner === owner }
        if (existing != null) existing.callback = callback
        else leftListeners += Listener(owner, callback)
    }

    fun activityStarted() {
        started += 1
        if (started == 1) entered()
    }

    fun activityStopped(changingConfigurations: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !changingConfigurations) {
            // A callback may replace a registration. This event retains its original snapshot.
            leftListeners.map { it.callback }.forEach { it() }
        }
    }
}

/**
 * Whether any of the app's activities is on screen. The player is its own activity, so the
 * main one stopping does not mean the viewer left the app; the count reaching zero does.
 */
object AppForeground : Application.ActivityLifecycleCallbacks {
    private val events = ForegroundEvents { onEntered?.invoke() }
    val visible: Boolean get() = events.visible
    /** Called on the main thread when the first activity starts: launch, or back from home / sleep. */
    var onEntered: (() -> Unit)? = null

    /** Stable process-lifetime owners register independently, including repeated updater starts. */
    fun onLeft(owner: Any, callback: () -> Unit) = events.onLeft(owner, callback)

    fun register(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) = events.activityStarted()
    override fun onActivityStopped(activity: Activity) = events.activityStopped(activity.isChangingConfigurations)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
