package com.strmr.tv.library

import com.strmr.tv.home.CustomList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the same serialized write/revision gates consumed by actual Store mutations. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomListStoreContextTest {
    @Before fun before() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    @After fun after() { ProfileDataContext.invalidate(); CustomListsStore.reset() }
    @Test fun `write start invalidates an outstanding list read at same profile`() = runTest {
        val reply = CompletableDeferred<List<CustomList>>()
        val old = launch { CustomListsStore.refresh { reply.await() } }; runCurrent()
        val receipt = CustomListOperations().begin("one")!!
        CustomListsStore.write(receipt.owner, receipt) { }
        CustomListsStore.refresh { listOf(CustomList("fresh", "Synthetic")) }
        reply.complete(listOf(CustomList("old", "Synthetic"))); old.join()
        assertEquals("fresh", CustomListsStore.lists!!.single().id)
    }
    @Test fun `writes to different rows serialize before demand`() = runTest {
        val operations = CustomListOperations(); val first = operations.begin("one")!!; val second = operations.begin("two")!!
        val reply = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val a = launch { operations.run(first) { CustomListsStore.write(it.owner, it) { order += "first"; reply.await(); order += "first-done" } } }; runCurrent()
        val b = launch { operations.run(second) { CustomListsStore.write(it.owner, it) { order += "second" } } }; runCurrent()
        assertEquals(listOf("first"), order)
        reply.complete(Unit); a.join(); b.join(); assertEquals(listOf("first", "first-done", "second"), order)
    }
    @Test fun `queued write that loses owner never executes when old mutex releases`() = runTest {
        val operations = CustomListOperations(); val first = operations.begin("one")!!; val second = operations.begin("two")!!
        val reply = CompletableDeferred<Unit>(); var demands = 0
        val a = launch { operations.run(first) { CustomListsStore.write(it.owner, it) { reply.await() } } }; runCurrent()
        val b = launch { operations.run(second) { CustomListsStore.write(it.owner, it) { demands++ } } }; runCurrent()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(Unit); a.join(); b.join(); assertEquals(0, demands)
    }
    @Test fun `new context write does not wait for old owner mutex`() = runTest {
        val original = CustomListOperations().begin("one")!!; val reply = CompletableDeferred<Unit>()
        val a = launch { CustomListsStore.write(original.owner, original) { reply.await() } }; runCurrent()
        ProfileDataContext.invalidate(); val fresh = CustomListOperations().begin("two")!!; var current = false
        val b = launch { CustomListsStore.write(fresh.owner, fresh) { current = true } }; runCurrent()
        assertTrue(current); assertTrue(b.isCompleted); reply.complete(Unit); a.join()
    }
    @Test fun `closing queued dialog cancels wait without running second write`() = runTest {
        val operations = CustomListOperations(); val first = operations.begin("one")!!; val second = operations.begin("two")!!
        val reply = CompletableDeferred<Unit>(); var demands = 0
        val a = launch { operations.run(first) { CustomListsStore.write(it.owner, it) { reply.await() } } }; runCurrent()
        val b = launch { operations.run(second) { CustomListsStore.write(it.owner, it) { demands++ } } }; runCurrent()
        operations.retire("two"); b.join(); reply.complete(Unit); a.join(); assertEquals(0, demands)
    }
    @Test fun `list snapshot projection hides prior owner even before reset observer runs`() = runTest {
        CustomListsStore.refresh { listOf(CustomList("private-original", "Synthetic")) }; assertEquals(1, CustomListsStore.lists!!.size)
        ProfileDataContext.invalidate(); assertNull(CustomListsStore.lists); assertTrue(CustomListsStore.userLists.isEmpty())
    }
    @Test fun `write cancellation cannot publish stored snapshot in following stage`() = runTest {
        val operations = CustomListOperations(); val receipt = operations.begin("one")!!; val reply = CompletableDeferred<Unit>()
        val a = launch { operations.run(receipt) { CustomListsStore.write(it.owner, it) { withContext(NonCancellable) { reply.await() } }; CustomListsStore.refresh { listOf(CustomList("bad", "Synthetic")) } } }
        runCurrent(); operations.close(); reply.complete(Unit); a.join(); assertNull(CustomListsStore.lists)
    }
}
