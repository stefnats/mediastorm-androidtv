package com.strmr.tv.home

import com.strmr.tv.api.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Byte-exact actual Go handler responses, not fabricated native response fixtures. */
class ManagementLanguageContractTest {
    private fun bytes(kind: String, name: String) = requireNotNull(javaClass.getResourceAsStream("/management-language-go/$kind/$name")).use { it.readBytes() }
    private fun raw(kind: String, name: String) = String(bytes(kind, name))
    private fun ids(page: BrowsePage) = page.entries.map { (it as BrowseEntry.ItemEntry).item.id }
    private fun hidden(name: String) = decodeHiddenManagementPage(raw("hidden", "hidden-$name.json"))
    private fun local(name: String) = decodeLibraryCardPage(raw("local", "local-cards-$name.json"), requireSupport = true)
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).exceptionOrNull() is ManagementPageFailure) }

    @Test fun `all eighteen actual response bytes match immutable producer manifests`() {
        for ((kind, count) in listOf("hidden" to 10, "local" to 8)) {
            val manifest = ApiJson.parseToJsonElement(raw(kind, "manifest.json")).jsonObject
            assertEquals(count, manifest.size)
            manifest.forEach { (name, value) ->
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes(kind, name)).joinToString("") { "%02x".format(it) }
                assertEquals(name, value.jsonPrimitive.content, hash)
                if (kind == "hidden") decodeHiddenManagementPage(raw(kind, name))
                else decodeLibraryCardPage(raw(kind, name), requireSupport = true)
            }
        }
    }
    @Test fun `actual hidden selected pages retain complete filtered total and IDs`() {
        assertEquals(listOf("tmdb:movie:2"), ids(hidden("selected")))
        assertEquals(listOf("tmdb:movie:3"), ids(hidden("continuation")))
        assertEquals(2, hidden("selected").total)
        assertEquals(2, hidden("continuation").total)
        assertEquals(12, hidden("unfiltered").total)
        assertTrue(hidden("unfiltered").originalLanguageFilterSupported)
        assertEquals(0, hidden("empty").total)
        assertTrue(hidden("empty").entries.isEmpty())
    }
    @Test fun `actual hidden unknown OR rare and Norwegian replies remain server selected`() {
        for (name in listOf("unknown", "code-or-unknown", "rare", "norwegian", "random-pool", "wrongkind")) {
            val page = hidden(name)
            assertEquals(page.entries.size, page.total)
            assertTrue(page.originalLanguageFilterSupported)
        }
        assertTrue(hidden("unknown").entries.isNotEmpty())
        assertTrue(hidden("rare").entries.isNotEmpty())
    }
    @Test fun `actual local groups preserve IDs total and original raw version count`() {
        val first = local("selected"); val next = local("continuation")
        assertEquals(listOf("group-02"), first.page.groups.map { it.id })
        assertEquals(listOf("group-03"), next.page.groups.map { it.id })
        assertEquals(2, first.page.total); assertEquals(2, next.page.total)
        assertEquals(3, first.rawGroups!!.single().jsonObject.getValue("itemCount").jsonPrimitive.int)
        assertTrue(first.supported)
        assertEquals(0, local("empty").page.total)
        assertTrue(local("unfiltered").page.total > first.page.total)
    }
    @Test fun `hidden required source marker total and every raw row are strict`() {
        val good = ApiJson.parseToJsonElement(raw("hidden", "hidden-selected.json")).jsonObject
        val row = good.getValue("items").jsonArray.single().jsonObject
        fun changed(key: String, value: JsonElement?) = JsonObject(good.toMutableMap().apply { if (value == null) remove(key) else put(key, value) }).toString()
        for (value in listOf<JsonElement?>(null, JsonNull, JsonPrimitive("true"), JsonPrimitive(false), JsonPrimitive(1))) rejected { decodeHiddenManagementPage(changed("originalLanguageFilterSupported", value)) }
        for (value in listOf<JsonElement?>(null, JsonNull, JsonPrimitive("hidden2"), JsonPrimitive(1))) rejected { decodeHiddenManagementPage(changed("source", value)) }
        for (value in listOf<JsonElement?>(null, JsonNull, JsonPrimitive("2"), JsonPrimitive(-1), JsonPrimitive(0))) rejected { decodeHiddenManagementPage(changed("total", value)) }
        for (key in listOf("id", "mediaType", "hiddenAt")) for (value in listOf<JsonElement?>(null, JsonNull, JsonPrimitive(3))) {
            val broken = JsonObject(row.toMutableMap().apply { if (value == null) remove(key) else put(key, value) })
            rejected { decodeHiddenManagementPage(changed("items", JsonArray(listOf(broken, row)))) }
        }
    }
    @Test fun `hidden invalid RFC3339 optional scalar and malformed later row never produce partial page`() {
        val root = ApiJson.parseToJsonElement(raw("hidden", "hidden-selected.json")).jsonObject
        val row = root.getValue("items").jsonArray.single().jsonObject
        for ((key, value) in listOf("hiddenAt" to JsonPrimitive("2026-11-16"), "hiddenAt" to JsonPrimitive("2026-02-30T00:00:00Z"), "year" to JsonPrimitive("2020"), "name" to JsonPrimitive(false), "mediaType" to JsonPrimitive("episode"), "externalIds" to buildJsonObject { put("tmdb", 2) })) {
            val bad = JsonObject(row + (key to value))
            rejected { decodeHiddenManagementPage(JsonObject(root + ("items" to JsonArray(listOf(row, bad)))).toString()) }
        }
        rejected { decodeHiddenManagementPage("{") }
    }
    @Test fun `active library rejects malformed wrappers and every known row without lossy decoding`() {
        val good = ApiJson.parseToJsonElement(raw("local", "local-cards-selected.json")).jsonObject
        val row = good.getValue("groups").jsonArray.single().jsonObject
        for ((key, value) in listOf("total" to JsonPrimitive("2"), "total" to JsonPrimitive(0), "groups" to JsonNull, "alphabetBuckets" to JsonPrimitive("A"), "alphabetBuckets" to JsonArray(listOf(JsonPrimitive(1))))) {
            rejected { decodeLibraryCardPage(JsonObject(good + (key to value)).toString(), requireSupport = true) }
        }
        for (key in listOf("id", "title", "groupType", "libraryType", "itemCount")) {
            val broken = JsonObject(good + ("groups" to JsonArray(listOf(JsonObject(row - key)))))
            rejected { decodeLibraryCardPage(broken.toString(), requireSupport = true) }
        }
        for ((key, value) in listOf("title" to JsonPrimitive(7), "itemCount" to JsonPrimitive("3"), "year" to JsonPrimitive("2020"), "poster" to JsonPrimitive("image"), "items" to JsonArray(listOf(buildJsonObject { put("id", "file") })))) {
            val broken = JsonObject(good + ("groups" to JsonArray(listOf(row, JsonObject(row + (key to value))))))
            rejected { decodeLibraryCardPage(broken.toString(), requireSupport = true) }
        }
        rejected { decodeLibraryCardPage("{", requireSupport = true) }
    }
    @Test fun `legacy unfiltered library stays compatible but marker alone never supports remote`() {
        val raw = """{"groups":[{"id":"old","title":"Legacy"}],"total":1}"""
        assertEquals("old", decodeLibraryCardPage(raw).page.groups.single().id)
        assertFalse(decodeLibraryCardPage(raw).supported)
        rejected { decodeLibraryCardPage(raw, requireSupport = true) }
        assertFalse(decodeLibraryCardPage(raw("local", "local-cards-selected.json"), remote = true).supported)
        rejected { decodeLibraryCardPage(raw("local", "local-cards-selected.json"), remote = true, requireSupport = true) }
    }
}
