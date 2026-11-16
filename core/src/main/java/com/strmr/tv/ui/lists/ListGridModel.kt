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
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Server-paged list state. Every page/random reply belongs to immutable options and an original profile epoch. */
@Stable
class ListGridModel(
    val list: BrowseList,
    query: Map<String, String>,
    private val scope: CoroutineScope,
    private val dataContext: Long = ProfileDataContext.current,
    private val readPage: suspend (BrowseList, Int, Int, Map<String, String>, BrowseOptions, Long) -> BrowsePage = { shown, limit, offset, extra, options, expected ->
        ApiClient.browsePage(shown, limit, offset, extra, options = options, expectedContext = expected)
    },
    private val randomOffset: (Int) -> Int = { (0 until it).random() },
) {
    private val query = query.toMap()
    val isCurrent get() = dataContext == ProfileDataContext.current
    private var generation = 0L
    private var selection by mutableLongStateOf(0)
    private var optionState by mutableStateOf(BrowseOptions())
    var options: BrowseOptions
        get() = optionState
        set(value) {
            if (!isCurrent) return
            val next = value.canonical() ?: return
            if (next == optionState) return
            optionState = next; selection++; invalidateRows()
        }
    private var partState by mutableIntStateOf((list as? BrowseList.Service)?.part ?: 0)
    var part: Int
        get() = partState
        set(value) { if (isCurrent && value != partState) { partState = value; selection++; invalidateRows() } }
    private var entryState by mutableStateOf<List<BrowseEntry>>(emptyList())
    var entries: List<BrowseEntry>
        get() = if (isCurrent) entryState else emptyList()
        private set(value) { entryState = value }
    var fetched by mutableIntStateOf(0); private set
    private var totalState by mutableStateOf<Int?>(null)
    var total: Int?
        get() = if (isCurrent) totalState else null
        private set(value) { totalState = value }
    var genres by mutableStateOf<List<String>>(emptyList()); private set
    var letters by mutableStateOf<List<String>>(emptyList()); private set
    private var languageSupport by mutableStateOf(false)
    val originalLanguageFilterSupported get() = isCurrent && shown.filters && languageSupport
    var loadError by mutableStateOf<String?>(null); private set
    var actionError by mutableStateOf<String?>(null)
    var loadingFor by mutableStateOf<Any?>(null); private set
    var picking by mutableStateOf(false); private set
    private var added by mutableIntStateOf(0)
    private var blockedGeneration: Long? = null
    private var loading: Read? = null
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
            return entries.mapIndexed { i, entry -> HomeCard.from(entry).let { if (ranked) it.ranked(i + 1) else it } }
                .filter { list == BrowseList.Hidden || it.target?.let(HiddenItemsStore::isHidden) != true }
        }

    init {
        scope.launch {
            ProfileDataContext.changes.collect {
                if (!isCurrent) {
                    invalidateRows(); languageSupport = false; genres = emptyList(); letters = emptyList()
                    loadError = "Profile changed. Reopen this list."
                }
            }
        }
    }
    private fun invalidateRows() {
        generation++; randomGeneration++; picking = false
        entries = emptyList(); fetched = 0; total = null; loadError = null; blockedGeneration = null
        loading = null; loadingFor = null
    }
    private class Read(val key: Any, val generation: Long, val list: BrowseList, val options: BrowseOptions)
    private fun capture() = Read(reloadKey, generation, shown, options.copy(genres = options.genres.toSet(), originalLanguages = options.originalLanguages.toSet()))
    private fun current(read: Read) = isCurrent && read.generation == generation && read.key == reloadKey
    private suspend fun read(read: Read, limit: Int, offset: Int, extra: Map<String, String> = query): BrowsePage {
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(dataContext)
        if (!current(read)) throw CancellationException("List selection changed")
        val page = readPage(read.list, limit, offset, extra, read.options, dataContext)
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(dataContext)
        if (!current(read)) throw CancellationException("List selection changed")
        if (read.list.filters && read.options.originalLanguageActive && !page.originalLanguageFilterSupported) throw LanguageFilterUnavailable()
        return page
    }
    private class LanguageFilterUnavailable : Exception("This server cannot apply the original language filter. Clear the filter or reload this list.")
    private fun failed(read: Read, error: Exception) {
        if (!current(read)) return
        if (error is LanguageFilterUnavailable) {
            // Preserve selected intent and its clear controls; never replace it with an unfiltered page.
            entries = emptyList(); fetched = 0; total = null; blockedGeneration = generation
            loadError = error.message
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
        try {
            val offset = fetched
            val quick = requested.list.quickPageQuery
            val page = read(requested, PAGE, offset, query + quick.orEmpty())
            if (quick != null) scope.launch {
                try {
                    val full = read(requested, PAGE, offset)
                    val byId = full.entries.associateBy { HomeCard.from(it).id }
                    if (current(requested)) entries = entries.map { byId[HomeCard.from(it).id] ?: it }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { failed(requested, error) }
            }
            if (fetched == 0) { genres = page.genres; letters = page.letters }
            languageSupport = requested.list.filters && page.originalLanguageFilterSupported
            fetched += page.entries.size
            val seen = entries.map { HomeCard.from(it).id }.toMutableSet()
            entries = entries + page.entries.filter { seen.add(HomeCard.from(it).id) }
            total = page.listSize(fetched); loadError = null
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
        if (!isCurrent || loading != null || total == null || blockedGeneration == generation) return
        generation++; randomGeneration++; picking = false
        val requested = capture(); val wanted = fetched
        loading = requested; loadingFor = requested.key
        try {
            val result = mutableListOf<BrowseEntry>(); var first: BrowsePage? = null; var size: Int
            do {
                val page = read(requested, PAGE, result.size)
                if (first == null) first = page
                result += page.entries; size = page.listSize(result.size)
            } while (page.entries.isNotEmpty() && result.size < wanted && result.size < size)
            val seen = mutableSetOf<String>()
            entries = result.filter { seen.add(HomeCard.from(it).id) }; fetched = result.size; total = size
            genres = first?.genres.orEmpty(); letters = first?.letters.orEmpty(); languageSupport = requested.list.filters && first?.originalLanguageFilterSupported == true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failed(requested, error) }
        finally { if (loading === requested) { loading = null; loadingFor = null } }
    }
    fun remove(card: HomeCard) {
        if (!isCurrent) return
        val id = customId ?: return
        val entry = entries.firstOrNull { HomeCard.from(it).id == card.id } as? BrowseEntry.ItemEntry ?: return
        val requested = capture(); val previous = Triple(entries, fetched, total)
        entries = entries - entry; fetched = maxOf(0, fetched - 1); total = total?.let { maxOf(0, it - 1) }
        scope.launch {
            if (!current(requested)) return@launch
            try { CustomListsStore.remove(entry.item, id, reloadGrids = false); if (current(requested)) actionError = null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (current(requested)) { entries = previous.first; fetched = previous.second; total = previous.third; actionError = "Unable to remove this title. Try again." } }
        }
    }
    fun pickRandom(open: (HomeLink) -> Unit) {
        if (!isCurrent || picking || blockedGeneration == generation || loading != null) return
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
                    if (!current(requested) || token != randomGeneration) return@launch
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
