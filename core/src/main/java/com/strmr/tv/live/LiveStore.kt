package com.strmr.tv.live

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What the Live tab lists: every channel, the favorites, or one or more categories. */
sealed interface LiveFilter {
    val label: String
    val categories: Set<String> get() = emptySet()

    data object All : LiveFilter { override val label = "All Channels" }
    data object Favorites : LiveFilter { override val label = "Favorites" }
    /** Never empty: no categories is [All] (see [selecting]). */
    data class Categories(override val categories: Set<String>) : LiveFilter {
        override val label get() = if (categories.size == 1) categories.first() else "${categories.size} Categories"
    }

    companion object {
        /** [names] as a filter: [All] when there are none. */
        fun selecting(names: Set<String>): LiveFilter = if (names.isEmpty()) All else Categories(names)
    }
}

/**
 * The Live tab's channels and guide data, for the active profile. Channels come a page at a
 * time from the server, which applies the source, category and search filters; hidden channels
 * are dropped here. Favorites are listed only under the Favorites filter, not pinned to the top
 * of the others. Favorites and hidden channels live in the
 * profile's settings (`liveTV`), shared with the Expo app; the category selection is kept on
 * this device, per profile, and restored on return. Guide data is [LiveGuideData]. Main thread only.
 */
class LiveStore(
    private val scope: CoroutineScope, val dataContext: Long = ProfileDataContext.current,
    private val preferences: LiveViewPreferences = LivePrefs,
    private val catalog: LiveCatalogAccess = ApiLiveCatalogAccess,
    private val stationFavorites: StationFavorites = ProfileStationFavorites.get(dataContext),
    private val profileId: String = ApiClient.profileId,
) {
    data class Query(val filter: LiveFilter, val sourceId: String?, val search: String, val groupByStation: Boolean = true,
        val favoriteIds: Set<String> = emptySet(), val favoriteStationIds: Set<String>? = null, val includeHidden: Boolean? = null,
        val hiddenRevision: Long = 0)
    private val pages = LivePageGate<Query>()
    val isContextCurrent get() = dataContext == ProfileDataContext.current
    private fun checkContext() = ProfileDataContext.requireCurrent(dataContext)

    private var filterState = mutableStateOf(LiveFilter.selecting(preferences.categories(profileId)))
    var filter: LiveFilter
        get() = filterState.value
        set(value) {
            filterState.value = value
            if (value != LiveFilter.Favorites) preferences.saveCategories(profileId, value.categories)
        }
    /** Where the header's star goes back to when Favorites is turned off. */
    private var filterBeforeFavorites: LiveFilter = filter
    private val grouping = mutableStateOf(preferences.groupStations(profileId))
    var groupByStation: Boolean
        get() = grouping.value
        set(value) { if (isContextCurrent) { grouping.value = value; preferences.saveGroupStations(profileId,value) } }
    private var collapsedStations by mutableStateOf<Set<String>>(emptySet())
    private val stationRows = derivedStateOf { LiveStationGroups.rows(visibleChannels,groupByStation,collapsedStations) }
    val channelRows get() = stationRows.value
    fun toggleStation(stationId: String) { if (isContextCurrent) collapsedStations = LiveStationGroups.toggle(collapsedStations,stationId) }

    var sourceId by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
        private set
    private var hiddenVisible by mutableStateOf(false)
    var showHidden: Boolean
        get() = hiddenVisible
        set(value) { if(isContextCurrent) { val before=query;hiddenVisible=value;invalidateIfChanged(before) } }

    var channels by mutableStateOf<List<LiveChannel>>(emptyList())
        private set
    var total by mutableStateOf<Int?>(null)
        private set
    var categories by mutableStateOf<List<String>>(emptyList())
        private set
    var sources by mutableStateOf<List<LiveSource>>(emptyList())
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var pageError by mutableStateOf<String?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set
    /** A passing note under the controls ("Only one source is available."), cleared after a moment. */
    var notice by mutableStateOf<String?>(null)
        private set
    /** The Stremio channel whose streams Choose Source lists. */
    var sourceChoice by mutableStateOf<LiveSourceChoice?>(null)
    private var sourceChoiceJob: Job? = null
    private var noticeJob: Job? = null
    /** Recording from the guide, and the scheduled / recording programmes it marks. */
    val recordings = LiveRecordings(scope, dataContext) { showNotice(it) }
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set
    /** null until known; the guide is offered only when the EPG is on. */
    var epgEnabled by mutableStateOf<Boolean?>(null)
        private set
    /** Now / next and guide schedules for the channels on screen. */
    val guide = LiveGuideData(scope,epgEnabled = { epgEnabled == true },context = dataContext)
    val schedules get() = guide.schedules

    var hasMore = true
        private set
    private var loadingFor by mutableStateOf<Query?>(null)
    private var loadedFor: Query? = null
    private var listsLoaded = false
    private var legacyRevision = 0L
    private var legacyReadSequence = 0L
    private var legacySaves = 0
    private var searchJob: Job? = null
    /** Waits out a server guide refresh, then re-fetches the guide data. */
    private var guideRefreshJob: Job? = null

    private var stationState by mutableStateOf(stationFavorites.state.value)
    private var hiddenRevision by mutableStateOf(0L)
    init { scope.launch { stationFavorites.state.collect { if(isContextCurrent)applyStationState(it) } } }
    private fun invalidateIfChanged(before: Query) {
        if(query!=before) { pages.reset();loadingFor=null;loadedFor=null }
    }
    private fun applyStationState(state: StationFavoriteState) {
        val before=query
        stationState=state
        invalidateIfChanged(before)
    }
    val stationFavoritesSupported get() = isContextCurrent && stationState.supported
    val favoriteStationIds get() = if(isContextCurrent)stationState.ids else emptySet()
    val stationFavoriteFailure get() = stationState.failures.values.firstOrNull()
    val stationFavoriteSettingsFailed get() = stationState.loadError != null
    val stationFavoriteSaving get() = stationState.pending.isNotEmpty()
    val stationFavoriteError get() = stationState.loadError ?: stationFavoriteFailure?.message
    val stationFavoriteBusy get() = stationFavoriteFailure?.stationId in stationState.pending
    val query: Query get() {
        val ids=if(!stationState.supported)null else if(filter==LiveFilter.Favorites)stationState.ids.toSet() else emptySet()
        val stationMode=!ids.isNullOrEmpty()
        return Query(filter,sourceId,search,groupByStation,if(filter==LiveFilter.Favorites)favorites.toSet() else emptySet(),
            ids,if(stationMode)showHidden else null,if(stationMode && !showHidden)hiddenRevision else 0)
    }
    fun stationAction(channel: LiveChannel): StationFavoriteAction? = StationFavoriteIds.from(channel)?.let(::stationAction)
    fun stationAction(id: String): StationFavoriteAction? =
        if(isCurrent && channels.any { StationFavoriteIds.from(it)==id }) stationFavorites.action(id) else null
    fun setStationFavorite(action: StationFavoriteAction) {
        if(!isContextCurrent || !isCurrent || !channels.any { StationFavoriteIds.from(it)==action.stationId })return
        stationFavorites.set(action.stationId,action.desired)
        applyStationState(stationFavorites.state.value)
    }
    fun retryStationFavorite() {
        if(!isContextCurrent)return
        stationFavoriteFailure?.let { stationFavorites.retry(it);applyStationState(stationFavorites.state.value) }
    }
    fun retryFavoriteSettings() { if(isContextCurrent)scope.launch { loadFavoriteSettings();reload(force=true) } }

    /** The channels to show: hidden ones only while [showHidden] is on. */
    val visibleChannels get() = if (showHidden || hidden.isEmpty()) channels else channels.filterNot { it.id in hidden }
    val isLoading get() = loadingFor != null
    /** The loaded channels are the current filters' (not a previous filter's, about to go). */
    val isCurrent get() = loadedFor == query

    // Channels

    /** First load: the profile's lists and the EPG status, then the first page. */
    suspend fun start() {
        checkContext()
        if (!listsLoaded) loadFavoriteSettings()
        if (epgEnabled == null) {
            val enabled = catalog.epgEnabled(dataContext)
            currentCoroutineContext().ensureActive()
            checkContext()
            epgEnabled = enabled
        }
        reload()
    }

    private suspend fun loadFavoriteSettings() {
        checkContext()
        val ticket=stationFavorites.beginRead()
        val legacyRead=legacyRevision
        val sequence=++legacyReadSequence
        val lists=try { catalog.preferences(dataContext) }
            catch(error: CancellationException){throw error}
            catch(_:Exception){stationFavorites.failRead(ticket);null}
        checkContext()
        currentCoroutineContext().ensureActive()
        lists?.let {
            if(legacyRead==legacyRevision && sequence==legacyReadSequence && legacySaves==0) {
                favorites=it.favorites.toSet();hidden=it.hidden.toSet();listsLoaded=true
            }
            stationFavorites.acceptRead(ticket,it)
        }
        applyStationState(stationFavorites.state.value)
    }

    /** Debounced into [search] before it reaches the server. */
    fun setSearchText(text: String) {
        if (!isContextCurrent) return
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(400)
            checkContext()
            if (text.trim() != search) search = text.trim()
        }
    }

    /** From the first page, unless the loaded list already matches the filters. */
    suspend fun reload(force: Boolean = false) {
        checkContext()
        if (!force && loadedFor == query && channels.isNotEmpty()) return
        pages.reset()
        loadingFor = null
        channels = emptyList()
        total = null
        hasMore = true
        loadError = null
        loadedFor = null
        pageError = null
        loadMore()
    }

    suspend fun loadMore() {
        val requested = query
        checkContext()
        if (!hasMore) return
        val ticket = pages.begin(requested) ?: return
        loadingFor = requested
        pageError = null
        val offset = ticket.offset
        try {
            val page = catalog.page(requested,offset,PAGE,dataContext)
            if (!isContextCurrent || !pages.commitPage(ticket,query,page.channels.size)) return
            val seen = if (offset == 0) mutableSetOf() else channels.map(LiveStationGroups::entryKey).toMutableSet()
            val fresh = page.channels.filter { seen.add(LiveStationGroups.entryKey(it)) }
            channels = if (offset == 0) fresh else channels + fresh
            hasMore = page.hasMore && page.channels.isNotEmpty()
            total = page.total
            page.availableCategories?.let { categories = it }
            page.sources?.let { srcs ->
                sources = srcs
                if (sourceId != null && srcs.none { it.id == sourceId }) sourceId = null
            }
            // Categories the server no longer lists drop out of the selection.
            val selected = requested.filter.categories
            if (selected.isNotEmpty() && categories.isNotEmpty() && !categories.containsAll(selected)) {
                filter = LiveFilter.selecting(selected.intersect(categories.toSet()))
            }
            loadedFor = requested
            loadError = null
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            if (pages.accepts(ticket,query) && isContextCurrent) {
                if (channels.isEmpty()) loadError = e.message else pageError = e.message ?: "Couldn't load more channels."
            }
        } finally {
            if (pages.finish(ticket)) loadingFor = null
        }
    }

    // Favorites and hidden channels

    /** The header's star: Favorites, or back to the filter that was on before it. */
    fun toggleFavoritesOnly() {
        if (filter == LiveFilter.Favorites) {
            filter = filterBeforeFavorites
        } else {
            filterBeforeFavorites = filter
            filter = LiveFilter.Favorites
        }
    }

    /**
     * Adds or removes [name] from the selected categories (from Favorites, starts a new
     * selection); none left lists every channel.
     */
    fun toggleCategory(name: String) {
        val names = filter.categories
        filter = LiveFilter.selecting(if (name in names) names - name else names + name)
    }

    fun toggleFavorite(channel: LiveChannel) {
        if (!isContextCurrent) return
        pages.reset()
        loadingFor = null
        val previous = favorites
        val previousChannels = channels
        favorites = if (channel.id in favorites) favorites - channel.id else favorites + channel.id
        if (filter == LiveFilter.Favorites && channel.id !in favorites &&
            !(stationFavoritesSupported && StationFavoriteIds.from(channel) in favoriteStationIds)) {
            channels = channels.filterNot { it.id == channel.id }
        }
        save(revert = { favorites = previous; channels = previousChannels })
    }

    fun toggleHidden(channel: LiveChannel) {
        if (!isContextCurrent) return
        val previous = hidden
        hidden = if (channel.id in hidden) hidden - channel.id else hidden + channel.id
        save(revert = { hidden = previous },onSaved = { val before=query;hiddenRevision++;invalidateIfChanged(before) })
    }

    // Choose Source

    /**
     * Loads a Stremio channel's streams and opens the list when there is more than one;
     * otherwise says why not, with the Expo app's wording.
     */
    fun chooseSource(channel: LiveChannel) {
        if (!isContextCurrent) return
        if (sourceChoiceJob?.isActive == true) return
        showNotice("Loading sources…", clears = false)
        sourceChoiceJob = scope.launch {
            try {
                checkContext()
                val streams = ApiClient.liveStremioStreams(channel)
                checkContext()
                if (streams.size > 1) {
                    noticeJob?.cancel()
                    notice = null
                    sourceChoice = LiveSourceChoice(channel, streams)
                } else {
                    showNotice(if (streams.isEmpty()) "No playable sources found." else "Only one source is available.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                checkContext()
                showNotice(e.message ?: "Failed to load sources.")
            }
        }
    }

    private fun showNotice(text: String, clears: Boolean = true) {
        notice = text
        noticeJob?.cancel()
        if (clears) noticeJob = scope.launch {
            delay(4_000)
            notice = null
        }
    }

    private fun save(revert: () -> Unit, onSaved: () -> Unit = {}) {
        legacyRevision++
        legacySaves++
        val fav = favorites.sorted()
        val hid = hidden.sorted()
        scope.launch {
            try {
                catalog.saveLegacy(fav, hid, dataContext)
                checkContext()
                actionError = null
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                checkContext()
                revert()
                actionError = e.message
            } finally {
                if(isContextCurrent) { legacyRevision++;legacySaves-- }
            }
        }
    }

    // Guide data

    fun needNowNext(channel: LiveChannel) = guide.needNowNext(channel)
    fun current(channel: LiveChannel, now: Long = System.currentTimeMillis()) = guide.current(channel, now)
    fun needSchedule(channel: LiveChannel, from: Long, to: Long) = guide.needSchedule(channel, from, to)

    // Refresh Guide & Channels

    /**
     * Clears the server's channel caches and reloads the list. With the EPG on, the server also
     * re-downloads its guide; once that is done, the guide data shown here is fetched again and
     * swapped in, so the guide doesn't blank while it waits.
     */
    suspend fun refresh() {
        checkContext()
        guideRefreshJob?.cancel()
        ApiClient.clearLiveCache()
        checkContext()
        val epgOn = epgEnabled == true
        var before: EpgStatus? = null
        if (epgOn) {
            before = runCatching { ApiClient.epgStatus() }.getOrNull()
            checkContext()
            showNotice("Refreshing the guide…", clears = false)
            val started = ApiClient.startEpgRefresh()
            checkContext()
            if (!started) {
                showNotice("Couldn't start a guide refresh.")
                before = null
            }
        }
        guide.clearNowNext()
        reload(force = true)
        if (!epgOn) {
            showNotice("Channels refreshed.")
            return
        }
        val start = before ?: return
        guideRefreshJob = scope.launch { awaitGuideRefresh(start) }
    }

    /** Polls the guide status until the server's refresh finishes (ten minutes at most), then re-fetches. */
    private suspend fun awaitGuideRefresh(before: EpgStatus) {
        var sawRefreshing = before.refreshing
        val started = System.currentTimeMillis()
        var status: EpgStatus? = null
        while (System.currentTimeMillis() - started < 600_000) {
            delay(3_000)
            checkContext()
            val current = runCatching { ApiClient.epgStatus() }.getOrNull() ?: continue
            checkContext()
            status = current
            if (current.refreshing) { sawRefreshing = true; continue }
            // Done once a run was seen or a new guide landed; a run too quick to see (or one that
            // never started) counts as done after a few polls.
            if (sawRefreshing || current.lastRefresh != before.lastRefresh || System.currentTimeMillis() - started > 12_000) break
        }
        checkContext()
        guide.refetch()
        checkContext()
        val error = status?.lastError
        if (!error.isNullOrEmpty() && status?.lastRefresh == before.lastRefresh) {
            showNotice("Guide refresh failed: $error")
        } else {
            showNotice("Guide refreshed.")
        }
    }

    private companion object {
        const val PAGE = 250
    }
}
