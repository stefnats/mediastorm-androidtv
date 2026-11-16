package com.strmr.tv.home

import com.strmr.core.R
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.api.Profile
import com.strmr.tv.api.libraryGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.api.vodOptionalRead

/** A configured shelf the app can show, and where its cards come from. */
data class PlannedShelf(
    val config: ShelfConfig,
    val source: HomeSource,
    /** Cards the row shows before its explore card. */
    val cap: Int,
    /** The shelf's own limit is below the cap: the row is the whole list, no explore card. */
    val limited: Boolean,
    val exploreFirst: Boolean,
) {
    val id: String get() = config.id

    /** A progressive list: the first cards come fast, then a second pass fills them in. */
    val completesLater: Boolean get() = (source as? HomeSource.List)?.list?.loadsProgressively == true

    /** Cards the row asks for: its cap, plus the explore card's collage when the list runs on. */
    private val wanted: Int get() = if (limited) cap else cap + HomeShelf.COLLAGE_OVERFLOW

    /** A progressive row too long for one quick pass: its first cards are a [FIRST_PAGE], its complete pass the rest. */
    val startsPartial: Boolean get() = completesLater && wanted > FIRST_PAGE

    private fun row(cards: List<HomeCard>, total: Int? = null, list: BrowseList? = null) = HomeShelf(
        id, config.title, cards, cap = cap, list = if (limited) null else list, exploreFirst = exploreFirst,
        reportedTotal = if (limited) minOf(cards.size, cap) else total,
    )

    /** The row's cards; null leaves the row out. `phase` applies to progressive lists. */
    suspend fun load(context: HomeContext, phase: ShelfPhase = ShelfPhase.CARDS): HomeShelf? {
        context.check()
        val settings = context.settings
        val result = when (val s = source) {
            HomeSource.ContinueWatching -> {
                var items = context.continueWatching()
                if (settings.excludeUpcomingFromContinue) items = items.filterNot { it.isUpcoming }
                row(items.map(HomeCard::from), list = BrowseList.ContinueWatching)
            }
            HomeSource.Upcoming -> {
                val items = context.continueWatching().filter { it.isUpcoming }
                val sorted = when (config.sort) {
                    "air-date-desc" -> items.sortedByDescending { it.airs }
                    "title" -> items.sortedBy { it.seriesTitle.lowercase() }
                    "recently-watched" -> items
                    else -> items.sortedBy { it.airs }
                }
                HomeShelf(id, config.title, sorted.take(cap).map(HomeCard::from), cap)
            }
            HomeSource.Watchlist -> {
                val page = context.page(BrowseList.Watchlist, cap + HomeShelf.COLLAGE_OVERFLOW, settings.viewQuery, null)
                val items = page.entries.filterIsInstance<BrowseEntry.ItemEntry>().map { it.item }
                    .filter { matchesHomeView(it.mediaType, settings.filter) }
                val shown = if (settings.hideWatched) items.filter { it.watchState != "complete" } else items
                row(shown.map(HomeCard::from), page.total, BrowseList.Watchlist)
            }
            HomeSource.SetAside -> {
                val list = context.lists().firstOrNull { it.isSetAside } ?: return null
                val items = context.items(list.id).filter { matchesHomeView(it.mediaType, settings.filter) }
                row(items.map(HomeCard::from), list = BrowseList.Custom(list.id, list.name))
            }
            is HomeSource.List -> {
                val extra = buildMap {
                    put("name", config.title)
                    if (config.hideUnreleased == true) put("hideUnreleased", "true")
                    if (settings.hideWatched) put("hideWatched", "true")
                    // The server filters the list to the view's media type.
                    putAll(settings.viewQuery)
                }
                // A progressive row's first pass asks for a screenful; its complete pass fills the row.
                val partial = phase == ShelfPhase.CARDS && startsPartial
                val page = context.page(s.list, if (partial) FIRST_PAGE else wanted, extra, phase)
                val cards = page.entries.mapIndexed { i, entry ->
                    if (s.ranked && entry is BrowseEntry.TitleEntry) HomeCard.from(entry.title, rank = i + 1) else HomeCard.from(entry)
                }
                // Until then the explore card follows the first cards, so the whole list is a click
                // away even if the complete pass is slow or fails.
                if (partial) {
                    HomeShelf(id, config.title, cards, cap = cards.size, list = if (limited) null else s.list,
                              exploreFirst = exploreFirst, reportedTotal = if (limited) cards.size else page.total)
                } else {
                    row(cards, page.total, s.list)
                }
            }
            HomeSource.Calendar -> {
                val groups = CalendarGroup.group(context.calendar())
                HomeShelf(id, config.title, CalendarGroup.comingUp(groups, cap).map { it.card }, cap)
            }
            is HomeSource.RecentlyAired -> {
                val now = System.currentTimeMillis()
                val items = CalendarGroup.group(context.calendar())
                    .filter { !it.first.isMovie && it.matches(s.sources) && (it.airs ?: Long.MAX_VALUE) <= now }
                    .sortedByDescending { it.airs ?: Long.MIN_VALUE }
                HomeShelf(id, config.title, items.take(cap).map { it.card }, cap)
            }
            HomeSource.Dashboard -> HomeShelf(id, config.title, context.dashboard().mapNotNull { it.card() }, cap)
            is HomeSource.WatchSomething -> {
                // Search waits on the Search tab; the AI cards need an AI key on the server.
                if (!s.hasAI) return null
                HomeShelf(
                    id, config.title, cap = cap,
                    cards = listOf(
                        HomeCard(
                            "__watch_something_ai__", HomeLink.Action(HomeAction.ASK_AI), "Ask AI", "Describe what you want",
                            "Tell the server's AI what you're in the mood for and get a list to pick from.",
                            drawable = R.drawable.watch_something_ask_ai,
                        ),
                        HomeCard(
                            "__watch_something_surprise__", HomeLink.Action(HomeAction.SURPRISE), "Surprise Me", "A random pick",
                            "Let the server pick something for tonight.", drawable = R.drawable.watch_something_surprise,
                        ),
                    ),
                )
            }
            is HomeSource.Services -> HomeShelf(
                id, config.title, cap = cap,
                cards = s.services.map {
                    HomeCard("service-${it.id}", HomeLink.OpenList(BrowseList.Service(it)), it.name, "Top movies and shows",
                             logoUrl = it.logoUrl, logoTile = true)
                },
            )
            is HomeSource.Hub -> HomeShelf(id, config.title, cap = cap, cards = context.hub(s.links, cap))
            HomeSource.LiveFavorites -> HomeShelf(id, config.title, context.liveFavorites(cap), cap)
            is HomeSource.Library -> {
                // Newest first for every source (Plex and Jellyfin default to A–Z), in the view's media type.
                val page = context.library(s.library.id, wanted)
                HomeShelf(
                    id, s.library.name, page.groups.map { HomeCard.from(com.strmr.tv.api.LibraryEntry(s.library, it)) }, cap = cap,
                    list = if (limited) null else BrowseList.Library(s.library), exploreFirst = exploreFirst,
                    reportedTotal = if (limited) minOf(page.groups.size, cap) else page.total,
                )
            }
        }
        context.check()
        return result
    }

    companion object {
        private val notForCuratedKids = setOf("trending-movies", "trending-tv", "top-ten", "streaming-services")
        private const val TOP_TEN_ID = "top-ten"
        /** Cards a progressive row's first pass asks for, a screenful or two; a large `itemCap` waits for the complete pass. */
        const val FIRST_PAGE = 30

        /**
         * The enabled shelves in order, minus those the app can't show yet (watch rooms) and
         * those a kids profile doesn't get. A curated kids profile gets a row
         * per allowed list instead of the open-ended ones (trending, Top 10, services).
         */
        fun plan(settings: HomeSettings, profile: Profile?): List<PlannedShelf> {
            val isKids = profile?.isKidsProfile == true
            var all = applyTopShelf(settings.shelves, settings)
            if (profile != null && profile.isKidsCurated) {
                all = (all.filterNot { it.id in notForCuratedKids } + kidsListShelves(profile.kidsAllowedLists ?: emptyList()))
                    .sortedBy { it.order ?: 0 }
            }
            val seen = mutableSetOf<String>()
            return all.mapNotNull { config ->
                if (config.enabled != true || !seen.add(config.id) || (isKids && config.id == "watch-something")) return@mapNotNull null
                val source = HomeSource.from(config, all, settings) ?: return@mapNotNull null
                val limit = config.limit ?: 0
                val limited = limit > 0 && limit < settings.itemCap
                PlannedShelf(config, source, if (limited) limit else settings.itemCap, limited, settings.exploreFirst)
            }
        }

        /**
         * The Expo TV top shelf (`tvTopShelfMode`). Disabled: no Top 10. Shelf: another
         * configured shelf takes Top 10's place. Default keeps the rows as configured (the
         * promoted Top 10 hero is parked on tvOS too).
         */
        private fun applyTopShelf(shelves: List<ShelfConfig>, settings: HomeSettings): List<ShelfConfig> = when (settings.topShelfMode) {
            TopShelfMode.DISABLED -> shelves.filterNot { it.id == TOP_TEN_ID }
            TopShelfMode.SHELF -> {
                val sourceId = settings.topShelfSourceId
                val source = shelves.firstOrNull { it.id == sourceId }
                if (sourceId == null || sourceId == TOP_TEN_ID || source == null) {
                    shelves
                } else {
                    val order = shelves.firstOrNull { it.id == TOP_TEN_ID }?.order ?: minOf(shelves.mapNotNull { it.order }.minOrNull() ?: 0, 0)
                    (listOf(source.copy(enabled = true, order = order)) + shelves.filterNot { it.id == sourceId || it.id == TOP_TEN_ID })
                        .sortedBy { it.order ?: 0 }
                }
            }
            TopShelfMode.STANDARD -> shelves
        }

        /** The Expo app's `kids-list-N` shelves: MDBList rows named after the URL's last segment. */
        private fun kidsListShelves(urls: List<String>) = urls.mapIndexed { index, url ->
            val slug = url.trimEnd('/').substringAfterLast('/')
            val name = if (slug.isEmpty()) "Kids List ${index + 1}"
            else slug.split('-', '_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            ShelfConfig(id = "kids-list-$index", type = "mdblist", listUrl = url, name = name, enabled = true, order = 10 + index)
        }
    }
}

/** What several rows share within one load: fetched once, on first use. */
class HomeContext internal constructor(
    val settings: HomeSettings,
    plan: List<PlannedShelf>,
    scope: CoroutineScope,
    val dataContext: Long,
    readContinue: suspend (Long) -> List<ContinueWatchingItem>,
    readCalendar: suspend (Int, Long) -> List<CalendarItem>,
    private val access: HomeShelfReads = ServerHomeShelfReads,
) {
    constructor(settings: HomeSettings, plan: List<PlannedShelf>, scope: CoroutineScope, dataContext: Long = ProfileDataContext.current) :
        this(settings, plan, scope, dataContext, { ApiClient.continueWatching(it) }, { limit, expected -> ApiClient.homeCalendar(limit, expected) })

    internal suspend fun check() {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(dataContext)
    }
    private val continueWatching: Deferred<List<ContinueWatchingItem>>? =
        if (plan.any { it.source == HomeSource.ContinueWatching || it.source == HomeSource.Upcoming }) {
            scope.async { check(); readContinue(dataContext).also { check() } }
        } else null
    private val calendar: Deferred<List<CalendarItem>>? =
        if (plan.any { it.source == HomeSource.Calendar || it.source is HomeSource.RecentlyAired }) {
            val limit = maxOf(plan.maxOfOrNull { it.cap } ?: 20, 20)
            scope.async { check(); readCalendar(limit, dataContext).also { check() } }
        } else null

    suspend fun continueWatching(): List<ContinueWatchingItem> {
        check()
        val items = continueWatching?.await().orEmpty()
        check()
        return items.filter { matchesHomeView(if (it.nextEpisode != null) "series" else "movie", settings.filter) }
    }
    suspend fun loadedContinueWatching(): List<ContinueWatchingItem>? {
        check()
        return continueWatching?.let { vodOptionalRead { it.await() } }.also { check() }
    }
    suspend fun calendar(): List<CalendarItem> {
        check()
        val items = calendar?.await().orEmpty()
        check()
        return items.filter { matchesHomeView(it.mediaType, settings.filter) }
    }
    private suspend fun <T> read(block: suspend () -> T): T { check(); return block().also { check() } }
    internal suspend fun page(list: BrowseList, count: Int, extra: Map<String, String>, phase: ShelfPhase?) = read { access.page(list, count, extra, phase, dataContext) }
    internal suspend fun lists() = read { access.lists(dataContext) }
    internal suspend fun items(id: String) = read { access.items(id, dataContext) }
    internal suspend fun dashboard() = read { access.dashboard(dataContext) }
    internal suspend fun hub(links: List<HomeSource.HubLink>, cap: Int) = read { access.hub(links, cap, dataContext) }
    internal suspend fun liveFavorites(cap: Int) = read { access.liveFavorites(cap, dataContext) }
    internal suspend fun library(id: String, count: Int) = read { access.library(id, count, settings.filter.mediaType, dataContext) }

}


/** Every actual shelf demand carries the immutable Home owner. No profile or content authority is inferred here. */
internal interface HomeShelfReads {
    suspend fun page(list: BrowseList, count: Int, extra: Map<String, String>, phase: ShelfPhase?, expected: Long): BrowsePage
    suspend fun lists(expected: Long): List<CustomList>
    suspend fun items(id: String, expected: Long): List<WatchlistItem>
    suspend fun dashboard(expected: Long): List<DashboardStream>
    suspend fun hub(links: List<HomeSource.HubLink>, cap: Int, expected: Long): List<HomeCard>
    suspend fun liveFavorites(cap: Int, expected: Long): List<HomeCard>
    suspend fun library(id: String, count: Int, mediaType: String?, expected: Long): com.strmr.tv.api.LibraryGroupPage
}
private object ServerHomeShelfReads : HomeShelfReads {
    override suspend fun page(list: BrowseList, count: Int, extra: Map<String, String>, phase: ShelfPhase?, expected: Long) = ApiClient.browsePage(list, count, extra = extra, phase = phase, expectedContext = expected)
    override suspend fun lists(expected: Long) = ApiClient.customLists(expected)
    override suspend fun items(id: String, expected: Long) = ApiClient.customListItems(id, expected)
    override suspend fun dashboard(expected: Long) = ApiClient.dashboardStreams(expected)
    override suspend fun hub(links: List<HomeSource.HubLink>, cap: Int, expected: Long) = HubCards.cards(links, cap, expected)
    override suspend fun liveFavorites(cap: Int, expected: Long) = ApiClient.favoriteChannelCards(cap, expected)
    override suspend fun library(id: String, count: Int, mediaType: String?, expected: Long) = ApiClient.libraryGroups(id, count, sort = "created", mediaType = mediaType, expectedContext = expected)
}
