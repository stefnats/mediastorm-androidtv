package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.ui.lists.PersonalArea
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Go handler bytes; strict raw decoder and real card-coordinate consumption. */
class StoredLovedContractTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/loved-stored-go/$name")).use { it.readBytes() }
    private fun wire(name: String) = String(bytes("loved-stored-$name.json"))
    private fun rejected(raw: String) = assertTrue(runCatching { decodeStoredLovedPage(raw) }.exceptionOrNull() is ManagementPageFailure)
    private fun root() = ApiJson.parseToJsonElement(wire("selected")).jsonObject
    @Test fun `all nine real Go responses retain producer hashes and complete selection totals`() {
        val manifest = ApiJson.parseToJsonElement(String(bytes("manifest.json"))).jsonObject
        assertEquals(9, manifest.size)
        manifest.forEach { (name, expected) ->
            assertEquals(expected.jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString("") { "%02x".format(it) })
            val raw = String(bytes(name)); val offset = if (name.contains("continuation")) 1 else 0
            val page = decodeStoredLovedPage(raw, offset = offset)
            assertTrue(page.originalLanguageFilterSupported)
            assertTrue(page.entries.all { (it as BrowseEntry.TitleEntry).storedLove != null })
        }
        assertEquals(12, decodeStoredLovedPage(wire("unfiltered"), 2).total)
        assertEquals(2, decodeStoredLovedPage(wire("selected"), 1).total)
        assertEquals(2, decodeStoredLovedPage(wire("continuation"), 1, 1).total)
        assertEquals(0, decodeStoredLovedPage(wire("empty"), 50).total)
        for (name in listOf("norwegian", "rare", "unknown", "or-unknown", "random-pool")) {
            val page = decodeStoredLovedPage(wire(name), 0); assertEquals(page.total, page.entries.size)
        }
    }
    @Test fun `source actual capability total and items never default to a successful page`() {
        val good = root()
        for (key in listOf("source", "originalLanguageFilterSupported", "total", "items")) {
            rejected(JsonObject(good - key).toString())
            rejected(JsonObject(good + (key to JsonNull)).toString())
        }
        for ((key, bad) in listOf("source" to JsonPrimitive("loved"), "originalLanguageFilterSupported" to JsonPrimitive("true"), "originalLanguageFilterSupported" to JsonPrimitive(false), "total" to JsonPrimitive("2"), "total" to JsonPrimitive(-1), "items" to JsonPrimitive("rows"))) rejected(JsonObject(good + (key to bad)).toString())
        rejected("{")
    }
    @Test fun `required saved row title rank timestamp and malformed later rows reject entire page`() {
        val good = root(); val row = good.getValue("items").jsonArray.single().jsonObject
        for (key in listOf("rank", "updatedAt", "title")) rejected(JsonObject(good + ("items" to JsonArray(listOf(JsonObject(row - key))))).toString())
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive("1"), JsonPrimitive(2))) rejected(JsonObject(good + ("items" to JsonArray(listOf(JsonObject(row + ("rank" to bad)))))).toString())
        for (bad in listOf("2026-11-16", "2026-02-30T00:00:00Z", "private")) rejected(JsonObject(good + ("items" to JsonArray(listOf(JsonObject(row + ("updatedAt" to JsonPrimitive(bad)))))) ).toString())
        val title = row.getValue("title").jsonObject
        for (key in listOf("id", "name", "mediaType", "personalRating")) {
            val bad = JsonObject(row + ("title" to JsonObject(title - key)))
            rejected(JsonObject(good + ("items" to JsonArray(listOf(row, bad)))).toString())
        }
        for ((key, bad) in listOf("id" to JsonPrimitive(""), "mediaType" to JsonPrimitive("episode"), "name" to JsonPrimitive(3), "personalRating" to JsonPrimitive("like"), "year" to JsonPrimitive("2000"))) {
            rejected(JsonObject(good + ("items" to JsonArray(listOf(JsonObject(row + ("title" to JsonObject(title + (key to bad)))))))).toString())
        }
    }
    @Test fun `requested page cardinality is exact and enrichment is never consumed`() {
        val good = root(); val row = good.getValue("items").jsonArray.single().jsonObject
        assertTrue(runCatching { decodeStoredLovedPage(good.toString(), 2) }.exceptionOrNull() is ManagementPageFailure)
        val title = row.getValue("title").jsonObject + mapOf("poster" to JsonPrimitive("not-an-image"), "watchState" to JsonPrimitive("watched"), "sourceAvailability" to JsonPrimitive("fake"))
        val page = decodeStoredLovedPage(JsonObject(good + ("items" to JsonArray(listOf(JsonObject(row + ("title" to JsonObject(title))))))).toString())
        val actual = (page.entries.single() as BrowseEntry.TitleEntry).title
        assertNull(actual.poster); assertNull(actual.watchState); assertNull(actual.sourceAvailability)
    }
    @Test fun `new explicit static entry preserves legacy loved and its query`() {
        val tiles = PersonalArea.tiles(null, null, 0)
        val stored = tiles.single { it.list == BrowseList.LovedStored }
        assertNotNull(stored.art); assertEquals("Saved Love Ratings", stored.list.title)
        assertEquals(mapOf("source" to "loved"), PersonalArea.loved.query)
        assertTrue(tiles.any { it.list == PersonalArea.loved })
        assertFalse(BrowseList.LovedStored.filters)
        assertEquals("Rating updated", BrowseSort.ADDED.labelFor(BrowseList.LovedStored))
        assertEquals("Date Added", BrowseSort.ADDED.labelFor(PersonalArea.loved))
    }
}
