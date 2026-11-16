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
    private var resolved: String? = null
    private val resolvedItems = mutableSetOf<Pair<String, String>>()
    private val jobs = mutableSetOf<Job>()
    fun check() { owner.requireCurrent(); if (retired || !current(this)) throw CancellationException("List operation changed") }
    fun publish(action: () -> Unit): Boolean {
        if (!owner.isCurrent()) return false
        return owner.use { if (retired || !current(this)) false else { action(); true } }
    }
    internal fun register(job: Job) { check(); jobs.add(job) }
    internal fun unregister(job: Job) { jobs.remove(job) }
    internal fun cancel() { retired = true; jobs.toList().forEach { it.cancel() } }
    internal fun requireCreate() { check(); require(listId == null && created == null && resolved == null) { "List operation does not create a list" } }
    internal fun created(id: String) { requireCreate(); require(id.isNotBlank()); created = id }
    internal fun requireList(id: String) { check(); require(id == listId || id == created || id == resolved) { "List selection changed" } }
    internal fun resolvedList(id: String) {
        check()
        require(id.isNotBlank() && listId == null)
        if (created != null) require(created == id)
        else if (resolved != null) require(resolved == id)
        else resolved = id
    }
    internal fun resolvedItem(item: WatchlistItem) {
        check()
        require(resolved != null || created != null)
        resolvedItems.add(item.mediaType to item.id)
    }
    internal fun requireTarget(value: DetailsTarget) { check(); require(target === value) { "Title selection changed" } }
    internal fun requireItem(value: WatchlistItem) { check(); require(itemKey == (value.mediaType to value.id) || (value.mediaType to value.id) in resolvedItems) { "List item changed" } }
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

/** Captured when the actual title/menu selection is made, before deferred composition or animation. */
class TitleMenuSelection(
    val card: com.strmr.tv.home.HomeCard,
    expectedContext: Long,
    parent: Job,
    val continueWatching: Boolean = false,
) {
    val origin = TitleListOrigin(card.target, expectedContext, parent)
    val isCurrent get() = origin.isCurrent
    fun close() = origin.close()
}

/** An unchosen menu intent closes with its surface. It is not content or server mutation authority. */
class TitleListOrigin(
    val target: DetailsTarget?, val context: Long, internal val parent: Job,
    private val selected: () -> Boolean = { true },
) {
    private val owner = try {
        PlaybackSessionContext.capture(context)
    } catch (_: CancellationException) {
        null
    }
    private var open = true
    private var chosen = false
    private var pending: TitleListClick? = null
    val isCurrent get() = open && parent.isActive && owner?.isCurrent() == true && selected()

    internal fun selectionCurrent(): Boolean = parent.isActive && selected()

    fun accept(scope: CoroutineScope): TitleListClick? {
        if (!isCurrent || chosen || target == null || scope.coroutineContext[Job]?.isActive == false) return null
        chosen = true
        return TitleListClick(this, scope, requireNotNull(owner), target).also { pending = it }
    }

    internal fun detach(click: TitleListClick): Boolean {
        if (!isCurrent || pending !== click) return false
        pending = null
        return true
    }

    fun dismiss(action: () -> Unit): Boolean {
        val current = owner ?: return false
        if (!current.isCurrent()) return false
        return current.use {
            if (!open || !selectionCurrent()) false else {
                close()
                action()
                true
            }
        }
    }

    internal fun abandon(click: TitleListClick) {
        if (pending === click) {
            pending = null
            chosen = false
        }
    }

    fun close() {
        open = false
        pending?.cancel()
        pending = null
    }
}

/** Accepted click: wait for a successful hide, then transfer only to the original parent's lifetime. */
class TitleListClick internal constructor(
    private val origin: TitleListOrigin,
    private val scope: CoroutineScope,
    private val owner: PlaybackSessionContext,
    private val target: DetailsTarget,
) {
    private var cancelled = false
    private var committed = false
    internal fun cancel() { cancelled = true }
    fun abandon() {
        cancelled = true
        origin.abandon(this)
    }

    fun commit(dismiss: () -> Unit, action: suspend (CustomListOperation) -> Unit): Job? {
        if (cancelled || committed || !owner.isCurrent() || scope.coroutineContext[Job]?.isActive == false || !origin.detach(this)) return null
        committed = true
        val operations = CustomListOperations(owner.epoch, target) { origin.selectionCurrent() && scope.coroutineContext[Job]?.isActive != false }
        val operation = operations.begin() ?: return null
        if (!operation.publish(dismiss)) {
            operations.close()
            return null
        }
        if (scope.coroutineContext[Job]?.isActive == false || !owner.isCurrent()) {
            operations.close()
            return null
        }
        // A real producer child cancels at cancellation start, even if another child delays completion.
        return scope.launch(origin.parent) {
            try {
                operations.run(operation, action)
            } finally {
                operations.close()
            }
        }
    }
}

/** Actual sheet animation adapter: cancellation never becomes a completion callback that runs an action. */
suspend fun commitTitleListClick(hide: suspend () -> Unit, abandon: () -> Unit = {}, commit: () -> Unit) {
    try {
        currentCoroutineContext().ensureActive()
        hide()
        currentCoroutineContext().ensureActive()
        commit()
    } catch (error: Exception) {
        abandon()
        throw error
    }
}

/** Existing menu action containers retain this same bound function object when copied or sorted. */
class TitleListAction(
    private val origin: TitleListOrigin,
    private val scope: CoroutineScope,
    private val action: suspend (CustomListOperation) -> Unit,
) : () -> Unit {
    fun accept() = origin.accept(scope)
    fun commit(click: TitleListClick, dismiss: () -> Unit) = click.commit(dismiss, action)
    override fun invoke() { accept()?.let { commit(it, {}) } }
}
