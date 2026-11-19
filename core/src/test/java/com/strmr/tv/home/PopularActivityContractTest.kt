package com.strmr.tv.home

import com.strmr.tv.library.ProfileDataContext
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic scalar edges are separate from the genuine backend contract resources. */
internal object PopularActivityFixture {
    val origin = BrowseList.Remote("popular-on-server", "Popular", mapOf("source" to "popular-on-server"))
    fun list(source: BrowseList = origin, query: Map<String, String> = emptyMap(), producer: Job = Job()) = requireNotNull(BrowseList.PopularOnServerCached.open(source, query, ProfileDataContext.current, producer, { true }, { "synthetic-client" }))
    fun synthetic(total: Int, offset: Int, count: Int, views: Long = 2L, titleAt: (Int) -> Pair<String, String> = { "tmdb:movie:${it + 1}" to "movie" }): String = buildJsonObject {
        put("source", "popular-on-server-cached"); put("originalLanguageFilterSupported", true); put("total", total)
        putJsonArray("alphabetBuckets") { add("S") }
        putJsonArray("items") { repeat(count) { i ->
            val (id, kind) = titleAt(offset + i)
            add(buildJsonObject { put("rank", views); putJsonObject("title") {
                put("id", id); put("name", "Synthetic stored title"); put("mediaType", kind)
                if (id.startsWith("tmdb:")) put("tmdbId", id.substringAfterLast(':').toLong())
                if (id.startsWith("tvdb:")) put("tvdbId", id.substringAfterLast(':').toLong())
                put("overview", ""); put("year", 0); put("language", ""); put("forceTitleOverlay", true)
                put("cardSubtitle", "$views ${if (views == 1L) "view" else "views"}")
            } })
        } }
    }.toString()
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/popular-on-server-cached-go/$name")).use { it.readBytes() }
    fun raw(name: String) = String(bytes("$name.json"), Charsets.UTF_8)
    fun records() = Json.parseToJsonElement(String(bytes("manifest.json"), Charsets.UTF_8)).jsonArray
    fun page(name: String): BrowsePage {
        val row = records().first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
        val query = row.getValue("request").jsonPrimitive.content.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        return decodePopularActivityPage(raw(name), query["limit"]!!.toInt(), query["offset"]?.toInt() ?: 0)
    }
}

class PopularActivityContractTest {
    @Test fun `all genuine backend exports retain original bytes total grouping and count subtitles`() {
        val manifest = PopularActivityFixture.records()
        assertEquals(12, manifest.size)
        for (value in manifest) {
            val record = value.jsonObject; val name = record.getValue("name").jsonPrimitive.content
            val bytes = PopularActivityFixture.bytes(record.getValue("file").jsonPrimitive.content)
            assertEquals(record.getValue("sha256").jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val page = PopularActivityFixture.page(name)
            assertEquals(record.getValue("total").jsonPrimitive.int, page.total)
            assertEquals(record.getValue("count").jsonPrimitive.int, page.entries.size)
            assertTrue(page.originalLanguageFilterSupported); assertNull(page.watching); assertNull(page.collection)
        }
        val counts = Json.parseToJsonElement(PopularActivityFixture.raw("counts-default")).jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("rank").jsonPrimitive.int }
        assertEquals(listOf(4, 2, 2), counts)
        assertArrayEquals(PopularActivityFixture.bytes("minimum-one.json"), PopularActivityFixture.bytes("threshold-alias-one.json"))
        assertArrayEquals(PopularActivityFixture.bytes("minimum-one.json"), PopularActivityFixture.bytes("equal-threshold-one.json"))
        assertEquals(0, PopularActivityFixture.page("empty-capability").total)
        assertEquals("tmdb:42", HomeCard.from(PopularActivityFixture.page("primary-alias-language").entries.single()).target!!.titleId)
        val boundaries = PopularActivityFixture.page("provider-kind-boundaries").entries.map { HomeCard.from(it).target!! }
        assertEquals(listOf("tmdb:tv:42", "tmdb:42", "tvdb:movie:42", "tvdb:series:42"), boundaries.map { it.titleId })
        assertEquals(listOf(false, true, true, false), boundaries.map { it.isMovie })
        assertEquals(2, PopularActivityFixture.page("unknown-only").total)
        assertEquals(3, PopularActivityFixture.page("or-unknown").total)
        assertEquals(setOf("A", "B", "S"), PopularActivityFixture.page("name-alphabet").letters.toSet())
    }
    @Test fun `view ranks are not ordinals and shared primary text retains kind and provider distinctions`() {
        val titles = listOf("tmdb:42" to "movie", "tmdb:42" to "series", "tvdb:movie:42" to "movie", "tvdb:tv:42" to "series")
        val page = decodePopularActivityPage(PopularActivityFixture.synthetic(4, 0, 4, views = 7) { titles[it] }, 4, 0)
        assertEquals(4, page.entries.size)
        val cards = page.entries.map(HomeCard::from)
        assertEquals(listOf(true, false, true, false), cards.map { it.target!!.isMovie })
        assertEquals(listOf("tmdb:42", "tmdb:42", "tvdb:movie:42", "tvdb:tv:42"), cards.map { it.target!!.titleId })
        assertTrue(cards.all { it.rank == null && it.subtitle == "7 views" })
        assertEquals("1 view", (decodePopularActivityPage(PopularActivityFixture.synthetic(1, 0, 1, views = 1), 1, 0).entries.single() as BrowseEntry.TitleEntry).title.cardSubtitle)
        assertEquals("2147483648 views", (decodePopularActivityPage(PopularActivityFixture.synthetic(1, 0, 1, views = 2_147_483_648L), 1, 0).entries.single() as BrowseEntry.TitleEntry).title.cardSubtitle)
    }
    @Test fun `malformed capability count claims primary identities and pagination fail the whole page`() {
        val good = Json.parseToJsonElement(PopularActivityFixture.synthetic(2, 0, 2)).jsonObject
        val row = good.getValue("items").jsonArray.first().jsonObject; val title = row.getValue("title").jsonObject
        fun changedTitle(values: Map<String, JsonElement>) = JsonObject(good + ("items" to buildJsonArray { add(JsonObject(row + ("title" to JsonObject(title + values)))); add(row) }))
        val bad = listOf(
            JsonObject(good - "originalLanguageFilterSupported"), JsonObject(good + ("originalLanguageFilterSupported" to JsonPrimitive("true"))),
            JsonObject(good + ("source" to JsonPrimitive("popular-on-server"))), JsonObject(good + ("total" to JsonPrimitive(1))),
            JsonObject(good + ("pool" to buildJsonObject { put("complete", true) })), JsonObject(good + ("sourceTotal" to JsonPrimitive(999))),
            JsonObject(good + ("items" to buildJsonArray { add(JsonObject(row + ("rank" to JsonPrimitive(0)))); add(row) })),
            changedTitle(mapOf("cardSubtitle" to JsonPrimitive("2 profiles"))), changedTitle(mapOf("tmdbId" to JsonPrimitive(99))),
            changedTitle(mapOf("id" to JsonPrimitive("/private/file"))), changedTitle(mapOf("mediaType" to JsonPrimitive("series"))),
            changedTitle(mapOf("sourceAvailability" to buildJsonObject { put("local", true) })),
        )
        for (raw in bad) assertTrue(runCatching { decodePopularActivityPage(raw.toString(), 2, 0) }.exceptionOrNull() is ManagementPageFailure)
        assertEquals(3, decodePopularActivityPage(PopularActivityFixture.synthetic(3, 7, 0), 1, 7).total)
    }
}
