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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal enum class ResumeAction { NONE, PICKER, PIN }

/** Process-local elapsed time; wall-clock or server-clock adjustments do not affect absence. */
internal class ResumeAbsence(private val elapsedMs: () -> Long) {
    private var since: Long? = null

    fun backgrounded(): Boolean {
        if (since != null) return false
        since = elapsedMs()
        return true
    }

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
 * Whole-app background return and deferred player-exit access. Player, paused VOD,
 * PiP, requested background audio and multiscreen are not interrupted by this gate.
 * Returning to browsing first hides protected content, then uses the existing PIN flow.
 * Main thread only; this neither stops a decoder nor revokes a server unlock token.
 */
internal object ResumeLock {
    /** A picker opened for a protected return cannot be cancelled back into browsing. */
    var pickRequired = false
    private val preferences = ResumeSettings()
    private var started = 0
    private val resumed = mutableSetOf<Activity>()
    private val access = DeferredResumeGate(SystemClock::elapsedRealtime)
    val browsingLocked = access.blocked
    private var browsingPage: Activity? = null
    private lateinit var actions: ResumeWork
    private lateinit var pickers: ResumeWork
    private lateinit var settings: ResumeWork

    private class Owner(val profile: Profile, val epoch: Long, val session: Any, val client: String, val source: Any) {
        val binding = ResumeBinding(profile, epoch, session, client, source)
        fun isCurrent() = ProfileDataContext.current == epoch &&
            AppSession.phase.value == AppSession.Phase.Ready && AppSession.profile.value === profile &&
            ApiClient.session === session && ApiClient.profileId == profile.id && Config.clientId == client &&
            (ApiClient.baseUrl to ApiClient.remoteCredential) == source
    }

    @Serializable
    private data class Settings(val display: Display? = null) {
        @Serializable
        data class Display(val alwaysShowProfileSelector: Boolean? = null)
    }

    fun install(scope: CoroutineScope) {
        actions = ResumeWork(scope)
        pickers = ResumeWork(scope)
        settings = ResumeWork(scope)
        scope.launch {
            ProfileDataContext.changes.collect {
                synchronizeOwner()
                pickers.cancelStale()
            }
        }
        scope.launch {
            combine(AppSession.phase, AppSession.profile, ProfileUnlock.pinChallenge) { _, _, _ -> Unit }.collect {
                synchronizeOwner()
            }
        }
        (App.context as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started += 1
                if (isPlayer(activity)) playerEntered(activity)
                else if (started == 1) beforeBrowsing(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                resumed += activity
                if (isPlayer(activity)) playerEntered(activity) else beforeBrowsing(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed -= activity
                browsingStopped(activity)
                // PiP can remain STARTED while the app itself is outside the foreground.
                if (resumed.isEmpty() && !activity.isChangingConfigurations) {
                    access.backgrounded(owner()?.binding)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                browsingStopped(activity)
                if (started == 0 && !activity.isChangingConfigurations) {
                    access.backgrounded(owner()?.binding)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                resumed -= activity
                browsingStopped(activity)
            }
        })
    }

    private fun isPlayer(activity: Activity): Boolean = resumePlaybackExempt(
        activity.javaClass, App.platform.playerActivity, App.platform.multiscreenActivity)

    private fun owner(): Owner? {
        val profile = AppSession.profile.value ?: return null
        val session = ApiClient.session ?: return null
        return Owner(profile, ProfileDataContext.current, session, Config.clientId,
            ApiClient.baseUrl to ApiClient.remoteCredential).takeIf { it.isCurrent() }
    }

    private fun synchronizeOwner() {
        val owner = owner()
        if (owner == null && AppSession.phase.value == AppSession.Phase.Ready) access.ownerUnavailable()
        else access.reconcile(owner?.binding, owner?.profile?.requiresPin == true)
        actions.cancelStale()
        settings.cancelStale()
        // A changed or cancelled shared prompt may need a fresh original-page ticket.
        browsingPage?.takeUnless { it.isFinishing }?.let(::beforeBrowsing)
    }

    /** Explicit profile selection has already completed its established startup/PIN policy. */
    fun profileActivated() {
        access.profileActivated()
        if (owner() == null && AppSession.phase.value == AppSession.Phase.Ready) access.ownerUnavailable()
        browsingPage = null
        if (::actions.isInitialized) actions.cancelStale()
    }

    /** Main calls this before onStart/onResume can expose its existing composition. */
    fun beforeBrowsing(activity: Activity) {
        if (!::actions.isInitialized) return
        browsingPage = activity
        val owner = owner()
        if (owner == null && AppSession.phase.value == AppSession.Phase.Ready) {
            access.ownerUnavailable()
            actions.cancelStale()
            return
        }
        val ticket = access.enter(owner?.binding, activity, false, owner?.profile?.requiresPin == true,
            owner?.profile?.id == AppSession.autoProfileId,
            owner?.let { preferences.value(it.profile, it.epoch) } ?: true, AppSession.profiles.value.size)
        actions.cancelStale()
        if (owner == null || ticket == null) return
        val selected = { owner.isCurrent() && browsingPage === activity && !activity.isFinishing && access.current(ticket, ResumeLock.owner()?.binding) }
        when (ticket.action) {
            ResumeAction.PICKER -> pickers.launch(selected) {
                publish { pickRequired = owner.profile.requiresPin }
                check()
                // The existing picker changes phase synchronously and retains its own later
                // profile reload contract. A phase change alone must not cancel that reload.
                AppSession.switchProfile()
            }
            ResumeAction.PIN -> actions.launch(selected) {
                publish { pickRequired = true }
                check()
                if (AppSession.unlockLockedProfile(owner.profile.id, owner.epoch)) {
                    publish {
                        if (access.complete(ticket, ResumeLock.owner()?.binding)) pickRequired = false
                    }
                } else {
                    // A different shared prompt may still be active. Keep the obligation;
                    // its close event can attach a fresh ticket to the same browsing page.
                    access.release(ticket)
                }
            }
            ResumeAction.NONE -> Unit
        }
        refreshSetting()
    }

    fun browsingStopped(activity: Activity) {
        if (browsingPage === activity) browsingPage = null
        access.leave(activity)
        if (::actions.isInitialized) actions.cancelStale()
    }

    private fun playerEntered(activity: Activity) {
        val owner = owner()
        browsingPage = null
        if (owner == null && AppSession.phase.value == AppSession.Phase.Ready) {
            access.ownerUnavailable()
            actions.cancelStale()
            return
        }
        access.enter(owner?.binding, activity, true, owner?.profile?.requiresPin == true,
            owner?.profile?.id == AppSession.autoProfileId,
            owner?.let { preferences.value(it.profile, it.epoch) } ?: true, AppSession.profiles.value.size)
        actions.cancelStale()
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
}
