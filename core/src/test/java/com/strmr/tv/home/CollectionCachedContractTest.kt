package com.strmr.tv.home

import com.strmr.tv.api.*
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Portable byte-exact Go handler replies; the fixture clock comes from the recorded observation. */
internal object CollectionFixture {
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/collection-cached-go/$name.json")).use { it.readBytes() }
    fun wire(name: String) = String(bytes(name))
    val now get() = OffsetDateTime.parse(ApiJson.parseToJsonElement(wire("unfiltered")).jsonObject.getValue("collection").jsonObject.getValue("observedAt").jsonPrimitive.content).toInstant().toEpochMilli() + 1
    fun page(name: String, limit: Int? = null, offset: Int = 0) = decodeCachedCollectionPage(wire(name), 77, limit, offset, now)
}
class CollectionCachedContractTest {
    private fun rejected(raw: String) = assertTrue(runCatching { decodeCachedCollectionPage(raw, 77, now = CollectionFixture.now) }.exceptionOrNull() is ManagementPageFailure)
    @Test fun `all nine actual Go reply bytes match producer hashes with portable recorded clock`() {
        val manifest = ApiJson.parseToJsonElement(CollectionFixture.wire("manifest")).jsonObject
        assertEquals(9, manifest.size)
        for ((name, expected) in manifest) {
            val bytes = CollectionFixture.bytes(name.removeSuffix(".json"))
            assertEquals(expected.jsonPrimitive.content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            if (name == "missing.json") rejected(String(bytes)) else decodeCachedCollectionPage(String(bytes), 77, now = CollectionFixture.now)
        }
    }
    @Test fun `actual full cached pool beyond 500 retains totals and returned page cardinality`() {
        val first = CollectionFixture.page("unfiltered", 2); val next = CollectionFixture.page("continuation", 2, 2)
        assertEquals(701, first.total); assertEquals(701, next.total); assertEquals(2, first.entries.size); assertEquals(2, next.entries.size)
        assertEquals(first.collection, next.collection); assertTrue(first.originalLanguageFilterSupported)
        assertTrue(first.entries.map { (it as BrowseEntry.TitleEntry).title.id }.intersect(next.entries.map { (it as BrowseEntry.TitleEntry).title.id }.toSet()).isEmpty())
    }
    @Test fun `actual server language unknown OR rare and full random responses remain distinct`() {
        assertEquals(1, CollectionFixture.page("late-french").total)
        assertEquals(3, CollectionFixture.page("late-norwegian").total)
        assertEquals(697, CollectionFixture.page("unknown-only").total)
        assertEquals(698, CollectionFixture.page("or-unknown").total)
        assertEquals(0, CollectionFixture.page("empty-filter").total)
        assertEquals(2, CollectionFixture.page("random-pool", 0).entries.size)
    }
    @Test fun `source marker collection identity and all plain title fields are strict`() {
        val good = ApiJson.parseToJsonElement(CollectionFixture.wire("unfiltered")).jsonObject
        for (key in listOf("source", "items", "total", "collection", "originalLanguageFilterSupported")) rejected(JsonObject(good - key).toString())
        for ((key, value) in listOf("source" to JsonPrimitive("collection"), "originalLanguageFilterSupported" to JsonPrimitive("true"), "total" to JsonPrimitive("701"), "items" to JsonNull)) rejected(JsonObject(good + (key to value)).toString())
        val row = good.getValue("items").jsonArray.first().jsonObject
        for (key in listOf("id", "name", "mediaType")) rejected(JsonObject(good + ("items" to JsonArray(listOf(row, JsonObject(row - key))))).toString())
        val meta = good.getValue("collection").jsonObject
        for (key in listOf("id", "name", "metadataLanguage", "observedAt", "expiresAt")) rejected(JsonObject(good + ("collection" to JsonObject(meta - key))).toString())
        rejected(JsonObject(good + ("collection" to JsonObject(meta + ("id" to JsonPrimitive(78))))).toString())
    }
    @Test fun `canonical movie identity cannot conflict with kind or supplied TMDB identity`() {
        val root = ApiJson.parseToJsonElement(CollectionFixture.wire("late-french")).jsonObject
        val row = root.getValue("items").jsonArray.single().jsonObject
        for (id in listOf("tmdb:tv:651", "anything", "tmdb:movie:0", "tmdb:movie:0651")) {
            rejected(JsonObject(root + ("items" to JsonArray(listOf(JsonObject(row + ("id" to JsonPrimitive(id))))))).toString())
        }
        for (value in listOf(JsonPrimitive(652), JsonPrimitive("651"), JsonPrimitive(true))) {
            rejected(JsonObject(root + ("items" to JsonArray(listOf(JsonObject(row + ("tmdbId" to value)))))).toString())
        }
    }
    @Test fun `actual observed expiry is bounded without real time sleeps or renewed fixture bytes`() {
        val raw = CollectionFixture.wire("unfiltered"); val page = CollectionFixture.page("unfiltered")
        val info = page.collection!!
        assertTrue(runCatching { decodeCachedCollectionPage(raw, 77, now = info.expiresMillis) }.isFailure)
        assertTrue(runCatching { decodeCachedCollectionPage(raw, 77, now = info.observedMillis - 1) }.isFailure)
        val root = ApiJson.parseToJsonElement(raw).jsonObject; val meta = root.getValue("collection").jsonObject
        for (value in listOf("not-a-date", "2026-02-30T00:00:00Z", OffsetDateTime.parse(info.observedAt).plusDays(2).toString())) rejected(JsonObject(root + ("collection" to JsonObject(meta + ("expiresAt" to JsonPrimitive(value))))).toString())
    }
    @Test fun `bare cache cards do not promote raw enrichment or editable rating claims`() {
        val root = ApiJson.parseToJsonElement(CollectionFixture.wire("late-french")).jsonObject
        val row = root.getValue("items").jsonArray.single().jsonObject
        val extra = JsonObject(row + mapOf("poster" to JsonPrimitive("private"), "watchState" to JsonPrimitive("complete"), "personalRatingEditable" to JsonPrimitive(true)))
        val title = (decodeCachedCollectionPage(JsonObject(root + ("items" to JsonArray(listOf(extra)))).toString(), 77, now = CollectionFixture.now).entries.single() as BrowseEntry.TitleEntry).title
        assertNull(title.poster); assertNull(title.watchState); assertNull(title.personalRating)
    }
}
