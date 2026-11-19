package com.strmr.tv.home

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.SourceAvailability
import com.strmr.tv.api.TitleSourceCoordinate
import com.strmr.tv.api.TitleSourceDemand
import com.strmr.tv.api.TitleSourceItem
import com.strmr.tv.api.TitleSourceResponse
import com.strmr.tv.library.ProfileDataContext
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleSourceGatewayEpochTest {
    private val title = TitleSourceCoordinate("tmdb:movie:42", "movie")
    private fun missing() = SourceAvailability("not_found", Instant.now().minusSeconds(60).toString(), Instant.now().plusSeconds(60).toString(), complete = true, reason = "no_matching_candidates")
    private fun reply(body: TitleSourceDemand) = TitleSourceResponse(true, body.version, body.titles.map { TitleSourceItem(it.titleId, it.mediaType, missing()) })
    @Before fun before() { ProfileDataContext.invalidate() }
    @After fun after() { ProfileDataContext.invalidate() }

    @Test fun `production gateway passes its original epoch and selected profile path to the sender`() = runTest {
        val expected = ProfileDataContext.current
        val path = ApiClient.profilePath
        val calls = mutableListOf<Triple<String, TitleSourceDemand, Long>>()
        val gateway = ServerTitleSourceGateway { sentPath, body, context -> calls += Triple(sentPath, body, context); reply(body) }
        val body = TitleSourceDemand(7, listOf(title))
        assertEquals(7L, gateway.update("synthetic-view", body).version)
        assertEquals(listOf(Triple("$path/source-availability/titles/synthetic-view", body, expected)), calls)
    }

    @Test fun `same-profile ABA before sending cannot revive the original production gateway`() = runTest {
        var calls = 0
        val profile = ApiClient.profileId; val server = ApiClient.baseUrl; val account = ApiClient.session?.accountId
        val gateway = ServerTitleSourceGateway { _, body, _ -> calls++; reply(body) }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertEquals(profile, ApiClient.profileId); assertEquals(server, ApiClient.baseUrl); assertEquals(account, ApiClient.session?.accountId)
        assertFalse(gateway.isCurrent())
        try { gateway.update("synthetic-view", TitleSourceDemand(1, listOf(title))); fail("old epoch was sent") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, calls)
    }

    @Test fun `held completed negative response from the actual gateway cannot cross a profile ABA`() = runTest {
        val entered = CompletableDeferred<TitleSourceDemand>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val gateway = ServerTitleSourceGateway { _, body, _ -> calls++; entered.complete(body); release.await(); reply(body) }
        val values = mutableListOf<Map<String, SourceAvailability>>()
        val job = launch { TitleSourceObserver(gateway).states(listOf(title)).collect { values += it } }
        runCurrent(); entered.await(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); release.complete(Unit); runCurrent(); job.join()
        assertFalse(gateway.isCurrent()); assertEquals(1, calls)
        assertTrue(values.isNotEmpty()); assertTrue(values.all { it.isEmpty() })
    }

    @Test fun `production coordinator store clears completed cached MISSING on same-profile ABA`() = runTest {
        val contexts = mutableListOf<Long>()
        val store = TitleSourceCoordinatorStore(backgroundScope) {
            ServerTitleSourceGateway { _, body, context -> contexts += context; reply(body) }
        }
        val firstEpoch = ProfileDataContext.current
        val first = store.current(); first.setVisible("hero", title)
        advanceTimeBy(151); runCurrent()
        assertEquals(SourceHint.Kind.MISSING, SourceHint.from(first.lastKnown.value[titleSourceKey(title)])?.kind)
        assertSame(first, store.current())
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        val second = store.current()
        assertNotSame(first, second); assertTrue(first.hints.value.isEmpty()); assertTrue(first.lastKnown.value.isEmpty())
        assertTrue(second.hints.value.isEmpty()); assertTrue(second.lastKnown.value.isEmpty())
        second.setVisible("hero", title); advanceTimeBy(151); runCurrent()
        assertEquals(SourceHint.Kind.MISSING, SourceHint.from(second.lastKnown.value[titleSourceKey(title)])?.kind)
        assertEquals(listOf(firstEpoch, ProfileDataContext.current), contexts)
        second.close()
    }
}
