package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.CustomList
import com.strmr.tv.home.WatchlistItem
import com.strmr.tv.home.customListItems
import com.strmr.tv.home.customLists
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reserved original-profile list. Accepted actions retain their original target and parent lifetime. */
object SetAsideStore {
    const val NAME = "Set Aside"
    private var storedItems by mutableStateOf<List<WatchlistItem>>(emptyList())
    private var itemsOwner: Long? = null
    val items: List<WatchlistItem> get() = storedItems.takeIf { itemsOwner == ProfileDataContext.current }.orEmpty()
    private val refreshes = ProfileRefresh()
    private var revision = 0L

    private fun invalidate() { revision++; refreshes.reset() }
    private val workflow = workflow(ApiSetAsideAccess)
    internal fun workflow(access: SetAsideAccess) = SetAsideWorkflow(access,
        before = { invalidate() },
        publish = { operation, items -> operation.publish { storedItems = items; itemsOwner = operation.owner.epoch } },
    )

    suspend fun refresh(expectedContext: Long = ProfileDataContext.current) {
        val owner = PlaybackSessionContext.capture(expectedContext)
        refreshOwned(owner) {
            val lists = ApiClient.customLists(expectedContext)
            owner.requireCurrent()
            val candidates = lists.filter { it.isSetAside }
            check(candidates.size <= 1) { "Set Aside list is ambiguous" }
            val list = candidates.singleOrNull()
            if (list == null) emptyList() else ApiClient.customListItems(list.id, expectedContext)
        }
    }

    private suspend fun refreshOwned(owner: PlaybackSessionContext, load: suspend () -> List<WatchlistItem>?) {
        val requested = revision
        refreshes.run(load = { checkedListStage(owner, null, load) }, success = { items -> owner.use {
            if (items != null && requested == revision) {
                storedItems = items
                itemsOwner = owner.epoch
            }
        } })
    }

    internal suspend fun refresh(load: suspend () -> List<WatchlistItem>?) = refreshOwned(PlaybackSessionContext.capture(), load)
    fun reset() { invalidate(); storedItems = emptyList(); itemsOwner = null }
    fun contains(target: DetailsTarget) = items.any { WatchlistStore.matches(it, target) }
    suspend fun add(target: DetailsTarget, operation: CustomListOperation) = workflow.add(target, operation)
    suspend fun remove(target: DetailsTarget, operation: CustomListOperation) = workflow.remove(target, operation)
}

/** The real reserved-list workflow's IO port, always carrying the original operation. */
internal interface SetAsideAccess {
    suspend fun lists(operation: CustomListOperation): List<CustomList>
    suspend fun create(operation: CustomListOperation): CustomList
    suspend fun items(id: String, operation: CustomListOperation): List<WatchlistItem>
    suspend fun add(target: DetailsTarget, id: String, operation: CustomListOperation)
    suspend fun remove(item: WatchlistItem, id: String, operation: CustomListOperation)
}

private object ApiSetAsideAccess : SetAsideAccess {
    override suspend fun lists(operation: CustomListOperation) = ApiClient.customLists(operation.owner.epoch)
    override suspend fun create(operation: CustomListOperation) = CustomListsStore.create(SetAsideStore.NAME, operation)
    override suspend fun items(id: String, operation: CustomListOperation): List<WatchlistItem> {
        operation.requireList(id)
        return ApiClient.customListItems(id, operation.owner.epoch)
    }
    override suspend fun add(target: DetailsTarget, id: String, operation: CustomListOperation) = CustomListsStore.add(target, id, operation)
    override suspend fun remove(item: WatchlistItem, id: String, operation: CustomListOperation) = CustomListsStore.remove(item, id, operation = operation)
}

/** Serializes ensure/create/mutate/read per original epoch, without blocking a replacement owner. */
internal class SetAsideWorkflow(
    private val access: SetAsideAccess,
    private val before: () -> Unit = {},
    private val publish: (CustomListOperation, List<WatchlistItem>) -> Unit,
) {
    private var mutexEpoch: Long? = null
    private var mutex = Mutex()

    private fun mutex(operation: CustomListOperation) = operation.owner.use {
        operation.check()
        if (mutexEpoch != operation.owner.epoch) {
            mutexEpoch = operation.owner.epoch
            mutex = Mutex()
        }
        mutex
    }

    private suspend fun <T> stage(operation: CustomListOperation, action: suspend () -> T) =
        checkedListStage(operation.owner, operation, action)

    private suspend fun listId(create: Boolean, operation: CustomListOperation): String? {
        val candidates = stage(operation) { access.lists(operation) }.filter { it.isSetAside }
        check(candidates.size <= 1) { "Set Aside list is ambiguous" }
        val list = candidates.singleOrNull() ?: if (create) stage(operation) { access.create(operation) } else return null
        check(list.isSetAside) { "Set Aside response is unexpected" }
        operation.resolvedList(list.id)
        return list.id
    }

    suspend fun add(target: DetailsTarget, operation: CustomListOperation) = mutate(target, operation) {
        val id = requireNotNull(listId(true, operation))
        stage(operation) { access.add(target, id, operation) }
        val items = stage(operation) { access.items(id, operation) }
        operation.publish { publish(operation, items) }
    }

    suspend fun remove(target: DetailsTarget, operation: CustomListOperation) = mutate(target, operation) {
        val id = listId(false, operation)
        if (id == null) {
            operation.publish { publish(operation, emptyList()) }
            return@mutate
        }
        val matched = stage(operation) { access.items(id, operation) }.filter { WatchlistStore.matches(it, target) }
        for (item in matched) {
            operation.resolvedItem(item)
            stage(operation) { access.remove(item, id, operation) }
        }
        val items = stage(operation) { access.items(id, operation) }
        operation.publish { publish(operation, items) }
    }

    private suspend fun mutate(target: DetailsTarget, operation: CustomListOperation, action: suspend () -> Unit) {
        operation.requireTarget(target)
        mutex(operation).withLock { stage(operation) { before(); action() } }
    }
}
