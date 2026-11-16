package com.strmr.mobile.ui.home

import org.junit.Assert.*
import org.junit.Test

/** Actual HomeStores factory helper, without MainScope/Android profile activation or Compose execution. */
class HomeEntryCacheTest {
    @Test fun `same epoch retains per-view entries and new epoch retires every view`() {
        var retired = 0; var created = 0
        val cache = HomeEntryCache(create = { _: String, _: Long -> ++created }, retire = { retired++ })
        val first = cache.get("all", 1); val movies = cache.get("movies", 1)
        assertEquals(first, cache.get("all", 1)); assertEquals(movies, cache.get("movies", 1)); assertEquals(2, created)
        assertNotEquals(first, cache.get("all", 2)); assertNotEquals(movies, cache.get("movies", 2)); assertEquals(4, created); assertEquals(2, retired)
    }
    @Test fun `same visible profile label cannot revive an earlier epoch entry`() {
        var created = 0
        val cache = HomeEntryCache(create = { _: String, _: Long -> ++created }, retire = {})
        val first = cache.get("all", 10)
        cache.get("all", 11)
        val returned = cache.get("all", 12)
        assertNotEquals(first, returned); assertEquals(3, created)
    }
}
