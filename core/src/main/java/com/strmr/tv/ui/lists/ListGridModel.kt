package com.strmr.tv.ui.lists

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.vodDenial
import com.strmr.tv.home.*
import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.CustomListOperation
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.app.Config
import com.strmr.tv.ratings.*
import com.strmr.tv.ui.TitleRatingPrompt
import kotlinx.coroutines.Job
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest

/** Server-paged list state. Every page/random reply belongs to immutable options and an original profile epoch. */
@Stable
class ListGridModel(
    val list: BrowseList,
    query: Map<String, String>,
    private val scope: CoroutineScope,
    val dataContext: Long = (list as? BrowseList.CollectionCached)?.originalContext ?: (list as? BrowseList.ContinueWatchingCached)?.originalContext ?: (list as? BrowseList.RecentlyWatchedCached)?.originalContext ?: (list as? BrowseList.PopularOnServerCached)?.originalContext ?: (list as? BrowseList.SimilarCached)?.originalContext ?: (list as? BrowseList.TMDBPublicListCached)?.originalContext ?: ProfileDataContext.current,
    private val readPage: (suspend (BrowseList, Int, Int, Map<String, String>, BrowseOptions, Long) -> BrowsePage)? = null,
    private val randomOffset: (Int) -> Int = { (0 until it).random() },
    private val clientIdentity: () -> String = { Config.clientId },
    private val ratingGateway: (Long) -> TitleRatingGateway = { expected -> ApiClient.inContext(expected) { ServerTitleRatingGateway() } },
    private val openRating: (TitleRatingEditor) -> Unit = TitleRatingPrompt::open,
    private val pageSize: Int = PAGE,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val pageSelected: () -> Boolean = { true },
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val query = query.toMap()
    private val storedClient = if (list == BrowseList.LovedStored) ApiClient.inContext(dataContext) { clientIdentity() } else (list as? BrowseList.RecentlyWatchedCached)?.originalClient ?: (list as? BrowseList.PopularOnServerCached)?.originalClient ?: (list as? BrowseList.SimilarCached)?.originalClient ?: (list as? BrowseList.TMDBPublicListCached)?.originalClient
    val isCurrent get() = dataContext == ProfileDataContext.current &&
        (list !is BrowseList.CollectionCached && list !is BrowseList.ContinueWatchingCached && list !is BrowseList.RecentlyWatchedCached && list !is BrowseList.PopularOnServerCached && list !is BrowseList.SimilarCached && list !is BrowseList.TMDBPublicListCached || scope.coroutineContext[Job]?.isActive == true) &&
        (list !is BrowseList.ContinueWatchingCached && list !is BrowseList.RecentlyWatchedCached && list !is BrowseList.PopularOnServerCached && list !is BrowseList.SimilarCached && list !is BrowseList.TMDBPublicListCached || pageSelected()) &&
        (storedClient == null || storedClient == clientIdentity() && scope.coroutineContext[Job]?.isActive != false)
    private var generation = 0L
    private var selection by mutableLongStateOf(0)
    private var optionState by mutableStateOf(BrowseOptions(kind = (list as? BrowseList.RecentlyWatchedCached)?.parameters?.initialKind ?: (list as? BrowseList.PopularOnServerCached)?.parameters?.initialKind ?: (list as? BrowseList.TMDBPublicListCached)?.initialKind))
    var options: BrowseOptions
        get() = optionState
        set(value) {
            if (!isCurrent) return
            val canonical = value.canonical() ?: return
            val next = if (shown.storedMediaEligible(query)) canonical else canonical.copy(storedMedia = StoredMediaFilters())
            if (next == optionState) return
            optionState = next; selection++; invalidateRows()
        }
    private var partState by mutableIntStateOf((list as? BrowseList.Service)?.part ?: 0)
    var part: Int
        get() = partState
        set(value) { if (isCurrent && value != partState) { partState = value; selection++; invalidateRows() } }
    private var publishedRatingVersion by mutableLongStateOf(TitleRatingChanges.generation.value)
    private fun rowsCurrent() = (list != BrowseList.LovedStored || publishedRatingVersion == TitleRatingChanges.generation.value) && (list !is BrowseList.ContinueWatchingCached || watchingLive()) && (list !is BrowseList.SimilarCached || similarLive()) && (list !is BrowseList.TMDBPublicListCached || publicListLive())
    private var entryState by mutableStateOf<List<BrowseEntry>>(emptyList())
    var entries: List<BrowseEntry>
        get() = if (isCurrent && rowsCurrent()) entryState else emptyList()
        private set(value) { entryState = value }
    var fetched by mutableIntStateOf(0); private set
    private var totalState by mutableStateOf<Int?>(null)
    var total: Int?
        get() = if (isCurrent && rowsCurrent()) totalState else null
        private set(value) { totalState = value }
    var genres by mutableStateOf<List<String>>(emptyList()); private set
    var letters by mutableStateOf<List<String>>(emptyList()); private set
    var collectionObservation by mutableStateOf<CachedCollectionObservation?>(null); private set
    var watchingObservation by mutableStateOf<CachedWatchingObservation?>(null); private set
    private var watchingExpiry: Job? = null
    var similarObservation by mutableStateOf<CachedSimilarObservation?>(null); private set
    private var similarDeadline = Long.MAX_VALUE
    private var similarExpiry: Job? = null
    private fun similarLive() = similarObservation == null || monotonicNanos() < similarDeadline
    private fun watchingLive() = (list as? BrowseList.ContinueWatchingCached)?.live() != false
    var publicListObservation by mutableStateOf<CachedTMDBPublicListObservation?>(null); private set
    private var publicListDeadline = Long.MAX_VALUE
    private var publicListExpiry: Job? = null
    private fun publicListLive() = publicListObservation == null || monotonicNanos() < publicListDeadline
    fun openSavedWatching(producer: Job, selected: () -> Boolean): BrowseList.ContinueWatchingCached? =
        if (list != BrowseList.ContinueWatching || !isCurrent) null else BrowseList.ContinueWatchingCached.open(dataContext, producer, selected)

    val canOpenSavedRecent: Boolean get() = list.isNormalRecentActivity() && isCurrent
    fun openSavedRecent(producer: Job, selected: () -> Boolean): BrowseList.RecentlyWatchedCached? =
        if (!canOpenSavedRecent) null else BrowseList.RecentlyWatchedCached.open(list, query, dataContext, producer, selected, clientIdentity)

    val canOpenSavedPopular: Boolean get() = list.isNormalPopularActivity() && isCurrent
    fun openSavedPopular(producer: Job, selected: () -> Boolean): BrowseList.PopularOnServerCached? =
        if (!canOpenSavedPopular) null else BrowseList.PopularOnServerCached.open(list, query, dataContext, producer, selected, clientIdentity)

    val canOpenSavedSimilar: Boolean get() = list is BrowseList.Similar && isCurrent
    fun openSavedSimilar(producer: Job, selected: () -> Boolean): BrowseList.SimilarCached? =
        if (!canOpenSavedSimilar) null else BrowseList.SimilarCached.open(list, dataContext, producer, selected, clientIdentity)

    val canOpenSavedPublicList: Boolean get() = isCurrent && runCatching { tmdbPublicListSelection(list, query) }.getOrNull() != null
    fun openSavedPublicList(producer: Job, selected: () -> Boolean): BrowseList.TMDBPublicListCached? =
        if (!canOpenSavedPublicList) null else BrowseList.TMDBPublicListCached.open(list, query, dataContext, producer, selected, clientIdentity)

    private var mediaSupport by mutableStateOf(false)
    val storedMediaFilterSupported get() = isCurrent && shown.storedMediaEligible(query) && mediaSupport
    private var languageSupport by mutableStateOf(false)
    private var hiddenLegacyMode: Boolean? = null
    val originalLanguageFilterSupported get() = isCurrent && shown.originalLanguageEligible && languageSupport
    var loadError by mutableStateOf<String?>(null); private set
    var actionError by mutableStateOf<String?>(null)
    var loadingFor by mutableStateOf<Any?>(null); private set
    var picking by mutableStateOf(false); private set
    private var added by mutableIntStateOf(0)
    private var blockedGeneration: Long? = null
    private var loading: Read? = null
    private val pageReads = mutableSetOf<Deferred<BrowsePage>>()
    private var randomGeneration = 0L

    val shown: BrowseList get() = if (list is BrowseList.Service) list.copy(part = part) else list
    val customId: String? get() = (list as? BrowseList.Custom)?.id
    val current get() = if (isCurrent) customId?.let { id -> CustomListsStore.lists?.firstOrNull { it.id == id } } else null
    val title: String get() = current?.name ?: shown.title
    val manageable: Boolean get() = current?.let { !it.isSetAside } == true
    val reloadKey: Any get() = listOf(options, part, added, selection)
    val loadingMore: Boolean get() = loadingFor == reloadKey
    val cards: List<HomeCard>
        get() {
            val ranked = shown is BrowseList.TopTen
            val requested = capture()
            return entries.mapIndexed { i, entry ->
                val card = HomeCard.from(entry).let { if (ranked) it.ranked(i + 1) else if (list == BrowseList.Hidden) it.copy(hiddenManagementEntry = true) else it }
                if (list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached) {
                    // A saved-row key is presentation only. All actions retain card.target.
                    card.copy(id = "${if (list is BrowseList.RecentlyWatchedCached) "saved-recent" else if (list is BrowseList.PopularOnServerCached) "saved-popular" else if (list is BrowseList.SimilarCached) "saved-similar" else "saved-public-list"}:$dataContext:$generation:$i", cacheOnlyBrowseEntry = true)
                } else if (list is BrowseList.CollectionCached || list is BrowseList.ContinueWatchingCached) card.copy(cacheOnlyBrowseEntry = true)
                else if (list == BrowseList.LovedStored && entry is BrowseEntry.TitleEntry && entry.storedLove != null) {
                    card.copy(reloadStoredRating = { target, operation -> refreshStoredRating(requested, entry, card, target, operation) })
                } else card
            }
                .filter { list == BrowseList.Hidden || list == BrowseList.LovedStored || list is BrowseList.CollectionCached || list is BrowseList.ContinueWatchingCached || list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached || it.target?.let(HiddenItemsStore::isHidden) != true }
        }

    init {
        scope.launch {
            ProfileDataContext.changes.collect {
                if (!isCurrent) {
                    invalidateRows(); mediaSupport = false; languageSupport = false; genres = emptyList(); letters = emptyList()
                    loadError = "Profile changed. Reopen this list."
                }
            }
        }
    }
    fun pageSelectionChanged(selected: Boolean) {
        if ((list is BrowseList.ContinueWatchingCached || list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached) && !selected) {
            selection++; invalidateRows(); loadError = "Saved page changed. Reload this view."
        }
    }
    private fun invalidateRows() {
        pageReads.toList().forEach { it.cancel() }
        watchingExpiry?.cancel(); watchingExpiry = null; watchingObservation = null
        similarExpiry?.cancel(); similarExpiry = null; similarObservation = null; similarDeadline = Long.MAX_VALUE
        publicListExpiry?.cancel(); publicListExpiry = null; publicListObservation = null; publicListDeadline = Long.MAX_VALUE
        generation++; randomGeneration++; picking = false
        entries = emptyList(); fetched = 0; total = null; loadError = null; blockedGeneration = null
        loading = null; loadingFor = null; hiddenLegacyMode = null; collectionObservation = null
    }
    private class Read(
        val key: Any,
        val generation: Long,
        val list: BrowseList,
        val options: BrowseOptions,
        val ratingVersion: Long?,
    ) {
        val seenCollectionIDs = mutableSetOf<String>()
        val seenWatchingIDs = mutableSetOf<String>()
    }
    private fun capture() = Read(reloadKey, generation, shown, options.copy(genres = options.genres.toSet(), originalLanguages = options.originalLanguages.toSet()), if (list == BrowseList.LovedStored) TitleRatingChanges.generation.value else null)
    private fun current(read: Read) = isCurrent && read.generation == generation && read.key == reloadKey && (read.ratingVersion == null || read.ratingVersion == TitleRatingChanges.generation.value)
    private suspend fun read(read: Read, limit: Int, offset: Int, extra: Map<String, String> = query): BrowsePage = coroutineScope {
        val pending = async { readPageOwned(read, limit, offset, extra) }
        pageReads += pending
        try { pending.await() } finally { pageReads -= pending }
    }
    private suspend fun readPageOwned(read: Read, limit: Int, offset: Int, extra: Map<String, String>): BrowsePage {
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(dataContext)
        if (!current(read)) throw CancellationException("List selection changed")
        if (read.list is BrowseList.ContinueWatchingCached && !watchingLive()) throw ManagementPageFailure()
        if (read.list is BrowseList.SimilarCached && !similarLive()) throw ManagementPageFailure()
        if (read.list is BrowseList.TMDBPublicListCached && !publicListLive()) throw ManagementPageFailure()
        if (read.list is BrowseList.CollectionCached && collectionObservation?.let { it.expiresMillis <= clockMillis() } == true) throw ManagementPageFailure()
        val page = readPage?.invoke(read.list, limit, offset, extra, read.options, dataContext)
            ?: ApiClient.browsePage(read.list, limit, offset, extra, options = read.options, expectedContext = dataContext, hiddenLegacyMode = hiddenLegacyMode)
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(dataContext)
        if (!current(read)) throw CancellationException("List selection changed")
        if (read.list is BrowseList.SimilarCached && !similarLive()) throw ManagementPageFailure()
        if (read.list is BrowseList.TMDBPublicListCached && !publicListLive()) throw ManagementPageFailure()
        if (read.list is BrowseList.RecentlyWatchedCached || read.list is BrowseList.PopularOnServerCached || read.list is BrowseList.SimilarCached || read.list is BrowseList.TMDBPublicListCached) {
            val remaining = maxOf(0L, page.total.toLong() - offset)
            val expectedRows = if (limit == 0) remaining else minOf(limit.toLong(), remaining)
            if (!page.originalLanguageFilterSupported || page.total < 0 ||
                offset > page.total && fetched > 0 || page.entries.size.toLong() != expectedRows ||
                page.entries.any { it !is BrowseEntry.TitleEntry }) throw ManagementPageFailure()
        }
        if (read.list is BrowseList.SimilarCached) {
            val observation = page.similar ?: throw ManagementPageFailure()
            if (page.total > 4096) throw ManagementPageFailure()
            if (observation.seedID != read.list.seedID || observation.kind != read.list.kind.mediaType || observation.scope != "returned-results" ||
                similarObservation?.let { !it.sameObservation(observation) } == true) throw ManagementPageFailure()
            val acceptedDeadline = read.list.accept(observation)
            similarDeadline = if (similarObservation?.sameObservation(observation) == true) minOf(similarDeadline, acceptedDeadline) else acceptedDeadline
            similarObservation = observation
            similarExpiry?.cancel()
            val expiryGeneration = generation
            similarExpiry = scope.launch {
                kotlinx.coroutines.delay(maxOf(1L, (similarDeadline - monotonicNanos()) / 1_000_000L + 1L))
                if (isCurrent && generation == expiryGeneration && !similarLive()) {
                    invalidateRows(); blockedGeneration = generation; loadError = "Saved recommendations expired. Reload this view."
                }
            }
        }
        if (read.list is BrowseList.TMDBPublicListCached) {
            val observation = page.publicList ?: throw ManagementPageFailure()
            if (page.total > 4096) throw ManagementPageFailure()
            if (observation.listID != read.list.listID || observation.scope != "returned-items" ||
                publicListObservation?.let { !it.sameObservation(observation) } == true) throw ManagementPageFailure()
            val acceptedDeadline = read.list.accept(observation)
            publicListDeadline = if (publicListObservation?.sameObservation(observation) == true) minOf(publicListDeadline, acceptedDeadline) else acceptedDeadline
            publicListObservation = observation
            publicListExpiry?.cancel()
            val expiryGeneration = generation
            publicListExpiry = scope.launch {
                kotlinx.coroutines.delay(maxOf(1L, (publicListDeadline - monotonicNanos()) / 1_000_000L + 1L))
                if (isCurrent && generation == expiryGeneration && !publicListLive()) {
                    invalidateRows(); blockedGeneration = generation; loadError = "Saved list expired. Reload this view."
                }
            }
        }
        if (read.list is BrowseList.CollectionCached) {
            val observed = page.collection ?: throw ManagementPageFailure()
            if (!page.originalLanguageFilterSupported || observed.id != read.list.id || observed.expiresMillis <= clockMillis() || observed.observedMillis > clockMillis() ||
                collectionObservation?.let { it != observed } == true || fetched > 0 && total != null && total != page.total) throw ManagementPageFailure()
            val incoming = page.entries.map { HomeCard.from(it).id }
            if (incoming.distinct().size != incoming.size || incoming.any { it in read.seenCollectionIDs }) throw ManagementPageFailure()
            read.seenCollectionIDs.addAll(incoming)
            collectionObservation = observed
        }
        if (read.list is BrowseList.ContinueWatchingCached) {
            val observed = page.watching ?: throw ManagementPageFailure()
            val remaining = observed.clock.remainingMillis(observed.expiresMillis)
            if (!page.originalLanguageFilterSupported || remaining <= 0 || remaining > 600_000L || observed.observedMillis > observed.clock.upperMillis() || page.total !in 0..4096 ||
                fetched > 0 && total != page.total || page.entries.any { it !is BrowseEntry.CachedWatchingEntry } ||
                page.entries.size != (if (limit == 0) maxOf(0,page.total-offset) else minOf(limit,maxOf(0,page.total-offset)))) throw ManagementPageFailure()
            val incoming = page.entries.map { (it as BrowseEntry.CachedWatchingEntry).item.let { row -> "${row.mediaType}|${row.seriesId}" } }
            if (incoming.distinct().size != incoming.size || incoming.any { it in read.seenWatchingIDs }) throw ManagementPageFailure()
            read.seenWatchingIDs.addAll(incoming)
            val deadline = read.list.accept(observed)
            watchingObservation = observed
            watchingExpiry?.cancel()
            watchingExpiry = scope.launch {
                kotlinx.coroutines.delay(maxOf(1L,(deadline-System.nanoTime())/1_000_000L+1L))
                if (isCurrent && !watchingLive()) { invalidateRows(); loadError = "Saved feed expired. Return to Continue Watching to update it." }
            }
        }
        if ((read.list == BrowseList.LovedStored || read.list is BrowseList.RecentlyWatchedCached || read.list is BrowseList.PopularOnServerCached || read.list is BrowseList.SimilarCached || read.list is BrowseList.TMDBPublicListCached || read.list.originalLanguageEligible && read.options.originalLanguageActive) && !page.originalLanguageFilterSupported) throw LanguageFilterUnavailable()
        if (read.list == BrowseList.Hidden) {
            val mode = !page.originalLanguageFilterSupported
            if (hiddenLegacyMode != null && hiddenLegacyMode != mode) throw ManagementPageFailure()
            hiddenLegacyMode = mode
        }
        if (read.options.storedMedia.active && (!read.list.storedMediaEligible(extra) || !page.storedMediaFilterSupported || page.storedMediaFilterBasis != StoredMediaFilters.BASIS)) throw StoredMediaFilterUnavailable()
        return page
    }
    private class StoredMediaFilterUnavailable : Exception("This server cannot apply saved library media filters. Clear the media filters or reload this list.")
    private class LanguageFilterUnavailable : Exception("This server cannot apply the original language filter. Clear the filter or reload this list.")
    private fun failed(read: Read, error: Exception) {
        if (!current(read)) return
        if (error is StoredMediaFilterUnavailable || error is LanguageFilterUnavailable || error is ManagementPageFailure || read.list == BrowseList.Hidden || read.list == BrowseList.LovedStored || read.list is BrowseList.CollectionCached || read.list is BrowseList.ContinueWatchingCached || read.list is BrowseList.RecentlyWatchedCached || read.list is BrowseList.PopularOnServerCached || read.list is BrowseList.SimilarCached || read.list is BrowseList.TMDBPublicListCached) {
            if (read.list is BrowseList.SimilarCached || read.list is BrowseList.TMDBPublicListCached) invalidateRows()
            // Preserve selected intent and its clear controls; never replace it with an unfiltered page.
            entries = emptyList(); fetched = 0; total = null; blockedGeneration = generation
            if (read.list is BrowseList.CollectionCached) collectionObservation = null
            if (read.list is BrowseList.ContinueWatchingCached) { watchingObservation = null; watchingExpiry?.cancel() }
            if (read.list is BrowseList.SimilarCached) { similarObservation = null; similarExpiry?.cancel(); similarDeadline = Long.MAX_VALUE }
            if (read.list is BrowseList.TMDBPublicListCached) { publicListObservation = null; publicListExpiry?.cancel(); publicListDeadline = Long.MAX_VALUE }
            loadError = error.vodDenial?.message ?: if (read.list is BrowseList.RecentlyWatchedCached) "Saved activity unavailable. Reload this view." else if (read.list is BrowseList.PopularOnServerCached) "Saved popularity unavailable. Reload this view." else if (read.list is BrowseList.SimilarCached) "Saved recommendations unavailable. Return to recommendations." else if (read.list is BrowseList.TMDBPublicListCached) "Saved list unavailable. Return to the list." else if (read.list is BrowseList.ContinueWatchingCached) "Saved feed unavailable. Return to Continue Watching to update it." else if (read.list is BrowseList.CollectionCached) "Saved collection unavailable. Return to details to update it." else if (error is LanguageFilterUnavailable || error is ManagementPageFailure) error.message else "Unable to load this management view. Reload the list."
        } else if (entries.isEmpty()) loadError = error.vodDenial?.message ?: "Unable to load this list. Try again."
    }

    suspend fun reload() {
        if (!isCurrent) return
        currentCoroutineContext().ensureActive()
        invalidateRows()
        loadPage()
    }
    suspend fun loadPage() {
        if (!isCurrent || blockedGeneration == generation || loading != null || total?.let { fetched >= it } == true) return
        val requested = capture(); loading = requested; loadingFor = requested.key
        if (list is BrowseList.CollectionCached) requested.seenCollectionIDs.addAll(entries.map { HomeCard.from(it).id })
        if (list is BrowseList.ContinueWatchingCached) requested.seenWatchingIDs.addAll(entries.map { (it as BrowseEntry.CachedWatchingEntry).item.let { row -> "${row.mediaType}|${row.seriesId}" } })
        try {
            val offset = fetched
            val quick = requested.list.quickPageQuery
            val page = read(requested, pageSize, offset, query + quick.orEmpty())
            if (quick != null) scope.launch {
                try {
                    val full = read(requested, pageSize, offset)
                    val byId = full.entries.associateBy { HomeCard.from(it).id }
                    if (current(requested)) entries = entries.map { byId[HomeCard.from(it).id] ?: it }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { failed(requested, error) }
            }
            if (fetched == 0) { genres = page.genres; letters = page.letters }
            mediaSupport = requested.list.storedMediaEligible(query) && page.storedMediaFilterSupported
            languageSupport = requested.list.originalLanguageEligible && page.originalLanguageFilterSupported
            fetched += page.entries.size
            val seen = entries.map { HomeCard.from(it).id }.toMutableSet()
            entries = entries + if (list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached) page.entries else page.entries.filter { seen.add(HomeCard.from(it).id) }
            publishedRatingVersion = requested.ratingVersion ?: publishedRatingVersion
            total = if (list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached) page.total else page.listSize(fetched); loadError = null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failed(requested, error) }
        finally { if (loading === requested) { loading = null; loadingFor = null } }
    }
    suspend fun followItemChanges() {
        val id = customId ?: return
        CustomListsStore.itemChanges.collect { change ->
            if (!isCurrent || change.listId != id) return@collect
            generation++; randomGeneration++; picking = false
            val removed = change.removed ?: return@collect run { added++ }
            val gone = entries.filter { (it as? BrowseEntry.ItemEntry)?.item?.let { item -> item.mediaType == removed.mediaType && item.id == removed.id } == true }
            entries = entries - gone.toSet(); fetched = maxOf(0, fetched - gone.size); total = total?.let { maxOf(0, it - gone.size) }
        }
    }
    suspend fun rereadLoaded() {
        if (list is BrowseList.ContinueWatchingCached || list is BrowseList.RecentlyWatchedCached || list is BrowseList.PopularOnServerCached || list is BrowseList.SimilarCached || list is BrowseList.TMDBPublicListCached) return
        if (!isCurrent || loading != null || total == null || blockedGeneration == generation) return
        generation++; randomGeneration++; picking = false
        val requested = capture(); val wanted = fetched
        loading = requested; loadingFor = requested.key
        try {
            val result = mutableListOf<BrowseEntry>(); var first: BrowsePage? = null; var size: Int
            do {
                val page = read(requested, pageSize, result.size)
                if (first == null) first = page
                result += page.entries; size = page.listSize(result.size)
            } while (page.entries.isNotEmpty() && result.size < wanted && result.size < size)
            val seen = mutableSetOf<String>()
            publishedRatingVersion = requested.ratingVersion ?: publishedRatingVersion
            entries = result.filter { seen.add(HomeCard.from(it).id) }; fetched = result.size; total = size
            mediaSupport = requested.list.storedMediaEligible(query) && first?.storedMediaFilterSupported == true
            genres = first?.genres.orEmpty(); letters = first?.letters.orEmpty(); languageSupport = requested.list.originalLanguageEligible && first?.originalLanguageFilterSupported == true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failed(requested, error) }
        finally { if (loading === requested) { loading = null; loadingFor = null } }
    }
    /** Explicit Rate only: one exact row delivery, then the existing authoritative rating read. */
    private suspend fun refreshStoredRating(read: Read, entry: BrowseEntry.TitleEntry, card: HomeCard, target: DetailsTarget, operation: CustomListOperation) {
        suspend fun check() {
            currentCoroutineContext().ensureActive(); operation.requireTarget(target)
            if (!current(read) || target !== card.target || operation.owner.epoch != dataContext || entries.none { it === entry }) throw CancellationException("Stored rating selection changed")
        }
        check()
        val ordinal = requireNotNull(entry.storedLove).rank - 1
        val page = this.read(read, 1, ordinal)
        check()
        val actual = (page.entries.singleOrNull() as? BrowseEntry.TitleEntry)?.title
        if (!page.originalLanguageFilterSupported || actual?.id != target.titleId || actual.mediaType != if (target.isMovie) "movie" else "series") throw StoredRatingReloadRequired()
        val server = operation.owner.use { ratingGateway(dataContext) }
        val bound = object : TitleRatingGateway {
            override fun isCurrent() = current(read) && entries.any { it === entry } && server.isCurrent()
            private fun checkTarget(value: DetailsTarget) { if (value !== target || !isCurrent()) throw CancellationException("Rating owner changed") }
            override suspend fun read(value: DetailsTarget): TitleRatingReply { checkTarget(value); return server.read(value).also { checkTarget(value) } }
            override suspend fun write(value: DetailsTarget, rating: PersonalRating): TitleRatingReply { checkTarget(value); return server.write(value, rating).also { checkTarget(value) } }
        }
        if (!bound.isCurrent()) throw CancellationException("Rating owner changed")
        val reply = bound.read(target)
        PersonalRating.from(reply.rating)
        check()
        val editor = TitleRatingEditor(target, bound, initialRating = reply,
            receiptReloadHint = "Close this prompt and choose Rate again to refresh this title.")
        if (!operation.publish { if (current(read)) openRating(editor) else editor.close() }) editor.close()
    }

    suspend fun followStoredRatingChanges() {
        if (list != BrowseList.LovedStored) return
        var seen = TitleRatingChanges.generation.value
        TitleRatingChanges.generation.collectLatest { next ->
            if (next != seen && isCurrent) { seen = next; reload() }
        }
    }

    fun remove(card: HomeCard) {
        if (!isCurrent) return
        val id = customId ?: return
        val entry = entries.firstOrNull { HomeCard.from(it).id == card.id } as? BrowseEntry.ItemEntry ?: return
        val requested = capture(); val previous = Triple(entries, fetched, total)
        val operations = CustomListOperations(dataContext)
        val operation = operations.begin(id, entry.item) ?: return
        entries = entries - entry; fetched = maxOf(0, fetched - 1); total = total?.let { maxOf(0, it - 1) }
        scope.launch {
            try {
                if (!current(requested)) return@launch
                operations.run(operation) { CustomListsStore.remove(entry.item, id, operation = it, expectedContext = dataContext, reloadGrids = false) }; if (current(requested)) actionError = null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (current(requested)) { entries = previous.first; fetched = previous.second; total = previous.third; actionError = "Unable to remove this title. Try again." } }
            finally { operations.close() }
        }
    }
    fun pickRandom(open: (HomeLink) -> Unit) {
        if (!isCurrent || list is BrowseList.ContinueWatchingCached && !watchingLive() || list is BrowseList.SimilarCached && !similarLive() || list is BrowseList.TMDBPublicListCached && !publicListLive() || picking || blockedGeneration == generation || loading != null) return
        val count = total ?: fetched
        if (count <= 0) return
        val requested = capture(); val cached = entries.toList(); val countRead = fetched; val token = ++randomGeneration
        picking = true
        scope.launch {
            try {
                if (!current(requested) || token != randomGeneration) return@launch
                repeat(6) {
                    val offset = randomOffset(count).coerceIn(0, count - 1)
                    val entry = if (offset < cached.size && countRead == cached.size) cached[offset] else read(requested, 1, offset).entries.firstOrNull() ?: return@launch
                    if (!current(requested) || token != randomGeneration || list is BrowseList.ContinueWatchingCached && !watchingLive() || list is BrowseList.SimilarCached && !similarLive() || list is BrowseList.TMDBPublicListCached && !publicListLive()) return@launch
                    val link = HomeCard.from(entry).link
                    if (link is HomeLink.Details || link is HomeLink.Library && link.entry.group.isShow) return@launch open(link)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(requested, error) }
            finally { if (token == randomGeneration) picking = false }
        }
    }
    companion object { const val PAGE = 60 }
}

/** A live sorted collection moved; no different row may renew or open the old title editor. */
class StoredRatingReloadRequired : Exception("This list changed. Reload the list and choose Rate again.")
