package com.strmr.tv.home

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.LibraryRef
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.sources.SourceFilterLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogueLanguageOptionsTest {
    @Test fun `no selection preserves legacy query without original-language parameters`() {
        val query = BrowseOptions().query(BrowseList.Watchlist)
        assertFalse(query.containsKey("originalLanguages")); assertFalse(query.containsKey("includeUnknownOriginalLanguage"))
    }
    @Test fun `aliases are normalized deduplicated and sorted into registered ISO3 CSV`() {
        val options = BrowseOptions(originalLanguages = setOf("ger", "de", "fre", "fra", "EN"))
        assertEquals("deu,eng,fra", options.query(BrowseList.Watchlist)["originalLanguages"])
        assertEquals(setOf("deu", "eng", "fra"), options.canonical()!!.originalLanguages)
    }
    @Test fun `unknown-only and explicit OR remain distinct from no filter`() {
        val only = BrowseOptions(includeUnknownOriginalLanguage = true).query(BrowseList.PermanentPrequeue)
        assertFalse(only.containsKey("originalLanguages")); assertEquals("true", only["includeUnknownOriginalLanguage"])
        val both = BrowseOptions(originalLanguages = setOf("fra"), includeUnknownOriginalLanguage = true).query(BrowseList.Watchlist)
        assertEquals("fra", both["originalLanguages"]); assertEquals("true", both["includeUnknownOriginalLanguage"])
    }
    @Test fun `original-language options are sent on exactly three stored list types`() {
        val options = BrowseOptions(originalLanguages = setOf("spa"), includeUnknownOriginalLanguage = true)
        val supported = listOf(BrowseList.Custom("synthetic", "Synthetic"), BrowseList.Watchlist, BrowseList.PermanentPrequeue)
        supported.forEach { assertEquals("spa", options.query(it)["originalLanguages"]) }
        val unsupported = listOf(BrowseList.ContinueWatching, BrowseList.Hidden, BrowseList.TopTen(), BrowseList.Genre(28,"Synthetic",BrowseKind.MOVIES), BrowseList.Remote("synthetic","Synthetic",mapOf("source" to "tmdb-list")), BrowseList.Library(LibraryRef("synthetic","Synthetic")))
        unsupported.forEach { assertFalse(options.query(it).containsKey("originalLanguages")); assertFalse(options.query(it).containsKey("includeUnknownOriginalLanguage")) }
    }
    @Test fun `unregistered tags and unknown language tokens never become a partial filter`() {
        for (code in listOf("zzz", "und", "mul", "zxx", "de-DE", "", "fra,eng")) assertNull(BrowseOptions(originalLanguages = setOf("eng", code)).canonical())
        assertEquals(setOf("nob"), BrowseOptions(originalLanguages = setOf("nob")).canonical()!!.originalLanguages)
    }
    @Test fun `language count bound is after canonical deduplication and unknown is separate`() {
        val codes = SourceFilterLanguages.choices().take(33).toSet()
        assertEquals(33,codes.size); assertNull(BrowseOptions(originalLanguages = codes).canonical())
        assertNotNull(BrowseOptions(originalLanguages = codes.take(32).toSet(), includeUnknownOriginalLanguage = true).canonical())
    }
    @Test fun `clearing catalogue language preserves other filter and sort choices`() {
        val original = BrowseOptions(sort=BrowseSort.NAME,kind=BrowseKind.MOVIES,genres=setOf("Drama"),watch=WatchFilter.UNWATCHED,originalLanguages=setOf("fra"),includeUnknownOriginalLanguage=true)
        val cleared = BrowseOriginalLanguageMenu.toggle(original, listOf("fra"), 0)
        assertFalse(cleared.originalLanguageActive); assertEquals(original.genres,cleared.genres); assertEquals(original.kind,cleared.kind); assertEquals(original.sort,cleared.sort); assertEquals(original.watch,cleared.watch)
    }
    @Test fun `shared TV phone choices keep unknown separate and catalogue labels honest`() {
        val initial = BrowseOptions(); val codes = listOf("fra","spa")
        assertEquals(setOf(0),BrowseOriginalLanguageMenu.selected(initial,codes))
        val both = BrowseOriginalLanguageMenu.toggle(BrowseOriginalLanguageMenu.toggle(initial,codes,1),codes,2)
        assertEquals(setOf(1,2),BrowseOriginalLanguageMenu.selected(both,codes)); assertEquals(setOf("fra"),both.originalLanguages); assertTrue(both.includeUnknownOriginalLanguage)
        assertFalse(BrowseOriginalLanguageMenu.label(both).contains("audio",true))
    }
    @Test fun `actual request builder carries filter on later page and random offset unchanged`() {
        val options = BrowseOptions(originalLanguages=setOf("fr"),includeUnknownOriginalLanguage=true)
        for ((limit,offset) in listOf(60 to 0,60 to 60,1 to 81)) {
            val query = browseQuery(BrowseList.Watchlist,limit,offset,mapOf("hideWatched" to "true"),options)
            assertEquals("watchlist",query["source"]); assertEquals("fra",query["originalLanguages"]); assertEquals("true",query["includeUnknownOriginalLanguage"])
            assertEquals(offset.toString(),query["offset"]); assertEquals(limit.toString(),query["limit"]); assertEquals("true",query["hideWatched"])
        }
    }
    @Test fun `actual stale stored list wrapper rejects before Android settings or HTTP`() = runTest {
        val expected = ProfileDataContext.current; ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        try { ApiClient.browsePage(BrowseList.Watchlist,60,options=BrowseOptions(originalLanguages=setOf("fra")),expectedContext=expected); fail() }
        catch (_: CancellationException) { }
    }
    @Test fun `strict optional marker does not drop legacy rows or promote malformed values`() {
        for (marker in listOf("",",\"originalLanguageFilterSupported\":false",",\"originalLanguageFilterSupported\":\"true\"",",\"originalLanguageFilterSupported\":null",",\"originalLanguageFilterSupported\":{}")) {
            val page = decodeBrowsePage("{\"items\":[{\"id\":\"synthetic\",\"name\":\"Synthetic\"}],\"total\":1$marker}",BrowseList.Watchlist)
            assertEquals(1,page.entries.size); assertFalse(page.originalLanguageFilterSupported)
        }
        assertTrue(decodeBrowsePage("{\"items\":[],\"total\":0,\"originalLanguageFilterSupported\":true}",BrowseList.Watchlist).originalLanguageFilterSupported)
    }
    @Test fun `filtered stored total takes precedence while progressive legacy sourceTotal stays unchanged`() {
        val raw = "{\"items\":[],\"total\":2,\"sourceTotal\":100,\"originalLanguageFilterSupported\":true}"
        assertEquals(2,decodeBrowsePage(raw,BrowseList.Watchlist).total)
        assertEquals(100,decodeBrowsePage(raw,BrowseList.Remote("synthetic","Synthetic",mapOf("source" to "mdblist"))).total)
        assertFalse(decodeBrowsePage(raw,BrowseList.Remote("synthetic","Synthetic",mapOf("source" to "mdblist"))).originalLanguageFilterSupported)
    }
}
