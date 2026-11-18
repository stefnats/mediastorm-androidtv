package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.*
import com.strmr.tv.home.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Profile-owned Lists tab reads. A completed attempt with an error is not an empty success. */
object ListsTabStore {
    private var owner: Long? = null
    private var storedShelves by mutableStateOf<List<ShelfConfig>?>(null)
    private var storedContinue by mutableStateOf<List<ContinueWatchingItem>?>(null)
    private var storedLibraries by mutableStateOf<List<LibraryInfo>>(emptyList())
    private var attempted by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    val shelves get() = storedShelves.takeIf { owner == ProfileDataContext.current }
    val continueWatching get() = storedContinue.takeIf { owner == ProfileDataContext.current }
    val libraries get() = storedLibraries.takeIf { owner == ProfileDataContext.current }.orEmpty()
    val isLoaded get() = owner == ProfileDataContext.current && attempted
    val loadError get() = error.takeIf { owner == ProfileDataContext.current }
    private var generation = 0L
    private class Flight(val owner: Long, val generation: Long) {
        val done = CompletableDeferred<Unit>()
        var job: Job? = null
    }
    private var active: Flight? = null

    suspend fun refresh(expectedContext: Long = ProfileDataContext.current) = refresh(expectedContext, ServerListsTabReads)
    internal suspend fun refresh(expected: Long, access: ListsTabReads) = refreshOwned(expected) { readListsTabSnapshot(expected, access) }
    /** Existing test seam retains the same actual publication/sharing gate. */
    internal suspend fun refresh(load: suspend () -> ListsTabSnapshot) = refreshOwned(ProfileDataContext.current, load)

    private suspend fun refreshOwned(expected: Long, load: suspend () -> ListsTabSnapshot): Unit = supervisorScope {
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
        active?.takeIf { it.owner == expected && it.generation == generation }?.let { flight ->
            flight.done.await(); ProfileDataContext.requireCurrent(expected); return@supervisorScope
        }
        active?.job?.cancel()
        val flight = Flight(expected, ++generation); active = flight
        fun current() = active === flight && flight.generation == generation && expected == ProfileDataContext.current
        val child = async(start = CoroutineStart.LAZY) {
            currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
            load().also { currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected) }
        }
        flight.job = child
        try {
            child.start(); val snapshot = child.await()
            if (current()) {
                if (owner != expected) { storedShelves = null; storedContinue = null; storedLibraries = emptyList() }
                owner = expected
                snapshot.shelves?.let { storedShelves = it.toList() }
                snapshot.continueWatching?.let { storedContinue = it.toList() }
                snapshot.libraries?.let { storedLibraries = it.toList() }
                attempted = true
                error = if (snapshot.failed) "Some lists could not be refreshed. Try again." else null
            }
        } catch (cancelled: CancellationException) {
            flight.done.cancel(cancelled); throw cancelled
        } catch (failure: Exception) {
            if (current()) {
                if (owner != expected || failure.vodDenial != null) { storedShelves = null; storedContinue = null; storedLibraries = emptyList() }
                owner = expected; attempted = true
                error = failure.vodDenial?.message ?: "Could not refresh your lists. Try again."
            }
        } finally {
            child.cancel()
            if (active === flight) active = null
            if (!flight.done.isCompleted) flight.done.complete(Unit)
        }
    }

    fun reset() {
        generation++; active?.job?.cancel(); active?.done?.cancel(); active = null
        owner = null; storedShelves = null; storedContinue = null; storedLibraries = emptyList(); attempted = false; error = null
    }
}

internal data class ListsTabSnapshot(
    val shelves: List<ShelfConfig>? = null,
    val continueWatching: List<ContinueWatchingItem>? = null,
    val libraries: List<LibraryInfo>? = null,
    val failed: Boolean = false,
)

internal interface ListsTabReads {
    suspend fun shelves(expected: Long): List<ShelfConfig>
    suspend fun continuing(expected: Long): List<ContinueWatchingItem>
    suspend fun libraries(expected: Long): List<LibraryInfo>
}

/** Ordinary component failure preserves existing rows; cancellation and policy denial stop all children. */
internal suspend fun readListsTabSnapshot(expected: Long, access: ListsTabReads): ListsTabSnapshot = coroutineScope {
    suspend fun <T> read(action: suspend () -> T): Result<T> {
        currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected)
        return try {
            Result.success(action().also { currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected) })
        } catch (failure: Exception) { failure.rethrowVodDenial(); Result.failure(failure) }
    }
    val shelves = async { read { access.shelves(expected) } }
    val continuing = async { read { access.continuing(expected) } }
    val libraries = async { read { access.libraries(expected) } }
    val a = shelves.await(); val b = continuing.await(); val c = libraries.await()
    ListsTabSnapshot(a.getOrNull(), b.getOrNull(), c.getOrNull(), a.isFailure || b.isFailure || c.isFailure)
}

private object ServerListsTabReads : ListsTabReads {
    override suspend fun shelves(expected: Long) = ApiClient.homeSettings(expectedContext = expected).shelves
    override suspend fun continuing(expected: Long) = readListsTabContinue(expected) { path, owner -> ApiClient.sendRaw("GET", path, expectedContext = owner) }
    override suspend fun libraries(expected: Long) = ApiClient.libraries(expectedContext = expected, strict = true)
}

/** Same Set Aside alias filtering as Continue Watching, with strict menu-independent full rows. */
internal suspend fun readListsTabContinue(expected: Long, get: suspend (String, Long) -> String): List<ContinueWatchingItem> = coroutineScope {
    suspend fun raw(suffix: String): String {
        currentCoroutineContext().ensureActive()
        val path = ApiClient.inContext(expected) { "${ApiClient.profilePath}/$suffix" }
        return get(path, expected).also { currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected) }
    }
    val rows = async { decodeListsTabContinue(raw("history/continue")) }
    val parked = async {
        val lists = listsTabRows(raw("custom-lists")).map { row -> CustomList(listsTabText(row, "id"), listsTabText(row, "name")) }
        val candidates = lists.filter { it.isSetAside }; check(candidates.size <= 1) { "Ambiguous reserved list" }
        candidates.singleOrNull()?.let { list ->
            listsTabRows(raw("custom-lists/${ApiClient.segment(list.id)}/items")).map { row ->
                val kind = listsTabText(row, "mediaType"); require(kind == "movie" || kind == "series")
                ApiJson.decodeFromJsonElement<WatchlistItem>(row).also { listsTabText(row, "id"); listsTabText(row, "name", allowEmpty = true); listsTabAliases(row) }
            }
        }.orEmpty()
    }
    val watching = rows.await(); val aside = parked.await()
    watching.filterNot { row -> aside.any { item ->
        item.id == row.seriesId || listOf("tmdb", "imdb", "tvdb").any { key ->
            item.externalIds?.get(key)?.takeIf { it.isNotEmpty() }?.let { it == row.externalIds?.get(key) } == true
        }
    } }
}
internal fun decodeListsTabContinue(raw: String): List<ContinueWatchingItem> = listsTabRows(raw, allowNull = true).map { row ->
    listsTabText(row, "seriesId"); listsTabText(row, "seriesTitle", allowEmpty = true); listsTabAliases(row)
    ApiJson.decodeFromJsonElement<ContinueWatchingItem>(row)
}
private fun listsTabRows(raw: String, allowNull: Boolean = false): List<JsonObject> {
    val value = ApiJson.parseToJsonElement(raw)
    if (allowNull && value == JsonNull) return emptyList()
    return (value as? JsonArray ?: error("Invalid list response")).map { it as? JsonObject ?: error("Invalid list row") }
}
private fun listsTabText(row: JsonObject, key: String, allowEmpty: Boolean = false): String {
    val text = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid list field")
    require(allowEmpty || text.isNotBlank()); return text
}
private fun listsTabAliases(row: JsonObject) {
    row["externalIds"]?.takeUnless { it == JsonNull }?.let { value ->
        (value as? JsonObject ?: error("Invalid aliases")).values.forEach { require((it as? JsonPrimitive)?.isString == true) }
    }
}
