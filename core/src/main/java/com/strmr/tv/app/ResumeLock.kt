package com.strmr.tv.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.Profile
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal enum class ResumeAction { NONE, PICKER, PIN }

/** Process-local elapsed time; wall-clock or server-clock adjustments do not affect absence. */
internal class ResumeAbsence(private val elapsedMs: () -> Long) {
    private var since: Long? = null

    fun backgrounded() { since = elapsedMs() }

    fun take(): Long? {
        val start = since ?: return null
        since = null
        return (elapsedMs() - start).coerceAtLeast(0)
    }
}

/** Automatic selection exempts the short picker, never a protected profile's PIN recheck. */
internal fun resumeAction(
    awayMs: Long,
    requiresPin: Boolean,
    automatic: Boolean,
    alwaysShowPicker: Boolean,
    profileCount: Int,
): ResumeAction = when {
    !automatic && alwaysShowPicker && profileCount > 1 && awayMs > 2_000L -> ResumeAction.PICKER
    requiresPin && awayMs >= 5 * 60_000L -> ResumeAction.PIN
    else -> ResumeAction.NONE
}

/** A successful setting belongs only to its actual profile instance and original epoch. */
internal class ResumeSettings {
    private class Value(val profile: Any, val epoch: Long, val showPicker: Boolean)
    private var saved: Value? = null

    fun value(profile: Any, epoch: Long): Boolean =
        saved?.takeIf { it.profile === profile && it.epoch == epoch }?.showPicker ?: true

    fun save(profile: Any, epoch: Long, showPicker: Boolean) {
        saved = Value(profile, epoch, showPicker)
    }
}

/** One original producer-owned operation; replacement cannot publish through an older ticket. */
internal class ResumeWork(private val scope: CoroutineScope) {
    private var current: Ticket? = null

    internal inner class Ticket(private val selected: () -> Boolean) {
        internal lateinit var job: Job

        fun check() {
            job.ensureActive()
            if (current !== this || !selected()) throw CancellationException("Resume context changed")
        }

        fun publish(block: () -> Unit) {
            check()
            block()
        }

        internal fun stillSelected(): Boolean = selected()
    }

    fun launch(selected: () -> Boolean, block: suspend Ticket.() -> Unit): Job {
        current?.job?.cancel()
        val ticket = Ticket(selected)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            ticket.check()
            ticket.block()
        }
        ticket.job = job
        current = ticket
        job.start()
        return job
    }

    fun cancelStale() {
        current?.takeUnless { it.stillSelected() }?.job?.cancel()
    }
}

/**
 * Whole-app background return, measured monotonically. The existing player/multiscreen
 * exception consumes the absence without a deferred lock when the viewer later leaves it.
 * That exception includes paused mobile VOD, PiP and requested background audio.
 * Main thread only; this does not stop a decoder or revoke a server unlock token.
 */
internal object ResumeLock {
    /** The picker cannot be backed out of when it came up to recheck a protected profile. */
    var pickRequired = false
    private val preferences = ResumeSettings()
    private var started = 0
    private val absence = ResumeAbsence(SystemClock::elapsedRealtime)
    private lateinit var actions: ResumeWork
    private lateinit var settings: ResumeWork

    private class Owner(val profile: Profile, val epoch: Long) {
        fun isCurrent() = ProfileDataContext.current == epoch &&
            AppSession.phase.value == AppSession.Phase.Ready && AppSession.profile.value === profile
    }

    @Serializable
    private data class Settings(val display: Display? = null) {
        @Serializable
        data class Display(val alwaysShowProfileSelector: Boolean? = null)
    }

    fun install(scope: CoroutineScope) {
        actions = ResumeWork(scope)
        settings = ResumeWork(scope)
        scope.launch {
            ProfileDataContext.changes.collect {
                actions.cancelStale()
                settings.cancelStale()
            }
        }
        (App.context as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started += 1
                if (started == 1) foregrounded(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) {
                    absence.backgrounded()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun owner(): Owner? {
        val profile = AppSession.profile.value ?: return null
        return Owner(profile, ProfileDataContext.current).takeIf { it.isCurrent() }
    }

    /** Captures the profile before scheduling; a late reply cannot change another resume policy. */
    fun refreshSetting() {
        val owner = owner() ?: return
        settings.launch(owner::isCurrent) {
            val reply = try {
                check()
                ApiClient.send<Settings>("GET", "/api/settings", promptOnLock = false,
                    timeoutMs = 10_000, expectedContext = owner.epoch)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            publish { preferences.save(owner.profile, owner.epoch, reply.display?.alwaysShowProfileSelector ?: true) }
        }
    }

    private fun foregrounded(activity: Activity) {
        val away = absence.take() ?: return
        val owner = owner() ?: return
        val onPlayer = activity.javaClass == App.platform.playerActivity ||
            activity.javaClass == App.platform.multiscreenActivity
        if (onPlayer || ProfileUnlock.pinChallenge.value != null) return

        when (resumeAction(away, owner.profile.requiresPin, owner.profile.id == AppSession.autoProfileId,
            preferences.value(owner.profile, owner.epoch), AppSession.profiles.value.size)) {
            ResumeAction.PICKER -> actions.launch(owner::isCurrent) {
                publish { pickRequired = owner.profile.requiresPin }
                check()
                // clear() and the phase change are synchronous inside switchProfile. Its existing
                // later profile reload retains its own API contract, beyond this resume start gate.
                AppSession.switchProfile()
            }
            ResumeAction.PIN -> actions.launch(owner::isCurrent) {
                publish { pickRequired = true }
                check()
                if (AppSession.unlockLockedProfile(owner.profile.id, owner.epoch)) {
                    publish { pickRequired = false }
                }
            }
            ResumeAction.NONE -> Unit
        }
        refreshSetting()
    }
}
