package com.strmr.tv.library

import com.strmr.tv.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual singleton mutation/read paths with synthetic IO; no Android send or server authorization claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenMutationContextTest {
    private val target = DetailsTarget("tmdb:movie:17", "Synthetic", true, tmdbId = "17")
    private fun operation(value: DetailsTarget = target) = CustomListOperations(target = value).begin()!!
    private class Writes : HiddenWrites {
        val hides = mutableListOf<DetailsTarget>(); val deletes = mutableListOf<HiddenItem>()
        var hiding: suspend (DetailsTarget) -> HiddenItem = { HiddenItem(it.titleId, name = it.name) }
        var deleting: suspend (HiddenItem) -> Unit = {}
        override suspend fun hide(target: DetailsTarget, operation: CustomListOperation): HiddenItem { hides += target; return hiding(target) }
        override suspend fun delete(item: HiddenItem, operation: CustomListOperation) { deletes += item; deleting(item) }
    }
    @Before fun before() { HiddenItemsStore.reset() }
    @After fun after() { HiddenItemsStore.reset() }
    private suspend fun canceled(read: suspend () -> Unit) { try { read(); fail("Retired write survived") } catch (_: CancellationException) { } }
    @Test fun `actual hide merges owned item and unhide removes its aliases`() = runTest {
        val access = Writes(); val op = operation()
        HiddenItemsStore.pageItems(op.owner.epoch) { listOf(HiddenItem("legacy", externalIds = mapOf("tmdb" to "17")), HiddenItem("other")) }
        HiddenItemsStore.hideOwned(target, op, access)
        assertEquals(listOf("other", target.titleId), HiddenItemsStore.items.map { it.id })
        HiddenItemsStore.unhideOwned(target, operation(), access)
        assertEquals(listOf(target.titleId), access.deletes.map { it.id }); assertEquals("other", HiddenItemsStore.items.single().id)
    }
    @Test fun `queued old context writes perform zero IO`() = runTest {
        val op = operation(); val access = Writes()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { HiddenItemsStore.hideOwned(target, op, access) }; canceled { HiddenItemsStore.unhideOwned(target, op, access) }
        assertTrue(access.hides.isEmpty()); assertTrue(access.deletes.isEmpty())
    }
    @Test fun `equal copied target cannot rebind an original receipt`() = runTest {
        val access = Writes()
        try { HiddenItemsStore.hideOwned(target.copy(), operation(), access); fail("Target copy accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(access.hides.isEmpty())
    }
    @Test fun `late accepted hide cannot publish into a replacement profile`() = runTest {
        val access = Writes(); val reply = CompletableDeferred<Unit>(); val op = operation()
        access.hiding = { withContext(NonCancellable) { reply.await() }; HiddenItem(it.titleId) }
        val hiding = async { HiddenItemsStore.hideOwned(target, op, access) }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); HiddenItemsStore.reset()
        HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("new-owner")) }
        reply.complete(Unit); canceled { hiding.await() }
        assertEquals("new-owner", HiddenItemsStore.items.single().id); assertEquals(1, access.hides.size)
    }
    @Test fun `first alias DELETE then operation retirement starts zero second DELETE`() = runTest {
        val access = Writes(); val operations = CustomListOperations(target = target); val op = operations.begin()!!
        HiddenItemsStore.pageItems(op.owner.epoch) { listOf(HiddenItem("one", externalIds = mapOf("tmdb" to "17")), HiddenItem("two", externalIds = mapOf("tmdb" to "17"))) }
        access.deleting = { operations.close() }
        canceled { HiddenItemsStore.unhideOwned(target, op, access) }
        assertEquals(listOf("one"), access.deletes.map { it.id }); assertEquals(2, HiddenItemsStore.items.size)
    }
    @Test fun `404 aliases count as removed but terminal denial cannot become success`() = runTest {
        val access = Writes(); val op = operation()
        HiddenItemsStore.pageItems(op.owner.epoch) { listOf(HiddenItem(target.titleId)) }
        access.deleting = { throw ApiError.Http(HttpFailure(404, "{}")) }
        HiddenItemsStore.unhideOwned(target, op, access); assertTrue(HiddenItemsStore.items.isEmpty())
        HiddenItemsStore.pageItems(op.owner.epoch) { listOf(HiddenItem(target.titleId)) }
        access.deleting = { throw ApiError.Http(HttpFailure(403, "{}")) }
        try { HiddenItemsStore.unhideOwned(target, operation(), access); fail("Denial accepted") } catch (error: ApiError) { assertEquals(403, error.statusCode) }
        assertEquals(target.titleId, HiddenItemsStore.items.single().id)
    }
    @Test fun `same-owner writes serialize and retain both independent changes`() = runTest {
        val access = Writes(); val release = CompletableDeferred<Unit>(); val second = target.copy(titleId = "tmdb:movie:18", tmdbId = "18")
        access.hiding = { if (it === target) release.await(); HiddenItem(it.titleId) }
        val first = async { HiddenItemsStore.hideOwned(target, operation(), access) }; runCurrent()
        val later = async { HiddenItemsStore.hideOwned(second, operation(second), access) }; runCurrent()
        assertEquals(1, access.hides.size); release.complete(Unit); first.await(); later.await()
        assertEquals(setOf(target.titleId, second.titleId), HiddenItemsStore.items.map { it.id }.toSet())
    }
    @Test fun `refresh begun before hide cannot roll successful mutation back`() = runTest {
        val release = CompletableDeferred<Unit>(); val access = Writes()
        val refresh = launch { HiddenItemsStore.refresh { release.await(); emptyList() } }; runCurrent()
        HiddenItemsStore.hideOwned(target, operation(), access)
        release.complete(Unit); refresh.join(); assertEquals(target.titleId, HiddenItemsStore.items.single().id)
    }
    @Test fun `full read during pending mutation cannot publish a stale singleton snapshot`() = runTest {
        val access = Writes(); val release = CompletableDeferred<Unit>(); access.hiding = { release.await(); HiddenItem(it.titleId) }
        val hiding = async { HiddenItemsStore.hideOwned(target, operation(), access) }; runCurrent()
        assertTrue(HiddenItemsStore.pageItems(ProfileDataContext.current) { emptyList() }.isEmpty())
        release.complete(Unit); hiding.await(); assertEquals(target.titleId, HiddenItemsStore.items.single().id)
        assertFalse(HiddenItemsStore.isLoaded)
    }
    @Test fun `canceled original operation cannot publish after masked remote completion`() = runTest {
        val access = Writes(); val release = CompletableDeferred<Unit>()
        access.hiding = { withContext(NonCancellable) { release.await() }; HiddenItem(it.titleId) }
        val hiding = async { HiddenItemsStore.hideOwned(target, operation(), access) }; runCurrent()
        hiding.cancel(); release.complete(Unit); canceled { hiding.await() }; assertTrue(HiddenItemsStore.items.isEmpty())
    }
    @Test fun `foreign full store is hidden immediately before asynchronous reset`() = runTest {
        HiddenItemsStore.pageItems(ProfileDataContext.current) { listOf(HiddenItem("old")) }; assertTrue(HiddenItemsStore.isLoaded)
        ProfileDataContext.invalidate(); assertTrue(HiddenItemsStore.items.isEmpty()); assertFalse(HiddenItemsStore.isLoaded)
    }
    @Test fun `unexpected server echo cannot change hidden membership`() = runTest {
        val access = Writes(); access.hiding = { HiddenItem("different") }
        try { HiddenItemsStore.hideOwned(target, operation(), access); fail("Wrong title accepted") } catch (_: IllegalStateException) { }
        assertTrue(HiddenItemsStore.items.isEmpty())
    }
}
