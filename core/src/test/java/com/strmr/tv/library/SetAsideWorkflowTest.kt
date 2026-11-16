package com.strmr.tv.library

import com.strmr.tv.api.*
import com.strmr.tv.home.CustomList
import com.strmr.tv.home.WatchlistItem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual reserved workflow and actual SetAsideStore publication factory with synthetic IO ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetAsideWorkflowTest {
    private val target = DetailsTarget("tmdb:movie:17", "Synthetic", true, tmdbId = "17")
    private class Access : SetAsideAccess {
        val stages = mutableListOf<Pair<String, Long>>()
        var savedLists = emptyList<CustomList>()
        var savedItems = emptyList<WatchlistItem>()
        var readLists: suspend () -> List<CustomList> = { savedLists }
        var makeList: suspend () -> CustomList = {
            CustomList("reserved-original", SetAsideStore.NAME).also { savedLists = listOf(it) }
        }
        var readItems: suspend () -> List<WatchlistItem> = { savedItems }
        override suspend fun lists(operation: CustomListOperation): List<CustomList> {
            stages += "lists" to operation.owner.epoch
            return readLists()
        }
        override suspend fun create(operation: CustomListOperation): CustomList {
            stages += "create" to operation.owner.epoch
            return makeList().also { operation.created(it.id) }
        }
        override suspend fun items(id: String, operation: CustomListOperation): List<WatchlistItem> {
            operation.requireList(id); stages += "items:$id" to operation.owner.epoch
            return readItems()
        }
        override suspend fun add(target: DetailsTarget, id: String, operation: CustomListOperation) {
            operation.requireTarget(target); operation.requireList(id); stages += "add:$id" to operation.owner.epoch
            savedItems = savedItems + WatchlistItem(target.titleId)
        }
        override suspend fun remove(item: WatchlistItem, id: String, operation: CustomListOperation) {
            operation.requireList(id); operation.requireItem(item); stages += "remove:$id:${item.id}" to operation.owner.epoch
            savedItems = savedItems - item
        }
    }
    @Before fun before() { ProfileDataContext.invalidate(); SetAsideStore.reset() }
    @After fun after() { SetAsideStore.reset(); ProfileDataContext.invalidate() }
    private fun operation(target: DetailsTarget = this.target) = CustomListOperations(target = target).begin()!!
    private suspend fun failed(action: suspend () -> Unit) {
        try { action(); fail("Invalid workflow accepted") } catch (_: IllegalStateException) { }
    }
    @Test fun `first accepted add binds actual created reserved ID and publishes fresh owned items`() = runTest {
        val access = Access(); val workflow = SetAsideStore.workflow(access); val operation = operation()
        workflow.add(target, operation)
        assertEquals(listOf("lists", "create", "add:reserved-original", "items:reserved-original"), access.stages.map { it.first })
        assertTrue(access.stages.all { it.second == operation.owner.epoch }); assertEquals(listOf(WatchlistItem(target.titleId)), SetAsideStore.items)
    }
    @Test fun `existing reserved ID from fresh discovery is used without creating or adopting another list`() = runTest {
        val access = Access().apply { savedLists = listOf(CustomList("other", "Other"), CustomList("reserved-owned", SetAsideStore.NAME)) }
        SetAsideStore.workflow(access).add(target, operation())
        assertEquals(listOf("lists", "add:reserved-owned", "items:reserved-owned"), access.stages.map { it.first })
    }
    @Test fun `ambiguous or wrong-name reserved response fails before Add`() = runTest {
        val duplicated = Access().apply { savedLists = listOf(CustomList("a", SetAsideStore.NAME), CustomList("b", SetAsideStore.NAME)) }
        failed { SetAsideStore.workflow(duplicated).add(target, operation()) }; assertEquals(listOf("lists"), duplicated.stages.map { it.first })
        val wrong = Access().apply { makeList = { CustomList("wrong-list", "Other") } }
        failed { SetAsideStore.workflow(wrong).add(target, operation()) }; assertEquals(listOf("lists", "create"), wrong.stages.map { it.first }); assertTrue(SetAsideStore.items.isEmpty())
    }
    @Test fun `ordinary or terminal discovery failure never falls back to blind create`() = runTest {
        for (error in listOf(java.io.IOException("Synthetic"), ApiError.Http(HttpFailure(403, "{}")))) {
            val access = Access().apply { readLists = { throw error } }
            try { SetAsideStore.workflow(access).add(target, operation()); fail("Discovery failure hidden") } catch (actual: Exception) { assertSame(error, actual) }
            assertEquals(listOf("lists"), access.stages.map { it.first }); assertTrue(SetAsideStore.items.isEmpty())
        }
    }
    @Test fun `two same-owner accepted first-use actions serialize discovery and create once`() = runTest {
        val gate = CompletableDeferred<Unit>(); val access = Access()
        access.makeList = { gate.await(); CustomList("reserved-original", SetAsideStore.NAME).also { access.savedLists = listOf(it) } }
        val workflow = SetAsideStore.workflow(access)
        val first = launch { workflow.add(target, operation()) }; runCurrent()
        val other = target.copy(titleId = "tmdb:movie:18", tmdbId = "18")
        val second = launch { workflow.add(other, operation(other)) }; runCurrent()
        assertEquals(listOf("lists", "create"), access.stages.map { it.first })
        gate.complete(Unit); first.join(); second.join()
        assertEquals(1, access.stages.count { it.first == "create" }); assertEquals(2, access.stages.count { it.first.startsWith("add:") })
        assertEquals(setOf(target.titleId, other.titleId), SetAsideStore.items.map { it.id }.toSet())
    }
    @Test fun `accepted Create followed by epoch loss leaves original empty list with no Add or cleanup`() = runTest {
        val gate = CompletableDeferred<Unit>(); val access = Access()
        access.makeList = { gate.await(); CustomList("accepted-empty", SetAsideStore.NAME).also { access.savedLists = listOf(it) } }
        val workflow = SetAsideStore.workflow(access)
        val first = launch { workflow.add(target, operation()) }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); gate.complete(Unit); first.join()
        assertEquals(listOf("lists", "create"), access.stages.map { it.first }); assertEquals("accepted-empty", access.savedLists.single().id); assertTrue(SetAsideStore.items.isEmpty())
    }
    @Test fun `replacement owner does not wait for old owner's workflow mutex`() = runTest {
        val oldReply = CompletableDeferred<List<CustomList>>(); val access = Access()
        var reads = 0
        access.readLists = { if (reads++ == 0) oldReply.await() else access.savedLists }
        val workflow = SetAsideStore.workflow(access)
        val old = launch { workflow.add(target, operation()) }; runCurrent()
        ProfileDataContext.invalidate()
        val fresh = launch { workflow.add(target, operation()) }; runCurrent()
        assertTrue(fresh.isCompleted); assertEquals(1, access.stages.count { it.first == "create" })
        oldReply.complete(emptyList()); old.join(); assertEquals(1, access.stages.count { it.first == "create" })
    }
    @Test fun `cancelled masked discovery cannot create or publish even at same owner`() = runTest {
        val reply = CompletableDeferred<List<CustomList>>(); val access = Access().apply { readLists = { withContext(NonCancellable) { reply.await() } } }
        val task = launch { SetAsideStore.workflow(access).add(target, operation()) }; runCurrent()
        task.cancel(); reply.complete(emptyList()); task.join()
        assertEquals(listOf("lists"), access.stages.map { it.first }); assertTrue(SetAsideStore.items.isEmpty())
    }
    @Test fun `remove resolves only original matching stored aliases and rereads strict current membership`() = runTest {
        val a = WatchlistItem("canonical-a", externalIds = mapOf("tmdb" to "17")); val b = WatchlistItem("canonical-b", externalIds = mapOf("tmdb" to "17"))
        val foreign = WatchlistItem("foreign", externalIds = mapOf("tmdb" to "99"))
        val access = Access().apply { savedLists = listOf(CustomList("reserved-owned", SetAsideStore.NAME)); savedItems = listOf(a, b, foreign) }
        SetAsideStore.workflow(access).remove(target, operation())
        assertEquals(listOf("lists", "items:reserved-owned", "remove:reserved-owned:canonical-a", "remove:reserved-owned:canonical-b", "items:reserved-owned"), access.stages.map { it.first })
        assertEquals(listOf(foreign), SetAsideStore.items)
    }
    @Test fun `missing reserved list removes no files and creates no replacement list`() = runTest {
        val access = Access(); SetAsideStore.workflow(access).remove(target, operation())
        assertEquals(listOf("lists"), access.stages.map { it.first }); assertTrue(SetAsideStore.items.isEmpty())
    }
    @Test fun `actual mutation revision prevents delayed initial refresh replacing fresh Set Aside items`() = runTest {
        val reply = CompletableDeferred<List<WatchlistItem>>()
        val old = launch { SetAsideStore.refresh { reply.await() } }; runCurrent()
        SetAsideStore.workflow(Access()).add(target, operation())
        reply.complete(listOf(WatchlistItem("stale-initial"))); old.join()
        assertEquals(listOf(WatchlistItem(target.titleId)), SetAsideStore.items)
    }
    @Test fun `original-profile projection is empty before reset observer runs`() = runTest {
        SetAsideStore.workflow(Access()).add(target, operation()); assertFalse(SetAsideStore.items.isEmpty())
        ProfileDataContext.invalidate(); assertTrue(SetAsideStore.items.isEmpty()); assertFalse(SetAsideStore.contains(target))
    }
    @Test fun `an original receipt cannot switch reserved list after discovery or remove an unobserved item`() {
        val operation = operation(); operation.resolvedList("original")
        try { operation.resolvedList("replacement"); fail("List rebind accepted") } catch (_: IllegalArgumentException) { }
        try { operation.requireItem(WatchlistItem("unknown")); fail("Unobserved item accepted") } catch (_: IllegalArgumentException) { }
    }
}
