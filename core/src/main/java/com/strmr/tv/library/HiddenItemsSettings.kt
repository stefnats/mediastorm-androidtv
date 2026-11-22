package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.AppLog
import com.strmr.tv.home.hiddenFromContinueWatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Settings › Hidden Items: the titles hidden from Continue Watching and those hidden from this
 * profile, each with its undo. Continue Watching's list is loaded here; the profile's is
 * [HiddenItemsStore]'s, so an Unhide here reaches every screen. Both undos go through
 * [TitleActions] (its toast, and its change count, which reloads Home's Continue Watching).
 * One per screen; main thread only.
 */
class HiddenItemsSettings(
    private val expectedContext: Long = ProfileDataContext.current,
    private val readContinue: suspend (Long) -> List<HiddenItem> = { ApiClient.hiddenFromContinueWatching(it) },
) {
    private var closed = false
    private var continueSequence = 0L
    private val actions = mutableSetOf<CustomListOperations>()
    private fun checkCurrent() {
        if (closed) throw CancellationException("Hidden Items page closed")
        ProfileDataContext.requireCurrent(expectedContext)
    }
    fun close() { closed = true; actions.toList().forEach { it.close() }; actions.clear() }

    sealed interface Load {
        data object Loading : Load
        data object Loaded : Load
        data class Failed(val message: String) : Load
    }

    var continueLoad by mutableStateOf<Load>(Load.Loading)
        private set
    var continueItems by mutableStateOf<List<HiddenItem>>(emptyList())
        private set
    var profileLoad by mutableStateOf<Load>(Load.Loading)
        private set
    val profileItems: List<HiddenItem> get() = HiddenItemsStore.items

    /** Keys ([key]) of rows whose undo is in flight, so a second press does nothing. */
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set

    val isLoading: Boolean get() = continueLoad == Load.Loading || profileLoad == Load.Loading

    suspend fun load() = coroutineScope {
        launch { loadContinueWatching() }
        launch { loadProfile() }
    }

    suspend fun loadContinueWatching() {
        checkCurrent()
        val sequence = ++continueSequence
        continueLoad = Load.Loading
        continueLoad = try {
            val items = readContinue(expectedContext)
            checkCurrent()
            if (sequence == continueSequence) continueItems = items
            Load.Loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkCurrent()
            AppLog.w("HiddenItems", "continue watching list failed")
            Load.Failed("Couldn't load")
        }
    }

    suspend fun loadProfile() {
        checkCurrent()
        profileLoad = Load.Loading
        HiddenItemsStore.refresh(expectedContext)
        checkCurrent()
        profileLoad = if (HiddenItemsStore.isLoaded) Load.Loaded else Load.Failed("Couldn't load")
    }

    /** Shows the title in Continue Watching again; true when it left this list. */
    suspend fun showInContinueWatching(item: HiddenItem): Boolean = act(key(Section.ContinueWatching, item)) {
        ownedAction(item) { target, operation -> TitleActions.showInContinueWatching(target, operation) }.also { done ->
            checkCurrent()
            if (done) { continueSequence++; continueItems = continueItems.filterNot { it.id == item.id && it.mediaType == item.mediaType } }
        }
    }

    /** Unhides the title from this profile; true when it left this list. */
    suspend fun unhide(item: HiddenItem): Boolean = act(key(Section.Profile, item)) {
        ownedAction(item) { target, operation -> TitleActions.unhide(target, operation) }
    }

    private suspend fun ownedAction(item: HiddenItem, action: suspend (com.strmr.tv.api.DetailsTarget, CustomListOperation) -> Boolean): Boolean {
        checkCurrent()
        val target = item.toTarget()
        val operations = CustomListOperations(expectedContext, target) { !closed }
        actions += operations
        try {
            val operation = operations.begin(key = "hidden-item") ?: return false
            return operations.run(operation) { action(target, it) }
        } finally { actions -= operations; operations.close() }
    }

    private suspend fun act(key: String, action: suspend () -> Boolean): Boolean {
        checkCurrent()
        if (key in busy) return false
        busy = busy + key
        return try { action() } finally { busy = busy - key }
    }

    enum class Section(val prefix: String) { ContinueWatching("cw"), Profile("hp") }

    companion object {
        /** A row's stable key within its section (for lazy lists and focus). */
        fun key(section: Section, item: HiddenItem) = "${section.prefix}:${item.mediaType}:${item.id}"

        /** The section's placeholder row (loading, empty or failed). */
        fun statusKey(section: Section) = "${section.prefix}:status"

        /**
         * Where focus goes after the row at [removedIndex] left a list now [remaining] long:
         * the row that took its place, else the one above; null when the list is empty.
         */
        fun focusIndexAfterRemoval(removedIndex: Int, remaining: Int): Int? =
            if (remaining <= 0) null else removedIndex.coerceIn(0, remaining - 1)
    }
}
