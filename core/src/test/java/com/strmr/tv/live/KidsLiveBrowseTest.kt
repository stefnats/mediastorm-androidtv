package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KidsLiveBrowseTest {
    private val policy = KidsLivePolicy(true, "DE", 6, "cached-guide")
    private val programme = KidsLiveProgramme("Synthetic programme", "2026-11-22T09:00:00Z", "2026-11-22T09:30:00Z", 6, "DE",
        KidsLiveGuide("cached-guide", "xmltv", "Selected guide", "2026-11-22T08:00:00Z"), "selected_guide_claim")

    @Test fun menuRequiresAnExplicitValidPolicyAndPreservesAdultVisibility() {
        assertFalse(KidsLivePolicy().configured)
        assertFalse(ApiJson.decodeFromString<KidsLivePolicyReply>("{}").policy.configured)
        for (invalid in listOf(policy.copy(enabled = false), policy.copy(country = "FR"), policy.copy(guideSourceId = ""), policy.copy(maximumAge = 7))) {
            assertFalse(liveMenuVisible(true, invalid.configured, true))
        }
        assertTrue(liveMenuVisible(true, policy.configured, false))
        assertTrue(liveMenuVisible(false, false, true))
        assertFalse(liveMenuVisible(false, true, false))
    }

    @Test fun catalogueShowsUnknownAndTooOldWithoutTurningThemIntoPlayableChannels() {
        val eligible = KidsLiveChannel("exact-catalog-id", "Synthetic channel", "within_limit", programme = programme)
        val old = eligible.copy(id = "older", programmeState = "too_old", programme = programme.copy(age = 12))
        val unknown = ApiJson.decodeFromString<KidsLiveChannelsReply>("""{"channels":[{"id":"unknown","name":"Unclassified","programmeState":"unknown","programmeReason":"rating_missing"}],"total":1}""").channels.single()
        assertTrue(eligible.selectable)
        assertEquals("exact-catalog-id", eligible.channel().id)
        assertEquals("", eligible.channel().url)
        assertFalse(eligible.channel().hasStremioSources)
        assertFalse(old.selectable)
        assertFalse(unknown.selectable)
        assertFalse(eligible.copy(programme = null).selectable)
        assertTrue(eligible.description.contains("selected guide"))
        assertTrue(unknown.description.contains("No clear age"))
        for (entry in listOf(old, unknown)) {
            try { entry.channel(); fail("Ineligible channel selected") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun disabledPolicyNeverLoadsChannelsAndRefreshDropsNoLongerAllowedEntries() = runTest {
        var enabled = false
        var pages = 0
        val browser = KidsLiveBrowser(ProfileDataContext.current,
            readPolicy = { policy.copy(enabled = enabled) },
            readChannels = { pages++; KidsLiveChannelsReply(listOf(KidsLiveChannel("c", "Channel", "within_limit", programme = programme)), 1) })
        browser.load()
        assertEquals(0, pages)
        assertNotNull(browser.state.value.error)
        enabled = true; browser.load()
        assertEquals(1, pages)
        assertEquals(1, browser.state.value.channels.size)
        assertNull(browser.state.value.error)
        enabled = false; browser.load()
        assertEquals(1, pages)
        assertTrue(browser.state.value.channels.isEmpty())
        assertNotNull(browser.state.value.error)
    }
}
