package com.strmr.tv.home

import org.junit.Assert.*
import org.junit.Test

class StoredMediaOptionsTest {
    private val filters = StoredMediaFilters(audioLanguage="ger", subtitleLanguage="fr", audioCodec="eac3", videoCodec="hevc", hdr="hdr10", minHeight=1080, subtitles="present", unknown="only")
    @Test fun `saved list queries carry canonical same-file criteria on every offset`() {
        val lists = listOf(BrowseList.Watchlist, BrowseList.Custom("saved", "Saved"), BrowseList.Remote("shelf", "Shelf", mapOf("source" to "mdblist-shelf", "url" to "mediastorm:custom-list:saved")))
        for (list in lists) for ((limit, offset) in listOf(60 to 0, 60 to 60, 1 to 81)) {
            val q = browseQuery(list, limit, offset, options=BrowseOptions(originalLanguages=setOf("spa"), storedMedia=filters))
            assertEquals("deu", q["audioLanguage"]); assertEquals("fra", q["subtitleLanguage"])
            assertEquals("eac3", q["audioCodec"]); assertEquals("hevc", q["videoCodec"]); assertEquals("hdr10", q["hdr"])
            assertEquals("1080", q["minHeight"]); assertEquals("present", q["subtitles"]); assertEquals("only", q["mediaUnknown"])
            assertEquals(offset.toString(), q["offset"]); assertEquals(limit.toString(), q["limit"])
        }
        assertEquals("spa", BrowseOptions(originalLanguages=setOf("spa"), storedMedia=filters).query(BrowseList.Watchlist)["originalLanguages"])
    }
    @Test fun `discovery and unsupported saved sources never get media predicates`() {
        for (list in listOf(BrowseList.ContinueWatching, BrowseList.Remote("remote", "Remote", mapOf("source" to "mdblist", "url" to "https://synthetic.invalid/list")))) {
            assertFalse(list.storedMediaEligible())
            val q = BrowseOptions(storedMedia=filters).query(list)
            assertFalse(q.containsKey("audioLanguage")); assertFalse(q.containsKey("mediaUnknown"))
        }
        assertTrue(BrowseOptions(storedMedia=StoredMediaFilters(unknown="only")).query(BrowseList.Watchlist).keys.none { it == "mediaUnknown" })
    }
    @Test fun `shared TV phone menu clears only media criteria and preserves catalogue language`() {
        var f = StoredMediaFilters()
        f = StoredMediaFilterMenu.choose(StoredMediaFilterMenu.Field.AUDIO, "fra", f)
        f = StoredMediaFilterMenu.choose(StoredMediaFilterMenu.Field.UNKNOWN, "only", f)
        assertEquals("fra", f.audioLanguage); assertEquals("only", f.unknown)
        assertEquals(listOf("include", "exclude", "only"), StoredMediaFilterMenu.choices(StoredMediaFilterMenu.Field.UNKNOWN, f).map { it.value })
        val options=BrowseOptions(originalLanguages=setOf("deu"), storedMedia=f).copy(storedMedia=StoredMediaFilters())
        assertFalse(options.storedMedia.active); assertEquals(setOf("deu"), options.originalLanguages)
        assertNull(StoredMediaFilters(minHeight=1).canonical()); assertNull(StoredMediaFilters(audioLanguage="und").canonical())
        assertNull(StoredMediaFilters(subtitles="sometimes").canonical()); assertNull(StoredMediaFilters(unknown="guess").canonical())
    }
    @Test fun `only explicit stored capability enables controls and filtered totals precede source totals`() {
        for (marker in listOf("null", "false", "\"true\"", "{}")) {
            val page=decodeBrowsePage("{\"items\":[],\"total\":2,\"sourceTotal\":100,\"storedMediaFilterSupported\":$marker}", BrowseList.Watchlist)
            assertFalse(page.storedMediaFilterSupported); assertEquals(100, page.total)
        }
        val wire="{\"items\":[],\"total\":2,\"sourceTotal\":100,\"storedMediaFilterSupported\":true,\"storedMediaFilterBasis\":\"stored_library_versions\"}"
        val page=decodeBrowsePage(wire, BrowseList.Watchlist)
        assertTrue(page.storedMediaFilterSupported); assertEquals(StoredMediaFilters.BASIS, page.storedMediaFilterBasis); assertEquals(2, page.total)
        assertFalse(decodeBrowsePage(wire, BrowseList.ContinueWatching).storedMediaFilterSupported)
    }
}
