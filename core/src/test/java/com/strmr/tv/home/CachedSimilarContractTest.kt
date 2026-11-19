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
internal object SimilarFixture {
    val url = "http://127.0.0.1:8123/api/users/profile/display-list?source=similar-cached".toHttpUrl()
    val date = System.currentTimeMillis() / 1000L * 1000L
    fun origin(id: String = "77", kind: BrowseKind = BrowseKind.MOVIES) = BrowseList.Similar(id, kind, "Synthetic seed", null)
    fun list(id: Long = 77L, kind: BrowseKind = BrowseKind.MOVIES, producer: Job = Job()) = requireNotNull(BrowseList.SimilarCached.open(origin(id.toString(), kind), ProfileDataContext.current, producer, { true }, { "synthetic-client" }))
    fun dateText(at: Long) = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US).format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))
    fun clock(at: Long = date, received: Long = System.nanoTime()) = NetworkResponseObservation(url, listOf(dateText(at)), received - 1_000_000L, received).clock(url)
    fun synthetic(total: Int, offset: Int, count: Int, seed: Long = 77L, kind: String = "movie", observed: Long = date - 300_000L, expires: Long = date + 21_600_000L,
                  titleAt: (Int) -> String = { "tmdb:${if (kind == "movie") "movie" else "tv"}:${it + 1}" }): String = buildJsonObject {
        put("source", "similar-cached"); put("originalLanguageFilterSupported", true); put("total", total)
        putJsonObject("similar") { put("tmdbId", seed); put("mediaType", kind); put("metadataLanguage", "fr-US"); put("observedAt", Instant.ofEpochMilli(observed).toString()); put("expiresAt", Instant.ofEpochMilli(expires).toString()); put("scope", "returned-results") }
        putJsonArray("alphabetBuckets") { add("S") }
        putJsonArray("items") { repeat(count) { i -> val id = titleAt(offset + i); add(buildJsonObject {
            put("id", id); put("name", "Stored ${offset + i + 1}"); put("mediaType", kind); put("tmdbId", id.substringAfterLast(':').toLong())
            put("overview", ""); put("year", 0); put("language", "")
        }) } }
    }.toString()
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/similar-cached-go/$name")).use { it.readBytes() }
    fun raw(name: String) = String(bytes("$name.json"), Charsets.UTF_8)
    fun records() = ApiJson.parseToJsonElement(String(bytes("manifest.json"), Charsets.UTF_8)).jsonObject.getValue("replies").jsonArray
    fun page(name: String): BrowsePage {
        val record = records().first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
        val query = record.getValue("request").jsonPrimitive.content.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val root = ApiJson.parseToJsonElement(raw(name)).jsonObject
        val descriptor = root.getValue("similar").jsonObject
        val at = OffsetDateTime.parse(descriptor.getValue("observedAt").jsonPrimitive.content).toInstant().toEpochMilli() + 300_000L
        val kind = if (descriptor.getValue("mediaType").jsonPrimitive.content == "movie") BrowseKind.MOVIES else BrowseKind.SHOWS
        return decodeCachedSimilarPage(raw(name), list(descriptor.getValue("tmdbId").jsonPrimitive.long, kind), clock(at), query["limit"]!!.toInt(), query["offset"]?.toInt() ?: 0)
    }
}

class CachedSimilarContractTest {
    @Test fun `all ten genuine backend replies retain producer hashes and exact returned-set descriptors`() {
        val records = SimilarFixture.records(); assertEquals(10, records.size)
        for (value in records) {
            val row = value.jsonObject; val bytes = SimilarFixture.bytes(row.getValue("file").jsonPrimitive.content)
            assertEquals(row.getValue("sha256").jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val page = SimilarFixture.page(row.getValue("name").jsonPrimitive.content)
            assertEquals(row.getValue("total").jsonPrimitive.int, page.total); assertEquals(row.getValue("count").jsonPrimitive.int, page.entries.size)
            assertTrue(page.originalLanguageFilterSupported); assertNull(page.watching); assertNull(page.collection)
            assertEquals("returned-results", page.similar!!.scope)
        }
        assertEquals(listOf("tmdb:tv:21", "tmdb:series:22", "tmdb:tv:23"), SimilarFixture.page("series-unfiltered").entries.map { HomeCard.from(it).target!!.titleId })
        assertTrue(SimilarFixture.page("series-unfiltered").entries.all { HomeCard.from(it).target!!.isMovie == false })
        assertEquals("en-US", SimilarFixture.page("movie-global-locale").similar!!.metadataLanguage)
        val blank = SimilarFixture.page("blank-labels").entries.map { (it as BrowseEntry.TitleEntry).title.name }
        assertEquals(listOf("", "   "), blank)
        assertEquals(0, SimilarFixture.page("empty-capability").total)
    }
    @Test fun `plain primary title and source descriptor remain strict without importing side claims`() {
        val good = ApiJson.parseToJsonElement(SimilarFixture.synthetic(1, 0, 1)).jsonObject
        val title = good.getValue("items").jsonArray.single().jsonObject
        val descriptor = good.getValue("similar").jsonObject
        fun changedTitle(pair: Pair<String, JsonElement>) = JsonObject(good + ("items" to JsonArray(listOf(JsonObject(title + pair)))))
        val bad = mutableListOf<JsonObject>(JsonObject(good - "similar"), JsonObject(good - "originalLanguageFilterSupported"), JsonObject(good + ("source" to JsonPrimitive("similar"))), JsonObject(good + ("total" to JsonPrimitive(0))), JsonObject(good + ("originalLanguageFilterSupported" to JsonPrimitive("true"))))
        for (pair in listOf("tmdbId" to JsonPrimitive(99), "id" to JsonPrimitive("tmdb:1"), "id" to JsonPrimitive("/private/path"), "mediaType" to JsonPrimitive("series"), "name" to JsonPrimitive("unsafe\nname"), "year" to JsonPrimitive(2000), "language" to JsonPrimitive("fra"), "posterUrl" to JsonPrimitive("private"), "forceTitleOverlay" to JsonPrimitive(true), "sourceAvailability" to buildJsonObject { put("state", "found") })) bad += changedTitle(pair)
        for (pair in listOf("tmdbId" to JsonPrimitive(78), "mediaType" to JsonPrimitive("series"), "metadataLanguage" to JsonPrimitive(" "), "scope" to JsonPrimitive("all-tmdb"), "complete" to JsonPrimitive(true), "observedAt" to JsonPrimitive("2026-02-30T00:00:00Z"), "expiresAt" to JsonPrimitive(Instant.ofEpochMilli(SimilarFixture.date + 86_400_001L).toString()))) bad += JsonObject(good + ("similar" to JsonObject(descriptor + pair)))
        for ((index, raw) in bad.withIndex()) assertTrue("Malformed Similar case index=$index must fail the complete page", runCatching { decodeCachedSimilarPage(raw.toString(), SimilarFixture.list(), SimilarFixture.clock(), 1, 0) }.exceptionOrNull() is ManagementPageFailure)
        val validFirstPage = decodeCachedSimilarPage(JsonObject(good + ("total" to JsonPrimitive(2))).toString(), SimilarFixture.list(), SimilarFixture.clock(), 1, 0)
        assertEquals(2, validFirstPage.total); assertEquals(1, validFirstPage.entries.size)
        assertTrue(runCatching { decodeCachedSimilarPage(SimilarFixture.synthetic(5000, 0, 1), SimilarFixture.list(), SimilarFixture.clock(), 1, 0) }.isFailure)
        assertTrue(runCatching { decodeCachedSimilarPage(SimilarFixture.synthetic(2, 0, 2) { "tmdb:movie:42" }, SimilarFixture.list(), SimilarFixture.clock(), 2, 0) }.isFailure)
        assertTrue(runCatching { decodeCachedSimilarPage(SimilarFixture.synthetic(2, 0, 2, kind = "series") { if (it == 0) "tmdb:tv:42" else "tmdb:series:42" }, SimilarFixture.list(kind = BrowseKind.SHOWS), SimilarFixture.clock(), 2, 0) }.isFailure)
        val long = decodeCachedSimilarPage(SimilarFixture.synthetic(1, 0, 1, seed = Long.MAX_VALUE) { "tmdb:movie:${Long.MAX_VALUE}" }, SimilarFixture.list(Long.MAX_VALUE), SimilarFixture.clock(), 1, 0)
        assertEquals(Long.MAX_VALUE, (long.entries.single() as BrowseEntry.TitleEntry).title.tmdbId)
        assertEquals(3, decodeCachedSimilarPage(SimilarFixture.synthetic(3, 7, 0), SimilarFixture.list(), SimilarFixture.clock(), 1, 7).total)
    }
    @Test fun `Similar explicitly uses 24 hours while existing CW default and overflow limits remain bounded`() {
        val clock = SimilarFixture.clock(); val expires = SimilarFixture.date + 21_600_000L
        assertTrue(runCatching { clock.deadlineNanos(expires) }.isFailure)
        assertTrue(clock.deadlineNanos(expires, 86_400_000L) > System.nanoTime())
        for (max in listOf(0L, -1L, 86_400_001L, Long.MAX_VALUE)) assertTrue(runCatching { clock.deadlineNanos(expires, max) }.isFailure)
        assertTrue(runCatching { ObservedResponseClock(Long.MIN_VALUE, 1, 0).deadlineNanos(Long.MAX_VALUE, 86_400_000L) }.isFailure)
        assertTrue(runCatching { ObservedResponseClock(0, Long.MAX_VALUE - 1, 0).deadlineNanos(1, 86_400_000L) }.isFailure)
        assertTrue(runCatching { decodeCachedSimilarPage(SimilarFixture.synthetic(1, 0, 1), SimilarFixture.list(), SimilarFixture.clock(SimilarFixture.date + 21_601_000L), 1, 0) }.isFailure)
        assertTrue(runCatching { decodeCachedSimilarPage(SimilarFixture.synthetic(1, 0, 1), SimilarFixture.list(), SimilarFixture.clock(SimilarFixture.date - 400_000L), 1, 0) }.isFailure)
    }
    @Test fun `same actual receipt deadline cannot move during adoption pause or repeated filter reads`() {
        val received = System.nanoTime(); val clock = SimilarFixture.clock(received = received)
        val expires = SimilarFixture.date + 1602L // Actual Date precision +2ms RTT upper bound +600ms remaining.
        val page = decodeCachedSimilarPage(SimilarFixture.synthetic(1, 0, 1, expires = expires), SimilarFixture.list(), clock, 1, 0)
        val observation = page.similar!!; val expected = received + 600_000_000L
        val list = SimilarFixture.list(); assertEquals(expected, list.accept(observation))
        Thread.sleep(10); assertEquals(expected, list.accept(observation)); assertEquals(expected, clock.deadlineNanos(expires, 86_400_000L))
        assertTrue(runCatching { list.accept(observation.copy(clock = SimilarFixture.clock(SimilarFixture.date - 1000L))) }.isFailure)
    }
}
