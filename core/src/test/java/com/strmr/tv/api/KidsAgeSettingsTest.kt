package com.strmr.tv.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class KidsAgeSettingsTest {
    @Test fun `profile response adds nullable limits without breaking old replies`() {
        val old = ApiJson.decodeFromString<Profile>("""{"id":"child","name":"Child","isKidsProfile":true,"kidsMode":"rating"}""")
        assertNull(old.kidsMaxRating)
        assertNull(old.kidsMaxMovieRating)
        assertNull(old.kidsMaxTVRating)
        val current = ApiJson.decodeFromString<Profile>("""{"id":"child","name":"Child","isKidsProfile":true,"kidsMode":"rating_de","kidsMaxMovieRating":"6","kidsMaxTVRating":"12"}""")
        val summary = kidsAgeSummary(current)
        assertEquals("TMDB Germany title metadata", summary.headline)
        assertTrue(summary.detail.contains("Maximum movie rating: 6. Maximum series rating: 12."))
        assertTrue(summary.detail.contains("not proof for an individual episode"))
        assertTrue(summary.detail.contains("Missing, conflicting or stale German metadata is blocked"))
        assertTrue(summary.detail.contains("not an independently verified FSK"))
    }

    @Test fun `German caps accept only the five canonical ages`() {
        for (age in listOf("0", "6", "12", "16", "18")) {
            val summary = kidsAgeSummary(Profile("child", "Child", isKidsProfile = true, kidsMode = "rating_de", kidsMaxMovieRating = age, kidsMaxTVRating = age))
            assertTrue(summary.detail.startsWith("Maximum movie rating: $age. Maximum series rating: $age."))
        }
    }

    @Test fun `German limits never inherit old generic or US ratings`() {
        for (invalid in listOf(null, "", "PG", "TV-Y7", "06", " 6 ", "7")) {
            val summary = kidsAgeSummary(Profile("child", "Child", isKidsProfile = true, kidsMode = "rating_de", kidsMaxRating = "6", kidsMaxMovieRating = invalid, kidsMaxTVRating = "12"))
            assertTrue(summary.detail.startsWith("Maximum movie rating: unavailable. Maximum series rating: 12."))
            val other = kidsAgeSummary(Profile("child", "Child", isKidsProfile = true, kidsMode = "rating_de", kidsMaxRating = "6", kidsMaxMovieRating = "0", kidsMaxTVRating = invalid))
            assertTrue(other.detail.startsWith("Maximum movie rating: 0. Maximum series rating: unavailable."))
        }
    }

    @Test fun `preconfigured age mode is inactive on a non child profile`() {
        val summary = kidsAgeSummary(Profile("adult", "Adult", isKidsProfile = false, kidsMode = "rating_de", kidsMaxMovieRating = "6", kidsMaxTVRating = "6"))
        assertEquals("Inactive for this profile", summary.headline)
        assertFalse(summary.detail.contains("TMDB"))
        assertEquals("Rule unavailable", kidsAgeSummary(Profile("legacy", "Legacy", kidsMode = "rating_de")).headline)
    }

    @Test fun `legacy lists defaults and unknown modes remain distinct`() {
        val legacy = kidsAgeSummary(Profile("child", "Child", isKidsProfile = true, kidsMode = "rating", kidsMaxRating = "G", kidsMaxMovieRating = "PG", kidsMaxTVRating = "TV-Y7"))
        assertEquals("Legacy rating rules", legacy.headline)
        assertTrue(legacy.detail.contains("Movies: PG"))
        assertTrue(legacy.detail.contains("Series: TV-Y7"))
        assertTrue(legacy.detail.contains("Legacy shared limit: G"))
        assertTrue(legacy.detail.contains("not converted into German ages"))
        assertEquals("Selected title lists", kidsAgeSummary(Profile("child", "Child", isKidsProfile = true, kidsMode = "content_list")).headline)
        assertEquals("Server child rules", kidsAgeSummary(Profile("child", "Child", isKidsProfile = true)).headline)
        assertEquals("Rule unavailable", kidsAgeSummary(Profile("child", "Child", isKidsProfile = true, kidsMode = "future-rule")).headline)
    }

    @Test fun `refreshed rule stays with selected profile instead of another child`() {
        val first = Profile("first", "First", isKidsProfile = true, kidsMode = "rating_de", kidsMaxMovieRating = "6")
        val second = Profile("second", "Second", isKidsProfile = true, kidsMode = "rating_de", kidsMaxMovieRating = "18")
        assertEquals(first, selectedKidsAgeProfile(listOf(second, first), "first"))
        assertEquals(second, selectedKidsAgeProfile(listOf(first, second), "second"))
        for (rows in listOf(emptyList(), listOf(second), listOf(first, first))) {
            assertTrue(runCatching { selectedKidsAgeProfile(rows, "first") }.exceptionOrNull() is ApiError.NotReady)
        }
        assertTrue(runCatching { selectedKidsAgeProfile(listOf(first), "") }.isFailure)
    }

    @Test fun `Web parent editor preserves server prefix and exact profile query only`() {
        val url = kidsAgeEditorUrl("https://user:password@server.example/app/prefix/?token=old#section".toHttpUrl(), "child id?other=value", false)!!
        assertEquals("/app/prefix/account/kids-settings", url.encodedPath)
        assertEquals("child id?other=value", url.queryParameter("profileId"))
        assertEquals(setOf("profileId"), url.queryParameterNames)
        assertEquals("", url.username)
        assertEquals("", url.password)
        assertNull(url.fragment)
        assertEquals("https://server.example/account/kids-settings?profileId=child", kidsAgeEditorUrl("https://server.example".toHttpUrl(), "child", false).toString())
    }

    @Test fun `remote or absent server cannot offer a browser editor link`() {
        assertNull(kidsAgeEditorUrl(null, "child", false))
        assertNull(kidsAgeEditorUrl("http://127.0.0.1:1234".toHttpUrl(), "child", true))
        assertNull(kidsAgeEditorUrl("https://server.example".toHttpUrl(), "", false))
    }
}
