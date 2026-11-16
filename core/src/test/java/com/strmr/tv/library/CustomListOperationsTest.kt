package com.strmr.tv.library

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.WatchlistItem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual UI/Store operation adapters; epoch changes simulate retirement, not Android profile activation. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomListOperationsTest {
    @Before fun before() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    @After fun after() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    private suspend fun cancelled(action: suspend () -> Unit) {
        try { action(); fail("Retired operation accepted") } catch (_: CancellationException) { }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Different selection accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `queued old dialog cannot begin after A B A epochs`() = runTest {
        val operations = CustomListOperations(); var effects = 0
        val queued = launch { operations.begin("original")?.let { operations.run(it) { effects++ } } }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); queued.join()
        assertEquals(0, effects); assertFalse(operations.isCurrent)
    }
    @Test fun `same row replacement retires original receipt`() {
        val operations = CustomListOperations(); val original = operations.begin("one")!!
        val replacement = operations.begin("one")!!; var effects = 0
        assertFalse(original.publish { effects++ }); assertTrue(replacement.publish { effects++ }); assertEquals(1, effects)
    }
    @Test fun `different rows keep independent valid receipts`() {
        val operations = CustomListOperations(); val first = operations.begin("one")!!; val second = operations.begin("two")!!
        assertTrue(first.publish {}); assertTrue(second.publish {})
    }
    @Test fun `closed dialog rejects submit dismissal finally and error`() {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!; operations.close(); var effects = 0
        repeat(4) { assertFalse(receipt.publish { effects++ }) }; assertNull(operations.begin("one")); assertEquals(0, effects)
    }
    @Test fun `actual callback gate cannot pop a replacement page or revive old page`() {
        val original = Any(); var current = original
        val operations = CustomListOperations(selected = { current === original }); val receipt = operations.begin("one")!!
        current = Any(); var pops = 0; assertFalse(receipt.publish { pops++ })
        current = original; assertFalse(receipt.publish { pops++ }); assertEquals(0, pops)
        val fresh = CustomListOperations(selected = { current === original }); assertTrue(fresh.begin("one")!!.publish { pops++ }); assertEquals(1, pops)
    }
    @Test fun `selected resource cannot be replaced by another list`() {
        val receipt = CustomListOperations().begin("one")!!; receipt.requireList("one"); rejected { receipt.requireList("two") }
    }
    @Test fun `selected title requires original reference rather than value copy`() {
        val target = DetailsTarget("tmdb:movie:1", "Synthetic", true)
        val receipt = CustomListOperations(target = target).begin("one")!!
        receipt.requireTarget(target); rejected { receipt.requireTarget(target.copy()) }
        assertNotEquals(CustomListSelectionKey(target), CustomListSelectionKey(target.copy()))
        assertEquals(CustomListSelectionKey(target), CustomListSelectionKey(target))
    }
    @Test fun `removal preserves canonical item identity not row label`() {
        val item = WatchlistItem("tmdb:movie:1"); val receipt = CustomListOperations().begin("one", item)!!
        receipt.requireItem(item.copy(name = "Updated label"))
        rejected { receipt.requireItem(item.copy(id = "tmdb:movie:2")) }; rejected { receipt.requireItem(item.copy(mediaType = "series")) }
    }
    @Test fun `create receipt permits only returned original list`() {
        val receipt = CustomListOperations().begin()!!; receipt.requireCreate(); receipt.created("created-one"); receipt.requireList("created-one")
        rejected { receipt.requireList("replacement-two") }; rejected { receipt.created("replacement-two") }; rejected { receipt.requireCreate() }
    }
    @Test fun `same-key replacement cancels actual registered in-flight child`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!
        var cancellation = false; var publications = 0
        val job = launch { operations.run(receipt) { try { awaitCancellation() } finally { cancellation = true; it.publish { publications++ } } } }
        runCurrent(); operations.begin("one"); job.join()
        assertTrue(cancellation); assertTrue(job.isCancelled); assertEquals(0, publications)
    }
    @Test fun `closed lifetime cancels registered child even under NonCancellable parent`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!; var canceled = false
        val job = launch { withContext(NonCancellable) { try { operations.run(receipt) { try { awaitCancellation() } finally { canceled = true } } } catch (_: CancellationException) {} } }
        runCurrent(); operations.close(); job.join(); assertTrue(canceled)
    }
    @Test fun `ordinary parent cancellation remains cancellation`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!; var effects = 0
        val job = launch { operations.run(receipt) { awaitCancellation(); effects++ } }
        runCurrent(); job.cancelAndJoin(); assertEquals(0, effects)
    }
    @Test fun `masked late reply after replacement cannot publish or follow up`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!; val reply = CompletableDeferred<Unit>(); var effects = 0
        val job = launch { operations.run(receipt) { checkedListStage(it.owner, it) { withContext(NonCancellable) { reply.await() } }; effects++ } }
        runCurrent(); operations.begin("one"); reply.complete(Unit); job.join(); assertEquals(0, effects)
    }
    @Test fun `original context is checked after suspended stage before next demand`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!; val reply = CompletableDeferred<Unit>(); var effects = 0
        val job = launch { operations.run(receipt) { checkedListStage(it.owner, it) { reply.await() }; effects++ } }
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit); job.join(); assertEquals(0, effects)
    }
    @Test fun `retiring initial membership read cannot overwrite a subsequent row mutation`() = runTest {
        val operations = CustomListOperations(); val read = operations.begin(key = "load")!!; val reply = CompletableDeferred<Unit>(); var loaded = "initial"
        val job = launch { operations.run(read) { withContext(NonCancellable) { reply.await() }; it.publish { loaded = "stale" } } }
        runCurrent(); operations.retire("load"); val write = operations.begin("one")!!; write.publish { loaded = "confirmed" }
        reply.complete(Unit); job.join(); assertEquals("confirmed", loaded)
    }
    @Test fun `actual create-add adapter completes normal ordered request once`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin()!!; val stages = mutableListOf<String>()
        val result = operations.run(receipt) { createListAndAdd(it, create = { stages += "create"; "original-list" }, add = { id -> stages += "add:$id" }) }
        assertEquals("original-list", result); assertEquals(listOf("create", "add:original-list"), stages)
    }
    @Test fun `accepted create followed by owner loss leaves original empty list without add or rollback`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin()!!; val reply = CompletableDeferred<String>(); val stages = mutableListOf<String>()
        val job = launch { operations.run(receipt) { createListAndAdd(it, create = { stages += "accepted-create"; reply.await() }, add = { stages += "add" }) } }
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete("original-empty-list"); job.join()
        assertEquals(listOf("accepted-create"), stages); assertTrue(job.isCancelled)
    }
    @Test fun `create-add failure never attempts compensating delete`() = runTest {
        val receipt = CustomListOperations().begin()!!; val stages = mutableListOf<String>()
        try { createListAndAdd(receipt, create = { stages += "create"; "one" }, add = { stages += "add"; throw java.io.IOException("Synthetic") }); fail("Failure hidden") }
        catch (_: java.io.IOException) { }
        assertEquals(listOf("create", "add"), stages)
    }
    @Test fun `created list cannot be adopted after closing the original selection`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin()!!; var adds = 0
        cancelled { createListAndAdd(receipt, create = { operations.close(); "original" }, add = { adds++ }) }; assertEquals(0, adds)
    }
    @Test fun `cached target selection is owner bound and does not revive under new epoch`() {
        val target = DetailsTarget("tmdb:movie:1", "Synthetic", true); CustomListsStore.picking = target
        assertSame(target, CustomListsStore.picking); assertNotNull(CustomListsStore.pickingOwner(target)); assertNull(CustomListsStore.pickingOwner(target.copy()))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(CustomListsStore.picking); assertNull(CustomListsStore.pickingOwner(target))
        val fresh = target.copy(); CustomListsStore.picking = fresh; assertSame(fresh, CustomListsStore.picking)
    }
    @Test fun `old picker dismissal cannot clear replacement target in same epoch`() {
        val target = DetailsTarget("tmdb:movie:1", "Synthetic", true); val fresh = target.copy()
        CustomListsStore.picking = target; val expected = CustomListsStore.pickingOwner(target)!!.epoch
        CustomListsStore.picking = fresh; CustomListsStore.clearPicking(target, expected)
        assertSame(fresh, CustomListsStore.picking)
    }
}
