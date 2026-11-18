package com.strmr.tv.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.AppLog
import com.strmr.tv.app.AppSession
import com.strmr.tv.api.Profile
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.SetAsideStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A place on Home: a row of cards, or the place a row holds while it loads or after it failed. */
sealed interface HomeSlot {
    val id: String
    val title: String

    data class Shelf(val shelf: HomeShelf) : HomeSlot {
        override val id get() = shelf.id
        override val title get() = shelf.title
    }

    data class Loading(override val id: String, override val title: String) : HomeSlot
    data class Failed(override val id: String, override val title: String) : HomeSlot
}

/**
 * Home's rows, built from the profile's `homeShelves` config: every enabled shelf the app
 * knows, in the configured order, each loaded from its own source. Rows that come back empty
 * are left out, as on the Expo TV home; a row still loading, or that failed with nothing to
 * show, keeps its place as a placeholder. One store per Home view ([view]). Main thread only.
 */
class HomeStore internal constructor(
    originalScope: CoroutineScope, val view: String, val dataContext: Long, private val access: HomeStoreAccess,
) {
    constructor(scope: CoroutineScope, view: String = HomeViews.ALL, expectedContext: Long = ProfileDataContext.current) : this(scope, view, expectedContext, ServerHomeStoreAccess)

    private val ownerJob = SupervisorJob(originalScope.coroutineContext[Job])
    private val scope = CoroutineScope(originalScope.coroutineContext + ownerJob)
    val isCurrent: Boolean get() = ownerJob.isActive && dataContext == ProfileDataContext.current

    private suspend fun check(gen: Int? = null) {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(dataContext)
        if (!ownerJob.isActive || gen != null && gen != generation) throw CancellationException("Home load retired")
    }

    /** Actual work is a child of the original store, and awaiting caller cancellation also cancels it. */
    private suspend fun <T> owned(block: suspend () -> T): T {
        check()
        val child = this.scope.async(start = CoroutineStart.UNDISPATCHED) { check(); block().also { check() } }
        try { return child.await() }
        finally { if (!currentCoroutineContext().isActive) child.cancel() }
    }
    private var slotState by mutableStateOf<List<HomeSlot>>(emptyList())
    var slots: List<HomeSlot>
        get() = if (isCurrent) slotState else emptyList()
        private set(value) { slotState = value }
    var isLoading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    private var settingState by mutableStateOf<HomeSettings?>(null)
    var settings: HomeSettings?
        get() = if (isCurrent) settingState else null
        private set(value) { settingState = value }

    private var plan: List<PlannedShelf> = emptyList()
    private val rows = mutableMapOf<String, HomeShelf>()
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val completions = mutableMapOf<String, Job>()
    /** The manifest revision of the last full load; a different one means reload. */
    private var revision: String? = null
    private var generation = 0
    private var activeLoad: Job? = null
    private val prefills = mutableListOf<Job>()
    private var terminalRun = false
    /** Full loads and staleness checks running: overlapping triggers (re-entering Home after playing) skip. */
    private var loadsRunning = 0
    private var checksRunning = 0
    /** Rows show as they arrive once the page is up; before that it waits on the top row. */
    private var revealed = false
    /** Calendar rows waiting on a server calendar still being built: they keep loading and retry. */
    private val calendarPending = mutableSetOf<String>()
    private var calendarRetries = 0
    private var calendarRetry: Job? = null

    init {
        this.scope.launch {
            ProfileDataContext.changes.collect {
                if (it != dataContext) {
                    slotState = emptyList(); settingState = null
                    isLoading = false; loadError = "Profile changed. Reopen Home."
                    ownerJob.cancel()
                }
            }
        }
        this.scope.launch {
            check()
            com.strmr.tv.ratings.TitleRatingChanges.generation.drop(1).collectLatest {
                // A shelf requested before the vote may still be loading.
                // Refresh after it settles instead of losing this invalidation.
                while (loadsRunning > 0 || checksRunning > 0) delay(50)
                refreshIfStale()
            }
        }
        // Hiding, showing or reordering a row in Settings › Home Layout republishes.
        scope.launch { check(); snapshotFlow { access.layoutIdentity() }.collect { publish() } }
        // Hiding or unhiding a title drops it from, or returns it to, every row.
        scope.launch { check(); snapshotFlow { HiddenItemsStore.revision to HiddenItemsStore.items }.collect { publish() } }
    }

    /** Reloads the config and every row. Rows already shown stay until their new cards arrive. */
    suspend fun load() = owned {
        val job = currentCoroutineContext()[Job]!!
        activeLoad?.takeIf { it !== job }?.cancel()
        activeLoad = job
        loadsRunning++
        try { loadNow() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (isCurrent && activeLoad === job) {
                if (error.vodDenial != null) retireDeniedRun()
                loadError = error.vodDenial?.message ?: "Unable to load Home. Try again."
                isLoading = false
            }
        } finally {
            loadsRunning--
            if (activeLoad === job) activeLoad = null
        }
    }

    /** Loads unless a load is already running (opening Home and the playback rows' reload both ask). */
    suspend fun ensureLoaded() {
        check()
        if (loadsRunning == 0) load()
    }

    private suspend fun loadNow() {
        check()
        val gen = ++generation
        terminalRun = false
        retirePrefills()
        completions.values.forEach { it.cancel() }; completions.clear()
        calendarRetry?.cancel()
        calendarRetries = 0
        prefills += scope.launch { background(gen) { access.hidden(dataContext) } }
        prefills += scope.launch { background(gen) { access.aside(dataContext) } }
        val (loaded, rev) = coroutineScope {
            val revision = async { check(gen); access.revision(dataContext).also { check(gen) } }
            val value = access.settings(view, dataContext)
            check(gen)
            value to revision.await()
        }
        check(gen)
        revision = rev
        access.views(loaded)
        // The view is gone: Home falls back to All, another store.
        if (loaded.view != view) return
        settings = loaded
        plan = PlannedShelf.plan(loaded, access.profile())
        publishLayout()
        val skipped = loaded.shelves.filter { c -> c.enabled == true && plan.none { it.id == c.id } }.map { it.id }
        access.log(false, "view $view: shelves: ${plan.joinToString { it.id }}" + if (skipped.isEmpty()) "" else "; not supported: ${skipped.joinToString()}")
        rows.keys.retainAll(plan.map { it.id }.toSet())
        // Rows load top first, in this device's order.
        loadRows(access.ordered(plan), gen)
    }

    /** Tells Settings › Home Layout this view's profile layout and rows (on load, and on returning to the view). */
    fun publishLayout() {
        if (!isCurrent) return
        val settings = settings ?: return
        access.layout(settings, plan)
    }

    private fun retirePrefills() {
        prefills.forEach { it.cancel() }
        prefills.clear()
    }

    private suspend fun background(gen: Int, read: suspend () -> Unit) {
        try { check(gen); read(); check(gen) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (isCurrent && gen == generation) access.log(true, "Background Home data unavailable") }
    }

    /** Reloads only when the server's home manifest changed (returning to Home, waking, every few minutes). */
    suspend fun refreshIfStale() = owned {
        if (terminalRun) return@owned
        val known = revision ?: return@owned
        if (isLoading || loadsRunning > 0 || checksRunning > 0) return@owned
        val requested = generation
        checksRunning++
        try {
            val current = access.revision(dataContext)
            check(requested)
            if (current != null && current != known) return@owned load()
            val retry = plan.filter { it.id in failed || (it.id in calendarPending && calendarRetry?.isActive != true) }
            if (retry.isNotEmpty()) loadRows(retry, requested)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (isCurrent && requested == generation) readFailed(error)
        } finally {
            checksRunning--
        }
    }

    /** Reloads the rows built from Continue Watching and playback (after playing). */
    suspend fun reloadPlaybackRows() = owned {
        if (terminalRun) return@owned
        if (settings == null) return@owned ensureLoaded()
        // A full load running fetches these rows too.
        if (loadsRunning > 0) return@owned
        val requested = generation
        try { loadRows(plan.filter { it.source.followsPlayback }, requested) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (isCurrent && requested == generation) readFailed(error) }
    }

    /** Reloads the Watchlist row (a title added to or removed from the watchlist). */
    suspend fun reloadWatchlistRow() = owned {
        if (terminalRun) return@owned
        if (settings == null) return@owned
        val requested = generation
        try { loadRows(plan.filter { it.source == HomeSource.Watchlist }, requested) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (isCurrent && requested == generation) readFailed(error) }
    }

    private fun retireDeniedRun() {
        generation++
        terminalRun = true
        retirePrefills()
        completions.values.forEach { it.cancel() }; completions.clear()
        calendarRetry?.cancel(); calendarRetry = null
        calendarPending.clear(); loading.clear(); failed.clear()
        rows.clear(); slots = emptyList()
    }

    private fun readFailed(error: Exception) {
        if (error.vodDenial != null) retireDeniedRun()
        loadError = error.vodDenial?.message ?: "Unable to refresh Home. Try again."
        isLoading = false
    }

    private suspend fun loadRows(planned: List<PlannedShelf>, gen: Int) = supervisorScope {
        check(gen)
        val settings = settings ?: return@supervisorScope
        if (planned.isEmpty()) {
            publish()
            isLoading = false
            return@supervisorScope
        }
        val context = access.context(settings, planned, this, dataContext)
        revealed = slots.isNotEmpty()
        // A row already showing cards reloads straight to its complete form.
        val phases = planned.associate { it.id to if (rows[it.id] == null) ShelfPhase.CARDS else ShelfPhase.COMPLETE }
        planned.forEach {
            completions.remove(it.id)?.cancel()
            failed -= it.id
            if (rows[it.id] == null) loading += it.id
        }
        if (revealed) publish()
        val reveal = launch {
            // A first load waits this long, and for the top row, before showing anything, so
            // rows that answer at once never flash a placeholder.
            delay(1_000)
            if (isCurrent && gen == generation && !revealed && topRowReady) reveal()
        }
        var failure: Exception? = null
        val toComplete = mutableListOf<PlannedShelf>()
        val pendingNow = mutableListOf<PlannedShelf>()
        planned.map { shelf ->
            async {
                check(gen)
                val started = System.currentTimeMillis()
                val result = runCatching { access.row(shelf, context, phases[shelf.id] ?: ShelfPhase.CARDS) }
                check(gen)
                loading -= shelf.id
                result.onSuccess { row ->
                    calendarPending -= shelf.id
                    if (row == null) rows -= shelf.id else rows[shelf.id] = row
                    access.log(false, "${shelf.id}: ${row?.cards?.size ?: 0} cards${row?.kinds ?: ""} in ${System.currentTimeMillis() - started} ms")
                    if (row != null && row.cards.isNotEmpty() && phases[shelf.id] == ShelfPhase.CARDS && shelf.completesLater) {
                        toComplete += shelf
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    if (e.vodDenial != null) rows -= shelf.id
                    if (e is HomeCalendarPending) {
                        // Not a failure: the row keeps its loading placeholder until a retry fills it.
                        if (rows[shelf.id] == null) loading += shelf.id
                        calendarPending += shelf.id
                        pendingNow += shelf
                        if (revealed) publish()
                        return@async
                    }
                    access.log(true, "${shelf.id}: row unavailable")
                    if (rows[shelf.id] == null) failed += shelf.id
                    if (shelf.id == HomeShelf.CONTINUE_WATCHING_ID || failure == null) failure = e as? Exception
                }
                if (revealed) publish() else if (reveal.isCompleted && topRowReady) reveal()
            }
        }.awaitAll()
        reveal.cancel()
        check(gen)
        // Validate an already requested shared feed before scheduling any later stage.
        // A disabled Continue Watching row remains absent: this accessor never starts one.
        val launcherItems = context.loadedContinueWatching()
        check(gen)
        revealed = true
        if (pendingNow.isEmpty()) {
            if (planned.any { it.source == HomeSource.Calendar || it.source is HomeSource.RecentlyAired }) calendarRetries = 0
        } else {
            retryPendingCalendar(pendingNow, gen)
        }
        publish()
        // Complete passes wait for every row's first cards, so none queues ahead of an empty row.
        toComplete.forEach { complete(it, context, gen) }
        // The home screen's rows follow the All view's load (and its Continue Watching fetch).
        if (view == HomeViews.ALL) {
            check(gen)
            access.homeLoaded(launcherItems, settings.hideWatched, dataContext)
        }
        // Nothing to show at all: the page shows the error instead of a column of failed rows.
        loadError = if (slots.none { it is HomeSlot.Shelf }) failure?.vodDenial?.message ?: failure?.let { "Unable to load Home rows. Try again." } else null
        isLoading = false
    }

    /**
     * A progressive row's complete form (full metadata, exact total, and for a [PlannedShelf.startsPartial]
     * row the rest of its cards) behind its first cards. A failure keeps the first cards.
     */
    private fun complete(shelf: PlannedShelf, context: HomeContext, gen: Int) {
        completions[shelf.id] = scope.launch {
            check(gen)
            val started = System.currentTimeMillis()
            // A few at a time: each can take a while on a cold server and holds a connection.
            val row = completing.withPermit {
                check(gen)
                try { access.row(shelf, context, ShelfPhase.COMPLETE).also { check(gen) } }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (isCurrent && gen == generation) {
                        if (error.vodDenial != null) { rows -= shelf.id; failed += shelf.id; publish() }
                        access.log(true, "${shelf.id}: completing unavailable")
                    }
                    null
                }
            } ?: return@launch
            check(gen)
            if (row.cards.isEmpty()) return@launch
            access.log(false, "${shelf.id}: completed to ${row.cards.size} cards in ${System.currentTimeMillis() - started} ms")
            rows[shelf.id] = row
            completions -= shelf.id
            publish()
        }
    }

    /**
     * The server is still building the calendar (right after a restart): ask again in a few
     * seconds, a few times, then quietly drop the rows until the next refresh tries again.
     */
    private fun retryPendingCalendar(shelves: List<PlannedShelf>, gen: Int) {
        // No cancel of the previous retry here: this usually runs inside it.
        if (!isCurrent || gen != generation) return
        if (calendarRetries >= CALENDAR_RETRIES) {
            access.log(false, "calendar still building after $calendarRetries retries; giving up for now")
            calendarRetries = 0
            loading -= shelves.map { it.id }.toSet()
            return
        }
        calendarRetries++
        access.log(false, "calendar still building; retry $calendarRetries/$CALENDAR_RETRIES in ${CALENDAR_RETRY_MS / 1000} s")
        calendarRetry = scope.launch {
            delay(CALENDAR_RETRY_MS)
            check(gen)
            loadRows(shelves, gen)
        }
    }

    private fun reveal() {
        revealed = true
        publish()
        isLoading = false
    }

    /** The first row with cards is in, and no row above it is still loading. */
    private val topRowReady: Boolean
        get() {
            for (shelf in visiblePlan) {
                if (rows[shelf.id]?.cards?.isNotEmpty() == true) return true
                if (shelf.id in loading) return false
            }
            return false
        }

    /** The rows Home shows: this device's order (Row Order), minus those hidden in Settings › Home Layout. */
    private val visiblePlan get() = access.ordered(plan).filterNot { access.hiddenShelf(it.id) }

    /** The row without titles hidden from this profile. */
    private fun visible(row: HomeShelf): HomeShelf {
        if (HiddenItemsStore.items.isEmpty()) return row
        val hidden = row.cards.filter { card -> card.target?.let(HiddenItemsStore::isHidden) == true }
        return hidden.fold(row) { r, card -> r.removing(card.id) }
    }

    private fun publish() {
        if (!isCurrent) return
        val next = visiblePlan.mapNotNull { shelf ->
            val row = rows[shelf.id]
            when {
                row != null -> visible(row).let { if (it.cards.isEmpty()) null else HomeSlot.Shelf(it) }
                shelf.id in loading -> HomeSlot.Loading(shelf.id, shelf.config.title)
                shelf.id in failed -> HomeSlot.Failed(shelf.id, shelf.config.title)
                else -> null
            }
        }
        if (next != slots) slots = next
    }

    private companion object {
        /** Complete passes in flight across every Home view. */
        val completing = Semaphore(4)
        const val CALENDAR_RETRIES = 6
        const val CALENDAR_RETRY_MS = 10_000L
    }
}

/** " (3 movies, 2 series)" for the log: what a Home view's row really holds. */
private val HomeShelf.kinds: String
    get() {
        val targets = cards.mapNotNull { it.target }
        if (targets.isEmpty()) return ""
        return " (${targets.count { it.isMovie }} movies, ${targets.count { !it.isMovie }} series)"
    }

/** IO/platform boundary consumed by the real store; tests replace Android publication and transport only. */
internal interface HomeStoreAccess {
    suspend fun settings(view: String, expected: Long): HomeSettings
    suspend fun revision(expected: Long): String?
    suspend fun hidden(expected: Long)
    suspend fun aside(expected: Long)
    fun context(settings: HomeSettings, plan: List<PlannedShelf>, scope: CoroutineScope, expected: Long): HomeContext = HomeContext(settings, plan, scope, expected)
    suspend fun row(shelf: PlannedShelf, context: HomeContext, phase: ShelfPhase): HomeShelf? = shelf.load(context, phase)
    fun profile(): Profile?
    fun views(settings: HomeSettings)
    fun layout(settings: HomeSettings, plan: List<PlannedShelf>)
    fun layoutIdentity(): Any
    fun ordered(plan: List<PlannedShelf>): List<PlannedShelf>
    fun hiddenShelf(id: String): Boolean
    fun log(warning: Boolean, message: String)
    fun homeLoaded(items: List<ContinueWatchingItem>?, hideWatched: Boolean, expected: Long)
}

private object ServerHomeStoreAccess : HomeStoreAccess {
    override suspend fun settings(view: String, expected: Long) = ApiClient.homeSettings(view, expected)
    override suspend fun revision(expected: Long) = ApiClient.homeRevision(expected)
    override suspend fun hidden(expected: Long) { HiddenItemsStore.refresh(expected) }
    override suspend fun aside(expected: Long) { SetAsideStore.refresh(expected) }
    override fun profile() = AppSession.profile.value
    override fun views(settings: HomeSettings) {
        HomeViewStore.update(settings.views)
        com.strmr.tv.ui.settings.BadgeSettings.updateServer(settings.badgeVisibility)
    }
    override fun layout(settings: HomeSettings, plan: List<PlannedShelf>) = HomeLayoutStore.update(settings.layout, plan.map {
        ShelfLayoutInfo(it.id, it.config.title, ShelfStyle.isLandscapeByDefault(it.config))
    })
    override fun layoutIdentity(): Any = HomeLayoutStore.hiddenShelves to HomeLayoutStore.shelfOrder
    override fun ordered(plan: List<PlannedShelf>) = HomeLayoutStore.ordered(plan) { it.id }
    override fun hiddenShelf(id: String) = HomeLayoutStore.isHidden(id)
    override fun log(warning: Boolean, message: String) { if (warning) AppLog.w("Home", message) else AppLog.i("Home", message) }
    override fun homeLoaded(items: List<ContinueWatchingItem>?, hideWatched: Boolean, expected: Long) = com.strmr.tv.launcher.LauncherSync.homeLoaded(items, hideWatched, expected)
}
