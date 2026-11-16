package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.*
import com.strmr.tv.ui.lists.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeedStagesTest {
    private suspend fun canceled(read: suspend () -> Any?) { try { read(); fail("Retired stage survived") } catch (_: CancellationException) { } }
    @Test fun `live settings retirement prevents channels and guide demand`() = runTest {
        val expected = ProfileDataContext.current; var later = 0
        canceled { readFavoriteChannelCards(20, expected,
            { ProfileDataContext.invalidate(); listOf("one") to emptyList() },
            { _, _, _ -> later++; emptyList() }, { later++; true }, { _, _ -> later++; emptyList() }) }
        assertEquals(0, later)
    }
    @Test fun `live stages retain original owner favorites order and hidden exclusion`() = runTest {
        val expected = ProfileDataContext.current; val owners = mutableListOf<Long>()
        val cards = readFavoriteChannelCards(2, expected,
            { owners += it; listOf("b", "hidden", "a") to listOf("hidden") },
            { ids, limit, owner -> owners += owner; assertEquals(listOf("b", "a"), ids); assertEquals(2, limit)
                listOf(LiveChannel("a", "A", "synthetic-a"), LiveChannel("b", "B", "synthetic-b")) },
            { owners += it; true }, { _, owner -> owners += owner; emptyList() })
        assertEquals(listOf("B", "A"), cards.map { it.title }); assertEquals(4, owners.size); assertTrue(owners.all { it == expected })
    }
    @Test fun `ordinary guide failure retains channel names but terminal guide denial does not`() = runTest {
        suspend fun load(error: Exception) = readFavoriteChannelCards(20, ProfileDataContext.current,
            { listOf("one") to emptyList() }, { _, _, _ -> listOf(LiveChannel("one", "One", "synthetic")) }, { throw error }, { _, _ -> error("No now demand") })
        assertEquals("One", load(IOException("synthetic")).single().title)
        val denied = ApiError.Http(HttpFailure(403, "{}"))
        try { load(denied); fail("Denial used channel fallback") } catch (error: ApiError) { assertSame(denied, error) }
    }
    @Test fun `guide chunk retirement prevents subsequent chunk demand`() = runTest {
        var requests = 0; val expected = ProfileDataContext.current
        canceled { readFavoriteChannelCards(60, expected,
            { (0 until 60).map(Int::toString) to emptyList() },
            { _, _, _ -> (0 until 60).map { LiveChannel("$it", "$it", "synthetic", tvgId = "$it") } },
            { true }, { _, _ -> requests++; ProfileDataContext.invalidate(); emptyList() }) }
        assertEquals(1, requests)
    }
    @Test fun `queued hub children stop before preview cache or demand`() = runTest {
        val expected = ProfileDataContext.current; var requests = 0
        val link = HomeSource.HubLink("synthetic", "Synthetic", BrowseList.Watchlist, null, null)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { HubCards.cards(listOf(link), 20, expected) { _, _ -> requests++; TileArt() } }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); canceled { pending.await() }; assertEquals(0, requests)
    }
    @Test fun `late hub preview cannot publish a new profile card`() = runTest {
        val expected = ProfileDataContext.current; val reply = CompletableDeferred<Unit>()
        val link = HomeSource.HubLink("synthetic", "Synthetic", BrowseList.Watchlist, null, null)
        val pending = async { HubCards.cards(listOf(link), 20, expected) { _, owner ->
            assertEquals(expected, owner); withContext(NonCancellable) { reply.await() }; TileArt(posters = listOf("synthetic"))
        } }
        runCurrent(); ProfileDataContext.invalidate(); reply.complete(Unit); canceled { pending.await() }
    }
    @Test fun `strict preview cache rejects old owner before cached hit or read`() = runTest {
        var owner = 10L; val cache = ProfilePreviewCache(context = { owner }); var reads = 0
        cache.art("synthetic", null, expectedContext = 10) { reads++; TileArt(posters = listOf("old")) }
        owner = 11
        canceled { cache.art("synthetic", null, expectedContext = 10) { reads++; TileArt() } }
        assertEquals(1, reads)
    }
    @Test fun `strict preview denial propagates while legacy tile optional behavior remains`() = runTest {
        val cache = ProfilePreviewCache(context = { 10L }); val denied = ApiError.Http(HttpFailure(403, "{}"))
        try { cache.art("synthetic", null, expectedContext = 10) { throw denied }; fail("Denied cache returned art") } catch (error: ApiError) { assertSame(denied, error) }
        assertEquals(TileArt(), cache.art("legacy", null) { throw IOException("synthetic") })
    }
    @Test fun `strict preview late old read cannot overwrite replacement cache`() = runTest {
        var owner = 10L; val cache = ProfilePreviewCache(context = { owner }); val reply = CompletableDeferred<Unit>()
        val old = async { cache.art("synthetic", null, expectedContext = 10) { withContext(NonCancellable) { reply.await() }; TileArt(posters = listOf("old")) } }
        runCurrent(); owner = 11
        val fresh = cache.art("synthetic", null, expectedContext = 11) { TileArt(posters = listOf("fresh")) }
        reply.complete(Unit); canceled { old.await() }
        assertEquals(fresh, cache.art("synthetic", null, expectedContext = 11) { error("Must hit current cache") })
    }
}
