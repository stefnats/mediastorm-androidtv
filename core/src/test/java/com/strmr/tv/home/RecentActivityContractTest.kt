package com.strmr.tv.home

import com.strmr.tv.library.ProfileDataContext
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic scalar edges are separate from byte-exact exported Go contract resources. */
internal object RecentActivityFixture {
    val origin = BrowseList.Remote("recently-watched", "Recently Watched", mapOf("source" to "recently-watched"))
    fun list(source: BrowseList = origin, query: Map<String, String> = emptyMap(), producer: Job = Job()) = requireNotNull(BrowseList.RecentlyWatchedCached.open(source, query, ProfileDataContext.current, producer, { true }, { "synthetic-client" }))
    fun synthetic(total: Int, offset: Int, count: Int, id: String = "tmdb:movie:42"): String = buildJsonObject {
        put("source", "recently-watched-cached"); put("originalLanguageFilterSupported", true); put("total", total)
        putJsonArray("alphabetBuckets") { add("S") }
        putJsonArray("items") { repeat(count) { i -> add(buildJsonObject { put("rank", offset + i + 1); putJsonObject("title") {
            put("id", id); put("name", "Synthetic stored title"); put("mediaType", "movie"); put("tmdbId", id.substringAfterLast(':').toLong())
            put("overview", ""); put("year", 0); put("language", ""); put("forceTitleOverlay", true); put("cardSubtitle", "Fellow user watched")
        } }) } }
    }.toString()
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/recently-watched-cached-go/$name")).use { it.readBytes() }
    fun raw(name: String) = String(bytes("$name.json"), Charsets.UTF_8)
    fun page(name: String): BrowsePage {
        val records = Json.parseToJsonElement(String(bytes("manifest.json"), Charsets.UTF_8)).jsonArray
        val row = records.first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
        val query = row.getValue("request").jsonPrimitive.content.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        return decodeRecentActivityPage(raw(name), query["limit"]!!.toInt(), query["offset"]?.toInt() ?: 0)
    }
}

class RecentActivityContractTest {
    @Test fun `all actual backend exported bytes retain producer hashes and strict page counts`() {
        val manifest = Json.parseToJsonElement(String(RecentActivityFixture.bytes("manifest.json"), Charsets.UTF_8)).jsonArray
        assertEquals(9, manifest.size)
        for (value in manifest) {
            val record = value.jsonObject; val name = record.getValue("name").jsonPrimitive.content
            val bytes = RecentActivityFixture.bytes(record.getValue("file").jsonPrimitive.content)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(record.getValue("sha256").jsonPrimitive.content, sha)
            val page = RecentActivityFixture.page(name)
            assertEquals(record.getValue("Total").jsonPrimitive.int, page.total)
            assertEquals(record.getValue("Count").jsonPrimitive.int, page.entries.size)
            assertTrue(page.originalLanguageFilterSupported); assertNull(page.watching); assertNull(page.collection)
        }
    }
    @Test fun `genuine repeated title rows aliases blank names and unknown OR are retained`() {
        val repeated = RecentActivityFixture.page("repeated-all")
        assertEquals(7, repeated.entries.size)
        assertEquals(3, repeated.entries.count { HomeCard.from(it).target?.titleId == "tmdb:movie:42" })
        assertEquals(4, repeated.entries.count { HomeCard.from(it).target?.titleId == "tmdb:tv:800" })
        assertTrue(RecentActivityFixture.page("primary-alias-blank-name").entries.any { HomeCard.from(it).title.isEmpty() })
        assertEquals(0, RecentActivityFixture.page("empty-capability").total)
        assertTrue(RecentActivityFixture.page("or-unknown").total >= RecentActivityFixture.page("unknown-only").total)
    }
    @Test fun `synthetic malformed response fails whole page without trimming offsets or adopting claims`() {
        val good = Json.parseToJsonElement(RecentActivityFixture.synthetic(2, 0, 2)).jsonObject
        val row = good.getValue("items").jsonArray.first().jsonObject
        val title = row.getValue("title").jsonObject
        val bad = listOf(
            JsonObject(good - "originalLanguageFilterSupported"), JsonObject(good + ("originalLanguageFilterSupported" to JsonPrimitive("true"))),
            JsonObject(good + ("source" to JsonPrimitive("recently-watched"))), JsonObject(good + ("total" to JsonPrimitive(1))),
            JsonObject(good + ("sourceTotal" to JsonPrimitive(999))), JsonObject(good + ("pool" to buildJsonObject { put("complete", true) })),
            JsonObject(good + ("items" to buildJsonArray { add(JsonObject(row + ("rank" to JsonPrimitive(0)))); add(row) })),
            JsonObject(good + ("items" to buildJsonArray { add(JsonObject(row + ("title" to JsonObject(title + ("sourceAvailability" to buildJsonObject { put("local", true) }))))); add(row) })),
            JsonObject(good + ("items" to buildJsonArray { add(JsonObject(row + ("title" to JsonObject(title + ("tmdbId" to JsonPrimitive(99)))))); add(row) })),
            JsonObject(good + ("items" to buildJsonArray { add(JsonObject(row + ("title" to JsonObject(title + ("id" to JsonPrimitive("/private/file")))))); add(row) })),
        )
        for (raw in bad) assertTrue(runCatching { decodeRecentActivityPage(raw.toString(), 2, 0) }.exceptionOrNull() is ManagementPageFailure)
        assertEquals(0, decodeRecentActivityPage(RecentActivityFixture.synthetic(3, 7, 0), 1, 7).entries.size)
        assertEquals(3, decodeRecentActivityPage(RecentActivityFixture.synthetic(3, 7, 0), 1, 7).total)
    }
}
