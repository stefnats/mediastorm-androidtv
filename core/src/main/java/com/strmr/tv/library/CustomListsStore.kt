package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.strmr.tv.api.*
import com.strmr.tv.home.CustomList
import com.strmr.tv.home.WatchlistItem
import com.strmr.tv.home.customListItems
import com.strmr.tv.home.customLists
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Current-profile custom lists. Owned dialog operations never recapture a replacement profile. */
object CustomListsStore {
    private var storedLists by mutableStateOf<List<CustomList>?>(null)
    private var listOwner: Long? = null
    val lists: List<CustomList>? get() = storedLists.takeIf { listOwner == ProfileDataContext.current }
    private var picked: DetailsTarget? by mutableStateOf(null, referentialEqualityPolicy())
    private var pickedOwner: PlaybackSessionContext? = null
    var picking: DetailsTarget?
        get() = picked.takeIf { pickedOwner?.isCurrent() == true }
        set(value) { pickedOwner = value?.let { PlaybackSessionContext.capture() }; picked = value }
    fun pickingOwner(target: DetailsTarget): PlaybackSessionContext? = pickedOwner?.takeIf { picked === target && it.isCurrent() }
    fun clearPicking(target: DetailsTarget, expected: Long) {
        ApiClient.inContext(expected) { if (picked === target && pickedOwner?.epoch == expected) { picked = null; pickedOwner = null } }
    }
    data class ItemChange(val listId: String, val removed: WatchlistItem? = null)
    private val _itemChanges = MutableSharedFlow<ItemChange>(extraBufferCapacity = 16)
    val itemChanges: SharedFlow<ItemChange> = _itemChanges
    private val refreshes = ProfileRefresh()
    private var revision = 0L
    private var mutexEpoch: Long? = null
    private var mutex = Mutex()
    val userLists: List<CustomList> get() = lists.orEmpty().filterNot { it.isSetAside }

    private fun owner(operation: CustomListOperation?, expected: Long?): PlaybackSessionContext {
        if (operation != null) {
            operation.check()
            if (expected != null && expected != operation.owner.epoch) throw CancellationException("List context changed")
            return operation.owner
        }
        return PlaybackSessionContext.capture(expected ?: ProfileDataContext.current)
    }
    private fun changed() { revision++; refreshes.reset() }
    private fun mutex(owner: PlaybackSessionContext): Mutex = owner.use {
        if (mutexEpoch != owner.epoch) { mutexEpoch = owner.epoch; mutex = Mutex() }
        mutex
    }
    internal suspend fun <T> write(owner: PlaybackSessionContext, operation: CustomListOperation?, action: suspend () -> T): T {
        owner.requireCurrent(); operation?.check()
        return mutex(owner).withLock {
            checkedListStage(owner, operation) { changed(); action() }
        }
    }
    suspend fun refresh(expectedContext: Long? = null, operation: CustomListOperation? = null) {
        val owner = owner(operation, expectedContext)
        if (operation != null) refreshes.reset()
        val requestedRevision = revision
        refreshes.run(load = {
            try { checkedListStage(owner, operation) { ApiClient.customLists(owner.epoch) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (operation != null) error.rethrowVodDenial(); throw error }
        }, success = { result -> owner.use {
            operation?.check()
            if (revision == requestedRevision) { storedLists = result; listOwner = owner.epoch }
        } }, failure = { error -> if (operation != null) error.rethrowVodDenial() })
    }
    /** Existing isolated refresh test seam; no write or permission is inferred from its data. */
    internal suspend fun refresh(load: suspend () -> List<CustomList>) {
        val owner = PlaybackSessionContext.capture(); val requestedRevision = revision
        refreshes.run(load, success = { result -> owner.use { if (revision == requestedRevision) { storedLists = result; listOwner = owner.epoch } } })
    }
    fun reset() { changed(); storedLists = null; listOwner = null; picked = null; pickedOwner = null }

    suspend fun memberships(target: DetailsTarget, expectedContext: Long? = null, operation: CustomListOperation? = null): Map<String, WatchlistItem> = coroutineScope {
        val owner = owner(operation, expectedContext); operation?.requireTarget(target)
        val selected = owner.use { userLists.filter { (it.itemCount ?: 1) > 0 }.toList() }
        selected.map { list -> async {
            try {
                checkedListStage(owner, operation) { ApiClient.customListItems(list.id, owner.epoch) }
                    .firstOrNull { WatchlistStore.matches(it, target) }?.let { list.id to it }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (operation != null) error.rethrowVodDenial(); null }
        } }.awaitAll().filterNotNull().toMap().also { owner.requireCurrent(); operation?.check() }
    }

    suspend fun create(name: String, operation: CustomListOperation? = null, expectedContext: Long? = null): CustomList {
        val owner = owner(operation, expectedContext); operation?.requireCreate()
        return write(owner, operation) {
            val request = customListRequest("POST", "", buildJsonObject { put("name", name) }, owner, operation)
            val list = checkedListStage(owner, operation) { ApiClient.send<CustomList>(request.method, request.path, body = request.body, expectedContext = request.expected) }
            operation?.created(list.id); changed(); refresh(owner.epoch, operation)
            list
        }
    }
    suspend fun createAndAdd(name: String, target: DetailsTarget, operation: CustomListOperation): CustomList {
        operation.requireTarget(target)
        return createListAndAdd(operation,
            create = { create(name, operation) },
            add = { add(target, it.id, operation) },
        )
    }
    suspend fun rename(id: String, name: String, operation: CustomListOperation? = null, expectedContext: Long? = null) {
        val owner = owner(operation, expectedContext); operation?.requireList(id)
        write(owner, operation) {
            val request = customListRequest("PATCH", "/${ApiClient.segment(id)}", buildJsonObject { put("name", name) }, owner, operation)
            checkedListStage(owner, operation) { ApiClient.sendRaw(request.method, request.path, body = request.body, expectedContext = request.expected) }
            changed(); refresh(owner.epoch, operation)
        }
    }
    suspend fun delete(id: String, operation: CustomListOperation? = null, expectedContext: Long? = null) {
        val owner = owner(operation, expectedContext); operation?.requireList(id)
        write(owner, operation) {
            val request = customListRequest("DELETE", "/${ApiClient.segment(id)}", null, owner, operation)
            checkedListStage(owner, operation) { ApiClient.sendRaw(request.method, request.path, expectedContext = request.expected) }
            changed(); owner.use { operation?.check(); storedLists = lists?.filterNot { it.id == id }; listOwner = owner.epoch }
            refresh(owner.epoch, operation)
        }
    }
    suspend fun add(target: DetailsTarget, listId: String, operation: CustomListOperation? = null, expectedContext: Long? = null) {
        val owner = owner(operation, expectedContext); operation?.requireList(listId); operation?.requireTarget(target)
        write(owner, operation) {
            val body = owner.use { WatchlistStore.upsert(target) }
            val request = customListRequest("POST", "/${ApiClient.segment(listId)}/items", body, owner, operation)
            checkedListStage(owner, operation) { ApiClient.sendRaw(request.method, request.path, body = request.body, expectedContext = request.expected) }
            changed(); owner.use { operation?.check(); _itemChanges.tryEmit(ItemChange(listId)) }; refresh(owner.epoch, operation)
        }
    }
    suspend fun remove(item: WatchlistItem, listId: String, reloadGrids: Boolean = true, operation: CustomListOperation? = null, expectedContext: Long? = null) {
        val owner = owner(operation, expectedContext); operation?.requireList(listId); operation?.requireItem(item)
        write(owner, operation) {
            val request = customListRequest("DELETE", "/${ApiClient.segment(listId)}/items/${ApiClient.segment(item.mediaType)}/${ApiClient.segment(item.id)}", null, owner, operation)
            try { checkedListStage(owner, operation) { ApiClient.sendRaw(request.method, request.path, expectedContext = request.expected) } }
            catch (error: ApiError) { if (error.statusCode != 404) throw error; owner.requireCurrent(); operation?.check() }
            changed(); owner.use { operation?.check(); if (reloadGrids) _itemChanges.tryEmit(ItemChange(listId, item)) }; refresh(owner.epoch, operation)
        }
    }
    fun nameProblem(name: String, renaming: String? = null): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Enter a name for the list."
            trimmed.equals("Set Aside", ignoreCase = true) -> "“Set Aside” is reserved for titles parked out of Continue Watching."
            lists.orEmpty().any { it.id != renaming && it.name.equals(trimmed, ignoreCase = true) } -> "You already have a list called “$trimmed”."
            else -> null
        }
    }
}
