package com.strmr.tv.ui.lists

import com.strmr.tv.api.*
import com.strmr.tv.home.*
import com.strmr.tv.library.HiddenItemsStore
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual shared state machine with actual management decoders/negotiation, no Compose runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManagementLanguageModelTest {
    private fun wire(kind: String, name: String) = requireNotNull(javaClass.getResourceAsStream("/management-language-go/$kind/$name.json")).bufferedReader().use { it.readText() }
    private fun hidden(name: String) = decodeHiddenManagementPage(wire("hidden", "hidden-$name"))
    private fun local(name: String) = decodeLibraryCardPage(wire("local", "local-cards-$name"), requireSupport = true)
    private val library = LibraryRef("local", "Local")
    private suspend fun mapped(name: String, expected: Long) = readLibraryPage(library, BrowseOptions(), 1, 0, expected) { _, _, _, _, _, _, _ -> local(name).page }.copy(originalLanguageFilterSupported = true)

    @Test fun `actual hidden pages enable only capability controls and keep global store untouched`() = runTest {
        HiddenItemsStore.reset(); val revision = HiddenItemsStore.revision
        val offsets = mutableListOf<Int>()
        val model = ListGridModel(BrowseList.Hidden, emptyMap(), backgroundScope, readPage = { _, _, offset, _, _, _ -> offsets += offset; hidden(if (offset == 0) "selected" else "continuation") })
        model.reload(); model.loadPage()
        assertEquals(listOf(0, 1), offsets); assertEquals(2, model.total); assertEquals(2, model.cards.size)
        assertTrue(model.originalLanguageFilterSupported); assertTrue(model.cards.all { it.hiddenManagementEntry })
        assertTrue(HiddenItemsStore.items.isEmpty()); assertFalse(HiddenItemsStore.isLoaded); assertEquals(revision, HiddenItemsStore.revision)
    }
    @Test fun `hidden legacy list shows Unhide context but cannot advertise language filter`() = runTest {
        val model = ListGridModel(BrowseList.Hidden, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> BrowsePage(listOf(BrowseEntry.ItemEntry(WatchlistItem("old", name = "Old"))), 1) })
        model.reload(); assertTrue(model.cards.single().hiddenManagementEntry); assertFalse(model.originalLanguageFilterSupported)
    }
    @Test fun `supported hidden continuation cannot mix legacy array page`() = runTest {
        var calls = 0
        val model = ListGridModel(BrowseList.Hidden, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> if (calls++ == 0) hidden("selected") else BrowsePage(listOf(BrowseEntry.ItemEntry(WatchlistItem("legacy"))), 20) })
        model.reload(); model.loadPage(); assertTrue(model.entries.isEmpty()); assertEquals(0, model.fetched); assertNull(model.total); assertNotNull(model.loadError)
        model.loadPage(); assertEquals(2, calls)
    }
    @Test fun `library true marker from actual groups supports unknown source ref and filtered paging`() = runTest {
        val options = mutableListOf<BrowseOptions>(); val offsets = mutableListOf<Int>()
        val model = ListGridModel(BrowseList.Library(library), emptyMap(), backgroundScope, readPage = { _, _, offset, _, selected, expected -> options += selected; offsets += offset; mapped(if (offset == 0) "selected" else "continuation", expected) })
        model.reload(); assertTrue(model.originalLanguageFilterSupported)
        model.options = BrowseOptions(originalLanguages = setOf("fre", "haw")); model.reload(); model.loadPage()
        assertEquals(2, model.entries.size); assertEquals(2, model.total); assertEquals(listOf(0, 0, 1), offsets)
        assertTrue(options.drop(1).all { it.originalLanguages == setOf("fra", "haw") })
    }
    @Test fun `malformed active library continuation clears whole view without unfiltered followup`() = runTest {
        for (bad in listOf("{", wire("local", "local-cards-selected").replace("\"year\":2020", "\"year\":\"invalid\""), wire("local", "local-cards-selected").replace("\"groups\":[", "\"alphabetBuckets\":true,\"groups\":["))) {
            var calls = 0
            val model = ListGridModel(BrowseList.Library(library), emptyMap(), backgroundScope, readPage = { _, _, _, _, _, expected ->
                if (calls++ == 0) mapped("selected", expected) else { decodeLibraryCardPage(bad, requireSupport = true); error("Must reject malformed page") }
            })
            model.options = BrowseOptions(originalLanguages = setOf("fra")); model.reload(); model.loadPage()
            assertTrue(model.entries.isEmpty()); assertNull(model.total); assertEquals(0, model.fetched); assertNotNull(model.loadError)
            model.loadPage(); assertEquals(2, calls)
        }
    }
    @Test fun `active library marker downgrade never retries unfiltered`() = runTest {
        var calls = 0
        val model = ListGridModel(BrowseList.Library(library), emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; BrowsePage(emptyList(), 0) })
        model.options = BrowseOptions(includeUnknownOriginalLanguage = true); model.reload(); model.loadPage()
        assertEquals(1, calls); assertTrue(model.entries.isEmpty()); assertNotNull(model.loadError)
    }
    @Test fun `replaced default hidden read cancels before late unsupported can start legacy followup`() = runTest {
        val reply = CompletableDeferred<String>(); var legacyReads = 0; var reads = 0
        val model = ListGridModel(BrowseList.Hidden, emptyMap(), backgroundScope, readPage = { _, limit, offset, extra, options, expected ->
            reads++
            readHiddenManagementPage(limit, offset, extra, options, expected, null,
                readPage = { _, _ -> if (reads == 1) withContext(NonCancellable) { reply.await(); throw ApiError.Http(HttpFailure(400, "unsupported display list source")) } else wire("hidden", "hidden-selected") },
                readLegacy = { legacyReads++; error("No retired legacy demand") })
        })
        val old = async { model.reload() }; runCurrent()
        model.options = BrowseOptions(originalLanguages = setOf("fra")); model.reload()
        reply.complete("ignored"); assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, legacyReads); assertEquals(2, reads); assertEquals(1, model.entries.size); assertNull(model.loadError)
    }
    @Test fun `changed owner queued page and canceled masked response cannot expose management rows`() = runTest {
        var calls = 0
        val model = ListGridModel(BrowseList.Hidden, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> calls++; hidden("selected") })
        val pending = async { model.reload() }; ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); pending.await()
        assertEquals(0, calls); assertTrue(model.cards.isEmpty()); assertFalse(model.originalLanguageFilterSupported)
    }
    @Test fun `changed options late old rows never replace current hidden selection`() = runTest {
        val pending = CompletableDeferred<BrowsePage>(); var calls = 0
        val model = ListGridModel(BrowseList.Hidden, emptyMap(), backgroundScope, readPage = { _, _, _, _, _, _ -> if (calls++ == 0) withContext(NonCancellable) { pending.await() } else hidden("empty") })
        val old = async { model.reload() }; runCurrent(); model.options = BrowseOptions(includeUnknownOriginalLanguage = true); model.reload()
        pending.complete(hidden("selected")); assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertTrue(model.entries.isEmpty()); assertEquals(0, model.total); assertNull(model.loadError)
    }
}
