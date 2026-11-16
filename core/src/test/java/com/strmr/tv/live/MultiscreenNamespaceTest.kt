package com.strmr.tv.live

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MultiscreenNamespaceTest {
    private fun key(server: String = "https://example.invalid/proxy", account: String? = "account-a", profile: String = "profile", paired: Boolean = false) = multiscreenNamespace(server.toHttpUrl(), account, profile, paired)
    @Test fun `server account and profile each isolate private saved provider fields`() {
        val current = key(); assertNotEquals(current, key(server = "https://other.invalid/proxy")); assertNotEquals(current, key(account = "account-b")); assertNotEquals(current, key(profile = "other"))
        assertNotEquals(current, key(server = "https://example.invalid/other-proxy")); assertNotEquals(current, key(server = "https://example.invalid:444/proxy"))
    }
    @Test fun `configured namespace contains no userinfo query fragment or account identifiers`() {
        val a = key(server = "https://synthetic-user:synthetic-pass@example.invalid/proxy?token=synthetic-secret#private")
        assertEquals(key(), a); assertTrue(a!!.matches(Regex("last\\.v2\\.[0-9a-f]{64}")))
        assertFalse(a.contains("account")); assertFalse(a.contains("profile")); assertFalse(a.contains("secret"))
    }
    @Test fun `transient paired bridge and unknown account cannot read or save a layout`() {
        assertNull(key(server = "http://127.0.0.1:32123", paired = true)); assertNull(key(account = null)); assertNull(key(account = "")); assertNull(key(profile = ""))
        assertNull(multiscreenNamespace(null, "account", "profile", false))
    }
    @Test fun `legacy profile key cannot be an automatic namespace match`() {
        assertNotEquals("last.profile", key()); assertNotEquals(key(account = "a:1", profile = "bc"), key(account = "a", profile = "1:bc"))
    }
    @Test fun `home away transport URL changes do not alter configured canonical namespace`() {
        assertEquals(key(), key(server = "https://example.invalid/proxy/")); assertEquals(key(), key(server = "https://example.invalid/proxy?ignored=one"))
    }
    @Test fun `actual storage keeps legacy and foreign private layouts byte unchanged without adopting them`() {
        val owner = com.strmr.tv.player.PlaybackSessionContext.capture()
        val legacy = "legacy-private-provider-url"; val foreign = "foreign-private-provider-url"
        val ownKey = multiscreenNamespace("https://own.invalid/proxy".toHttpUrl(), "account", owner.profileId, false)!!
        val foreignKey = multiscreenNamespace("https://foreign.invalid/".toHttpUrl(), "account", owner.profileId, false)!!
        val raw = mutableMapOf("last.${owner.profileId}" to legacy, foreignKey to foreign)
        val storage = MultiscreenLayoutStorage({ raw[it] }, { key, value -> raw[key] = value })
        assertNull(storage.saved(owner, ownKey))
        val selection = SavedMultiscreen(listOf(LiveChannel("a", "A", "https://own.invalid/a"), LiveChannel("b", "B", "https://own.invalid/b")))
        storage.save(owner, ownKey, selection); assertEquals(selection, storage.saved(owner, ownKey))
        assertEquals(legacy, raw["last.${owner.profileId}"]); assertEquals(foreign, raw[foreignKey]); assertEquals(3, raw.size)
    }
    @Test fun `unknown namespace performs neither storage reads nor writes`() {
        val owner = com.strmr.tv.player.PlaybackSessionContext.capture(); var reads = 0; var writes = 0
        val storage = MultiscreenLayoutStorage({ reads++; null }, { _, _ -> writes++ })
        val selection = SavedMultiscreen(emptyList())
        assertNull(storage.saved(owner, null)); assertNull(storage.saved(owner, "last.${owner.profileId}"))
        storage.save(owner, null, selection); storage.save(owner, "last.${owner.profileId}", selection)
        assertEquals(0, reads); assertEquals(0, writes)
    }
    @Test fun `late storage access cannot read or save another profile epoch`() {
        val owner = com.strmr.tv.player.PlaybackSessionContext.capture(); val key = multiscreenNamespace("https://own.invalid/".toHttpUrl(), "account", owner.profileId, false)!!
        var reads = 0; var writes = 0; val storage = MultiscreenLayoutStorage({ reads++; null }, { _, _ -> writes++ })
        com.strmr.tv.library.ProfileDataContext.invalidate(); com.strmr.tv.library.ProfileDataContext.invalidate()
        try { storage.saved(owner, key); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        try { storage.save(owner, key, SavedMultiscreen(emptyList())); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, reads); assertEquals(0, writes)
    }
}
