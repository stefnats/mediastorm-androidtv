package com.strmr.tv.launcher

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.library.ProfileDataContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LauncherSnapshotTest {
    private val original = ApiClient.baseUrl
    @After fun restore() { ApiClient.baseUrl = original; ProfileDataContext.invalidate() }
    @Test fun `actual snapshot copies supplied prefetch hide-watched and nested alias inputs`() {
        val aliases = mutableMapOf("imdb" to "tt7"); val backdrops = mutableListOf("https://example.invalid/old")
        val rows = mutableListOf(ContinueWatchingItem("tmdb:movie:7", "Original", externalIds = aliases, backdropUrls = backdrops))
        val input = LauncherSnapshot.capture(ProfileDataContext.current, rows, true)
        aliases["imdb"] = "changed"; backdrops.clear(); rows.clear()
        assertTrue(input.hideWatched); assertEquals("Original", input.prefetched!!.single().seriesTitle)
        assertEquals("tt7", input.prefetched.single().externalIds!!["imdb"]); assertEquals(1, input.prefetched.single().backdropUrls!!.size)
    }
    @Test fun `actual resolver retains original base proxy prefix and encoded query exactly once`() {
        ApiClient.baseUrl = "https://first.invalid/reverse/".toHttpUrl()
        val input = LauncherSnapshot.capture(ProfileDataContext.current, null, false)
        val suffix = "/api/images/proxy?url=https%3A%2F%2Fimage.invalid%2Fa%3Fx%3D1%26y%3D2"
        assertEquals("https://first.invalid/reverse$suffix", input.absolute(suffix))
        assertEquals("https://first.invalid/reverse$suffix", input.absolute("/reverse$suffix"))
        assertEquals("https://first.invalid/reverse/api/image?q=a%2Bb", input.absolute("api/image?q=a%2Bb"))
        ApiClient.baseUrl = "https://second.invalid/other/".toHttpUrl()
        assertEquals("https://first.invalid/reverse$suffix", input.absolute(suffix))
    }
    @Test fun `no userinfo or new auth profile session source parameters reach provider URLs`() {
        ApiClient.baseUrl = "https://synthetic-user:synthetic-password@first.invalid/reverse/?token=synthetic".toHttpUrl()
        val input = LauncherSnapshot.capture(ProfileDataContext.current, null, false)
        assertEquals("https://first.invalid/reverse/api/image?q=original", input.absolute("/api/image?q=original"))
        assertNull(input.absolute("https://provider-user:provider-password@external.invalid/image"))
        assertNull(input.absolute("//provider-user@external.invalid/image")); assertNull(input.absolute("file:///private/image"))
    }
    @Test fun `ordinary external URLs remain exactly unchanged without native fields`() {
        val input = LauncherSnapshot.capture(ProfileDataContext.current, null, false)
        val raw = "https://external.invalid/image?token=provider-only&x=a%2Bb"
        assertEquals(raw, input.absolute(raw))
        assertFalse(input.absolute(raw)!!.contains("profileId="))
    }
    @Test fun `old snapshot cannot be revived after epoch A B A or capture stale ownership`() {
        val epoch = ProfileDataContext.current; val input = LauncherSnapshot.capture(epoch, null, false)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); assertFalse(input.isCurrent)
        assertTrue(runCatching { LauncherSnapshot.capture(epoch, null, false) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
    @Test fun `relative and original server native credential queries are rejected without rewriting`() {
        ApiClient.baseUrl = "https://first.invalid/reverse/".toHttpUrl()
        val input = LauncherSnapshot.capture(ProfileDataContext.current, null, false)
        for (key in listOf("token", "access_token", "accessToken", "session", "sessionToken", "profileUnlock", "unlockToken", "authorization", "password", "accountPassword", "pin", "profilePin", "api_key", "apiKey", "TOKEN", "%74oken", "%70rofile%55nlock")) {
            assertNull(key, input.absolute("/api/images/proxy?$key=synthetic"))
            assertNull(key, input.absolute("https://first.invalid/reverse/api/image?$key=synthetic"))
        }
        assertEquals("https://external.invalid/image?token=provider-only&signature=a%2Bb", input.absolute("https://external.invalid/image?token=provider-only&signature=a%2Bb"))
        assertEquals("https://first.invalid/reverse/api/image?q=original", input.absolute("/api/image?q=original"))
    }

    @Test fun `actual payload rejects credentials in visible and nested Continue and watchlist artwork`() {
        ApiClient.baseUrl = "https://first.invalid/reverse/".toHttpUrl()
        val input = LauncherSnapshot.capture(ProfileDataContext.current, null, false)
        val bad = "/api/images/proxy?TOKEN=native-secret"
        val userinfo = "https://synthetic-user:synthetic-password@provider.invalid/image"
        val watching = ContinueWatchingItem("tmdb:movie:7", "T", posterUrl = bad, textPosterUrl = userinfo,
            backdropUrl = bad, textBackdropUrl = userinfo, backdropUrls = listOf(bad), logoUrl = bad)
        val saved = com.strmr.tv.home.WatchlistItem("tmdb:movie:8", name = "T", posterUrl = bad, textPosterUrl = userinfo,
            backdropUrl = bad, textBackdropUrl = userinfo, backdropUrls = listOf(bad), logoUrl = bad)
        val payload = input.payload(listOf(watching), listOf(saved))
        for (program in payload.continueWatching + payload.watchlist) {
            assertNull(program.imageUrl)
            for (url in listOf(program.displayUrl, program.playUrl)) {
                assertFalse(url.contains("native-secret")); assertFalse(url.contains("synthetic-password"))
                val target = DeepLink.parse(url)!!.target
                assertNull(target.posterUrl); assertNull(target.textPosterUrl); assertNull(target.backdropUrl); assertNull(target.logoUrl)
            }
        }
    }
    @Test fun `actual payload retains allowed external signatures through image and DeepLink fields`() {
        val input = LauncherSnapshot.capture(ProfileDataContext.current, null, false)
        val external = "https://external.invalid/image?token=provider-only&signature=a%2Bb"
        val row = ContinueWatchingItem("tmdb:movie:7", "T", posterUrl = external, logoUrl = external)
        val program = input.payload(listOf(row), emptyList()).continueWatching.single()
        assertEquals(external, program.imageUrl); assertEquals(external, DeepLink.parse(program.displayUrl)!!.target.posterUrl)
        assertEquals(external, DeepLink.parse(program.playUrl)!!.target.logoUrl)
    }

}
