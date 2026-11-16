package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ArtworkImageAccessTest {
    private val headers = mapOf("Authorization" to "Bearer synthetic-backend", "X-Profile-Unlock" to "synthetic-unlock", "X-Remote-Access-Credential" to "synthetic-remote", "X-Client-ID" to "synthetic-client")
    private fun access(base: String = "https://example.invalid/proxy/") = ArtworkImageAccess(base.toHttpUrl(), headers, "synthetic-profile") { true }
    @Test fun `only exact origin registered image routes receive native credentials`() {
        val access = access()
        val public = listOf("users/other/icon", "branding/images/logo", "sports/logo", "images/proxy", "images/gif-first-frame", "static/rating_icons/tv-ma.png", "static/icon-rounded.png")
        for (path in public) assertEquals(path, ArtworkImageAccess.Route.PUBLIC, access.route("https://example.invalid/proxy/api/$path".toHttpUrl()))
        assertEquals(ArtworkImageAccess.Route.PRIVATE, access.route("https://example.invalid/proxy/api/library/items/plex:item/artwork/poster".toHttpUrl()))
        for (path in listOf("branding", "images/warm", "static/admin.js", "static/unregistered.png", "admin/status", "library/items/id/artwork/poster/extra", "users/id/icon/extra", "library/items/id%2Fextra/artwork/poster", "library/items/id/artwork/file%5Cextra")) {
            assertEquals(path, ArtworkImageAccess.Route.NONE, access.route("https://example.invalid/proxy/api/$path".toHttpUrl()))
        }
        for (url in listOf("http://example.invalid/proxy/api/users/id/icon", "https://example.invalid:444/proxy/api/users/id/icon", "https://example.invalid/api/users/id/icon", "https://other.invalid/proxy/api/users/id/icon", "https://example.invalid/proxy-other/api/users/id/icon")) assertEquals(url, ArtworkImageAccess.Route.NONE, access.route(url.toHttpUrl()))
    }
    @Test fun `encoded queries duplicate values proxy prefix and directory bases roundtrip`() {
        for (base in listOf("https://example.invalid/proxy/", "https://example.invalid/proxy")) {
            val access = access(base)
            for (raw in listOf("/api/library/items/id/artwork/poster?x=a%2Bb&x=c%26d&flag", "/proxy/api/library/items/id/artwork/poster?x=a%2Bb&x=c%26d&flag", "api/library/items/id/artwork/poster?x=a%2Bb&x=c%26d&flag", "https://example.invalid/proxy/api/library/items/id/artwork/poster?x=a%2Bb&x=c%26d&flag")) {
                val url = access.url(raw)!!.toHttpUrl()
                assertEquals("/proxy/api/library/items/id/artwork/poster", url.encodedPath)
                assertEquals("x=a%2Bb&x=c%26d&flag&profileId=synthetic-profile", url.encodedQuery)
                assertEquals(listOf("a+b", "c&d"), url.queryParameterValues("x"))
            }
        }
    }
    @Test fun `public icon may name a different profile without active profile being appended`() {
        val raw = "/api/users/another-profile/icon?v=a%2Bb&userId=other&profileId=other"
        assertEquals("https://example.invalid/proxy$raw", access().url(raw))
        assertEquals("https://example.invalid/proxy/api/static/rating_icons/r.png", access().url("/api/static/rating_icons/r.png"))
    }
    @Test fun `protected profile selectors must all match without silently replacing foreign values`() {
        for (query in listOf("profileId=other", "profileId=", "profileId", "PROFILEID=other", "profileId=synthetic-profile&profileId=other", "profileId=synthetic-profile&userId=other")) assertNull(access().url("/api/library/items/id/artwork/poster?$query"))
        assertEquals("userId=synthetic-profile&profileId=synthetic-profile", access().url("/api/library/items/id/artwork/poster?userId=synthetic-profile&profileId=synthetic-profile")!!.toHttpUrl().encodedQuery)
    }
    @Test fun `provider query remains verbatim but exact captured backend secret is unavailable`() {
        val raw = "https://provider.invalid/image?token=synthetic-provider&x=a%2Bb&x=two&profileId=provider"
        assertEquals(raw, access().url(raw))
        for (value in listOf("synthetic-backend", "synthetic-unlock", "synthetic-remote", "Bearer%20synthetic-backend")) assertNull(access().url("https://provider.invalid/image?anything=$value"))
    }
    @Test fun `original HTTP scope cannot become current again after A B A`() {
        val owner = PlaybackSessionContext.capture()
        val access = ArtworkImageAccess("https://example.invalid/".toHttpUrl(), headers, owner.profileId, owner::isCurrent)
        assertNotNull(access.url("/api/users/other/icon"))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertNull(access.url("/api/users/other/icon"))
    }
}
