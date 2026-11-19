package com.strmr.tv.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ephemeral original authority; none of these values are persisted or logged. */
internal class ResumeBinding(
    val profile: Any,
    val epoch: Long,
    val session: Any,
    val client: String,
    val source: Any,
) {
    fun same(other: ResumeBinding): Boolean = profile === other.profile && epoch == other.epoch &&
        session === other.session && client == other.client && source == other.source
}

/** Exact platform activities retain playback regardless of pause, PiP or audio state. */
internal fun resumePlaybackExempt(activity: Class<*>, player: Class<*>, multiscreen: Class<*>?): Boolean =
    activity == player || activity == multiscreen

/**
 * The production lifecycle gate: player returns retain a PIN obligation without
 * stopping playback. A browsing page is blocked synchronously before PIN work starts.
 * All operations run on the main thread; the supplied clock is monotonic.
 */
internal class DeferredResumeGate(elapsedMs: () -> Long) {
    private val absence = ResumeAbsence(elapsedMs)
    private class Demand(val owner: ResumeBinding, val action: ResumeAction, val origin: Any)
    internal class Ticket internal constructor(
        val owner: ResumeBinding,
        val action: ResumeAction,
        val page: Any,
        val origin: Any,
        internal val demand: Any,
    )
    private var pending: Demand? = null
    private var selectedPage: Any? = null
    private var lastSurface: Any? = null
    private var lastWasPlayer = false
    private var backgroundOwner: ResumeBinding? = null
    private var backgroundPage: Any? = null
    private var backgroundWasPlayer = false
    private var active: Ticket? = null
    private val _blocked = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    fun backgrounded(owner: ResumeBinding?) {
        if (absence.backgrounded()) {
            backgroundOwner = owner
            backgroundPage = lastSurface
            backgroundWasPlayer = lastWasPlayer
        }
    }

    /** Called for every player/browsing entry, including Main beside a live PiP. */
    fun enter(
        owner: ResumeBinding?,
        page: Any,
        player: Boolean,
        requiresPin: Boolean,
        automatic: Boolean,
        alwaysShowPicker: Boolean,
        profileCount: Int,
    ): Ticket? {
        val away = absence.take()
        val original = backgroundOwner
        val origin = backgroundPage ?: page
        val cameFromPlayer = backgroundWasPlayer
        backgroundOwner = null
        backgroundPage = null
        backgroundWasPlayer = false
        reconcile(owner, requiresPin)
        lastSurface = page
        lastWasPlayer = player
        if (owner == null) return null
        if (requiresPin && away != null && away >= 5 * 60_000L && (player || cameFromPlayer) && pending == null) {
            // Preserve the original binding when current; changed authority receives
            // a new PIN obligation, never permission from the old response.
            pending = Demand(original?.takeIf { it.same(owner) } ?: owner, ResumeAction.PIN, origin)
        }
        if (player) {
            selectedPage = null
            active = null
            _blocked.value = pending != null
            return null
        }
        selectedPage = page
        val action = pending?.action ?: away?.let {
            resumeAction(it, requiresPin, automatic, alwaysShowPicker, profileCount)
        } ?: ResumeAction.NONE
        if (action == ResumeAction.NONE) {
            _blocked.value = false
            return null
        }
        val demand = pending ?: Demand(owner, action, page).also { pending = it }
        _blocked.value = true
        if (active?.let { it.demand === demand && it.page === page && it.owner.same(owner) } == true) return null
        return Ticket(demand.owner, demand.action, page, demand.origin, demand).also { active = it }
    }

    /** A new authority never adopts a PIN result or permission from its predecessor. */
    fun reconcile(owner: ResumeBinding?, requiresPin: Boolean) {
        val old = pending
        if (owner == null) {
            pending = null
            active = null
            selectedPage = null
            _blocked.value = false
        } else if (old != null && !old.owner.same(owner)) {
            pending = if (requiresPin) Demand(owner, ResumeAction.PIN, old.origin) else null
            active = null
            _blocked.value = pending != null
        }
    }

    /** Ready without a capturable current authority must not expose the old browsing tree. */
    fun ownerUnavailable() {
        selectedPage = null
        active = null
        _blocked.value = true
    }

    fun leave(page: Any) {
        if (selectedPage === page) {
            selectedPage = null
            active = null
        }
    }

    fun release(ticket: Ticket) {
        if (active === ticket) {
            selectedPage = null
            active = null
        }
    }

    fun current(ticket: Ticket, owner: ResumeBinding?): Boolean = owner != null && active === ticket &&
        selectedPage === ticket.page && pending === ticket.demand && ticket.owner.same(owner)

    fun complete(ticket: Ticket, owner: ResumeBinding?): Boolean {
        if (!current(ticket, owner)) return false
        pending = null
        active = null
        _blocked.value = false
        return true
    }

    /** Only the established explicit profile activation calls this, after its own PIN flow. */
    fun profileActivated() {
        absence.take()
        pending = null
        active = null
        selectedPage = null
        lastSurface = null
        lastWasPlayer = false
        backgroundOwner = null
        backgroundPage = null
        backgroundWasPlayer = false
        _blocked.value = false
    }
}
