package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Frozen Go bytes use explicitly simulated HTTP Date; real transport is tested separately. */
internal object PublicListFixture {
    val url = "http://127.0.0.1:8123/api/users/profile/display-list?source=tmdb-public-list-cached".toHttpUrl()
    val date = System.currentTimeMillis() / 1000L * 1000L
    fun origin(id: String = "77", kind: BrowseKind? = null) = ShelfConfig("synthetic-public-list", "Synthetic list", type = "tmdb", tmdbSourceType = "public-list", tmdbSourceId = id, tmdbMediaType = kind?.mediaType ?: "all", sort = "title.asc").browseList as BrowseList.Remote
    fun list(id: Long = 77L, kind: BrowseKind? = null, producer: Job = Job()) = requireNotNull(BrowseList.TMDBPublicListCached.open(origin(id.toString(), kind), emptyMap(), ProfileDataContext.current, producer, { true }, { "synthetic-client" }))
    fun dateText(at: Long) = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US).format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))
    fun clock(at: Long = date, received: Long = System.nanoTime()) = NetworkResponseObservation(url, listOf(dateText(at)), received - 1_000_000L, received).clock(url)
    fun synthetic(total: Int, offset: Int, count: Int, listID: Long = 77L, kind: String? = null, observed: Long = date - 300_000L, expires: Long = date + 21_600_000L,
                  titleAt: (Int) -> String = { "tmdb:${if ((kind ?: if (it % 2 == 0) "movie" else "series") == "movie") "movie" else "tv"}:${it + 1}" }): String = buildJsonObject {
        put("source", "tmdb-public-list-cached"); put("originalLanguageFilterSupported", true); put("total", total)
        putJsonObject("publicList") { put("listId", listID); put("metadataLanguage", "fr-US"); put("observedAt", Instant.ofEpochMilli(observed).toString()); put("expiresAt", Instant.ofEpochMilli(expires).toString()); put("scope", "returned-items") }
        putJsonArray("alphabetBuckets") { add("S") }
        putJsonArray("items") { repeat(count) { i -> val id = titleAt(offset + i); val media = kind ?: if (id.startsWith("tmdb:movie:")) "movie" else "series"; add(buildJsonObject {
            put("id", id); put("name", "Stored ${offset + i + 1}"); put("mediaType", media); put("tmdbId", id.substringAfterLast(':').toLong())
            put("overview", ""); put("year", 0); put("language", "")
        }) } }
    }.toString()
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/tmdb-public-list-cached-go/$name")).use { it.readBytes() }
    fun raw(name: String) = String(bytes("$name.json"), Charsets.UTF_8)
    fun records() = ApiJson.parseToJsonElement(String(bytes("manifest.json"), Charsets.UTF_8)).jsonObject.getValue("replies").jsonArray
    fun page(name: String): BrowsePage {
        val record = records().first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
        val query = record.getValue("request").jsonPrimitive.content.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val root = ApiJson.parseToJsonElement(raw(name)).jsonObject
        val descriptor = root.getValue("publicList").jsonObject
        val at = OffsetDateTime.parse(descriptor.getValue("observedAt").jsonPrimitive.content).toInstant().toEpochMilli() + 300_000L
        return decodeCachedTMDBPublicListPage(raw(name), list(descriptor.getValue("listId").jsonPrimitive.long), clock(at), query["limit"]!!.toInt(), query["offset"]?.toInt() ?: 0)
    }
}

class CachedTMDBPublicListContractTest {
    @Test fun `all ten genuine backend replies retain producer hashes and exact returned-set descriptors`() {
        val records = PublicListFixture.records(); assertEquals(10, records.size)
        for (value in records) {
            val row = value.jsonObject; val bytes = PublicListFixture.bytes(row.getValue("file").jsonPrimitive.content)
            assertEquals(row.getValue("sha256").jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val page = PublicListFixture.page(row.getValue("name").jsonPrimitive.content)
            assertEquals(row.getValue("total").jsonPrimitive.int, page.total); assertEquals(row.getValue("count").jsonPrimitive.int, page.entries.size)
            assertTrue(page.originalLanguageFilterSupported); assertNull(page.watching); assertNull(page.collection)
            assertEquals("returned-items", page.publicList!!.scope)
        }
        val mixed = PublicListFixture.page("mixed-unfiltered").entries.map { HomeCard.from(it).target!! }
        assertEquals(listOf("tmdb:movie:42", "tmdb:tv:42", "tmdb:movie:43", "tmdb:tv:44", "tmdb:movie:45"), mixed.map { it.titleId })
        assertEquals(listOf(true, false, true, false, true), mixed.map { it.isMovie })
        assertEquals(3, PublicListFixture.page("movies-only").total); assertEquals(2, PublicListFixture.page("series-only").total)
        val blank = PublicListFixture.page("blank-labels").entries.map { (it as BrowseEntry.TitleEntry).title.name }
        assertEquals(listOf("", "   "), blank)
        assertEquals(0, PublicListFixture.page("empty-capability").total)
    }
    @Test fun `plain primary title and source descriptor remain strict without importing side claims`() {
        val good = ApiJson.parseToJsonElement(PublicListFixture.synthetic(1, 0, 1)).jsonObject
        val title = good.getValue("items").jsonArray.single().jsonObject
        val descriptor = good.getValue("publicList").jsonObject
        fun changedTitle(pair: Pair<String, JsonElement>) = JsonObject(good + ("items" to JsonArray(listOf(JsonObject(title + pair)))))
        val bad = mutableListOf<JsonObject>(JsonObject(good - "publicList"), JsonObject(good - "originalLanguageFilterSupported"), JsonObject(good + ("source" to JsonPrimitive("tmdb-list"))), JsonObject(good + ("total" to JsonPrimitive(0))), JsonObject(good + ("originalLanguageFilterSupported" to JsonPrimitive("true"))))
        for (pair in listOf("tmdbId" to JsonPrimitive(99), "id" to JsonPrimitive("tmdb:1"), "id" to JsonPrimitive("/private/path"), "mediaType" to JsonPrimitive("series"), "name" to JsonPrimitive("unsafe\nname"), "year" to JsonPrimitive(2000), "language" to JsonPrimitive("fra"), "posterUrl" to JsonPrimitive("private"), "forceTitleOverlay" to JsonPrimitive(true), "sourceAvailability" to buildJsonObject { put("state", "found") })) bad += changedTitle(pair)
        for (pair in listOf("listId" to JsonPrimitive(78), "mediaType" to JsonPrimitive("series"), "metadataLanguage" to JsonPrimitive(" "), "scope" to JsonPrimitive("all-tmdb"), "complete" to JsonPrimitive(true), "observedAt" to JsonPrimitive("2026-02-30T00:00:00Z"), "expiresAt" to JsonPrimitive(Instant.ofEpochMilli(PublicListFixture.date + 86_400_001L).toString()))) bad += JsonObject(good + ("publicList" to JsonObject(descriptor + pair)))
        for ((index, raw) in bad.withIndex()) assertTrue("Malformed public-list case index=$index must fail the complete page", runCatching { decodeCachedTMDBPublicListPage(raw.toString(), PublicListFixture.list(), PublicListFixture.clock(), 1, 0) }.exceptionOrNull() is ManagementPageFailure)
        val validFirstPage = decodeCachedTMDBPublicListPage(JsonObject(good + ("total" to JsonPrimitive(2))).toString(), PublicListFixture.list(), PublicListFixture.clock(), 1, 0)
        assertEquals(2, validFirstPage.total); assertEquals(1, validFirstPage.entries.size)
        assertTrue(runCatching { decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(5000, 0, 1), PublicListFixture.list(), PublicListFixture.clock(), 1, 0) }.isFailure)
        assertTrue(runCatching { decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(2, 0, 2, kind = "movie") { "tmdb:movie:42" }, PublicListFixture.list(), PublicListFixture.clock(), 2, 0) }.isFailure)
        assertTrue(runCatching { decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(2, 0, 2, kind = "series") { if (it == 0) "tmdb:tv:42" else "tmdb:series:42" }, PublicListFixture.list(kind = BrowseKind.SHOWS), PublicListFixture.clock(), 2, 0) }.isFailure)
        val long = decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(1, 0, 1, listID = Long.MAX_VALUE) { "tmdb:movie:${Long.MAX_VALUE}" }, PublicListFixture.list(Long.MAX_VALUE), PublicListFixture.clock(), 1, 0)
        assertEquals(Long.MAX_VALUE, (long.entries.single() as BrowseEntry.TitleEntry).title.tmdbId)
        assertEquals(3, decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(3, 7, 0), PublicListFixture.list(), PublicListFixture.clock(), 1, 7).total)
    }
    @Test fun `Public list uses existing 24-hour bound while existing CW default and overflow limits remain bounded`() {
        val clock = PublicListFixture.clock(); val expires = PublicListFixture.date + 21_600_000L
        assertTrue(runCatching { clock.deadlineNanos(expires) }.isFailure)
        assertTrue(clock.deadlineNanos(expires, 86_400_000L) > System.nanoTime())
        for (max in listOf(0L, -1L, 86_400_001L, Long.MAX_VALUE)) assertTrue(runCatching { clock.deadlineNanos(expires, max) }.isFailure)
        assertTrue(runCatching { ObservedResponseClock(Long.MIN_VALUE, 1, 0).deadlineNanos(Long.MAX_VALUE, 86_400_000L) }.isFailure)
        assertTrue(runCatching { ObservedResponseClock(0, Long.MAX_VALUE - 1, 0).deadlineNanos(1, 86_400_000L) }.isFailure)
        assertTrue(runCatching { decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(1, 0, 1), PublicListFixture.list(), PublicListFixture.clock(PublicListFixture.date + 21_601_000L), 1, 0) }.isFailure)
        assertTrue(runCatching { decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(1, 0, 1), PublicListFixture.list(), PublicListFixture.clock(PublicListFixture.date - 400_000L), 1, 0) }.isFailure)
    }
    @Test fun `same actual receipt deadline cannot move during adoption pause or repeated filter reads`() {
        val received = System.nanoTime(); val clock = PublicListFixture.clock(received = received)
        val expires = PublicListFixture.date + 1602L // Actual Date precision +2ms RTT upper bound +600ms remaining.
        val page = decodeCachedTMDBPublicListPage(PublicListFixture.synthetic(1, 0, 1, expires = expires), PublicListFixture.list(), clock, 1, 0)
        val observation = page.publicList!!; val expected = received + 600_000_000L
        val list = PublicListFixture.list(); assertEquals(expected, list.accept(observation))
        Thread.sleep(10); assertEquals(expected, list.accept(observation)); assertEquals(expected, clock.deadlineNanos(expires, 86_400_000L))
        assertTrue(runCatching { list.accept(observation.copy(clock = PublicListFixture.clock(PublicListFixture.date - 1000L))) }.isFailure)
    }
}
