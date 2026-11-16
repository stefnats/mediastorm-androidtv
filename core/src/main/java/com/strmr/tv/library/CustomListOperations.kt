package com.strmr.tv.library

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.WatchlistItem
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** Original dialog/selection lifetime. This private receipt is not server mutation authority. */
class CustomListOperations(
    expectedContext: Long = ProfileDataContext.current,
    private val target: DetailsTarget? = null,
    private val selected: () -> Boolean = { true },
) {
    private val owner = try { PlaybackSessionContext.capture(expectedContext) } catch (_: CancellationException) { null }
    private var closed = false
    private val operations = mutableMapOf<String, CustomListOperation>()
    val isCurrent: Boolean get() {
        if (closed || owner?.isCurrent() != true) return false
        if (!selected()) { close(); return false }
        return true
    }
    fun begin(listId: String? = null, item: WatchlistItem? = null, key: String = listId ?: "create"): CustomListOperation? {
        if (!isCurrent) return null
        operations.remove(key)?.cancel()
        return CustomListOperation(requireNotNull(owner), listId, item?.let { it.mediaType to it.id }, target,
            current = { operation -> isCurrent && operations[key] === operation }).also { operations[key] = it }
    }
    fun retire(key: String) { operations.remove(key)?.cancel() }
    fun close() { closed = true; operations.values.toList().forEach { it.cancel() }; operations.clear() }
    suspend fun <T> run(operation: CustomListOperation, action: suspend (CustomListOperation) -> T): T = coroutineScope {
        operation.check()
        val child = async(start = CoroutineStart.LAZY) {
            currentCoroutineContext().ensureActive(); operation.check()
            val value = action(operation)
            currentCoroutineContext().ensureActive(); operation.check(); value
        }
        operation.register(child)
        try { child.start(); child.await() } finally { operation.unregister(child) }
    }
}

/** Immutable selected resource and target; newer operations or a closed surface retire it. */
class CustomListOperation internal constructor(
    val owner: PlaybackSessionContext,
    private val listId: String?,
    private val itemKey: Pair<String, String>?,
    private val target: DetailsTarget?,
    private val current: (CustomListOperation) -> Boolean,
) {
    private var retired = false
    private var created: String? = null
    private val jobs = mutableSetOf<Job>()
    fun check() { owner.requireCurrent(); if (retired || !current(this)) throw CancellationException("List operation changed") }
    fun publish(action: () -> Unit): Boolean {
        if (!owner.isCurrent()) return false
        return owner.use { if (retired || !current(this)) false else { action(); true } }
    }
    internal fun register(job: Job) { check(); jobs.add(job) }
    internal fun unregister(job: Job) { jobs.remove(job) }
    internal fun cancel() { retired = true; jobs.toList().forEach { it.cancel() } }
    internal fun requireCreate() { check(); require(listId == null && created == null) { "List operation does not create a list" } }
    internal fun created(id: String) { requireCreate(); require(id.isNotBlank()); created = id }
    internal fun requireList(id: String) { check(); require(id == listId || id == created) { "List selection changed" } }
    internal fun requireTarget(value: DetailsTarget) { check(); require(target === value) { "Title selection changed" } }
    internal fun requireItem(value: WatchlistItem) { check(); require(itemKey == (value.mediaType to value.id)) { "List item changed" } }
}

internal data class CustomListRequest(val method: String, val path: String, val body: JsonObject?, val expected: Long)
/** Actual API request constructor, independently testable without Android credential stores. */
internal fun customListRequest(method: String, suffix: String, body: JsonObject?, owner: PlaybackSessionContext, operation: CustomListOperation?): CustomListRequest = owner.use {
    operation?.check()
    CustomListRequest(method, "${ApiClient.profilePath}/custom-lists$suffix", body, owner.epoch)
}

internal suspend fun <T> checkedListStage(owner: PlaybackSessionContext, operation: CustomListOperation?, action: suspend () -> T): T {
    currentCoroutineContext().ensureActive(); owner.requireCurrent(); operation?.check()
    val value = action()
    currentCoroutineContext().ensureActive(); owner.requireCurrent(); operation?.check()
    return value
}

/** Compose remember keys must distinguish equal-valued replacement target objects. */
class CustomListSelectionKey(private val value: Any?) {
    override fun equals(other: Any?) = other is CustomListSelectionKey && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}

/** Actual compound write adapter: loss after an accepted create never starts an add or rollback. */
internal suspend fun <T> createListAndAdd(operation: CustomListOperation, create: suspend () -> T, add: suspend (T) -> Unit): T {
    val list = checkedListStage(operation.owner, operation, create)
    checkedListStage(operation.owner, operation) { add(list) }
    return list
}
