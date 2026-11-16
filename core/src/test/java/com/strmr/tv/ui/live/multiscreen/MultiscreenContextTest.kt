package com.strmr.tv.ui.live.multiscreen

import com.strmr.tv.api.ApiClient
import com.strmr.tv.live.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.api.VodAccessDenial
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Production launch/source/start helpers; no Activity, decoder or Android credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiscreenContextTest {
    private suspend fun canceled(action: suspend () -> Unit) { try { action(); fail("Retired owner survived") } catch (_: CancellationException) { } }
    @Test fun `launch receipt is private once only and unknown extras cannot open anything`() {
        val pending = MultiscreenPending(); val owner = PlaybackSessionContext.capture(); val launch = MultiscreenLaunch(owner, null, null)
        val id = pending.put(launch); assertNull(pending.take(null)); assertNull(pending.take("forged")); assertSame(launch, pending.take(id)); assertNull(pending.take(id))
    }
    @Test fun `superseded pending receipt cannot consume replacement launch`() {
        val pending = MultiscreenPending(); val owner = PlaybackSessionContext.capture(); val old = pending.put(MultiscreenLaunch(owner, null, null))
        val current = MultiscreenLaunch(owner, null, null); val id = pending.put(current)
        assertNull(pending.take(old)); assertSame(current, pending.take(id))
    }
    @Test fun `delayed Activity cannot adopt original launch after simulated A B A`() {
        val pending = MultiscreenPending(); val owner = PlaybackSessionContext.capture(); val id = pending.put(MultiscreenLaunch(owner, null, null))
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); assertNull(pending.take(id))
    }
    @Test fun `fullscreen handover keeps exact original context and stream without rebinding`() {
        val owner = PlaybackSessionContext.capture(); val channel = LiveChannel("synthetic", "Synthetic", "https://example.invalid/live"); val stream = LiveStream("https://example.invalid/hls", "owned")
        val handover = MultiscreenHandover(channel, stream, owner); val pending = MultiscreenPending(); val launch = MultiscreenLaunch(owner, handover, null)
        assertSame(owner, pending.take(pending.put(launch))!!.handover!!.ownership); assertSame(stream, handover.stream)
        ProfileDataContext.invalidate(); assertFalse(handover.ownership.isCurrent())
    }
    @Test fun `queued original producer performs no capacity or start request under replacement epoch`() = runTest {
        val owner = PlaybackSessionContext.capture(); var calls = 0
        val job = async { readMultiscreenStart(owner, {}, { calls++; true }, { calls++; LiveStream("synthetic", null) }) }
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); canceled { job.await() }; assertEquals(0, calls)
    }
    @Test fun `late original capacity answer cannot open a replacement profile stream`() = runTest {
        val owner = PlaybackSessionContext.capture(); val reply = CompletableDeferred<Boolean>(); var opens = 0
        val job = async { readMultiscreenStart(owner, {}, { withContext(NonCancellable) { reply.await() } }, { opens++; LiveStream("synthetic", null) }) }
        runCurrent(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); reply.complete(true); canceled { job.await() }; assertEquals(0, opens)
    }
    @Test fun `same owner newer operation prevents old capacity answer starting obsolete channel`() = runTest {
        val owner = PlaybackSessionContext.capture(); val reply = CompletableDeferred<Boolean>(); var current = true; var opens = 0
        val job = async { readMultiscreenStart(owner, { if (!current) throw CancellationException() }, { withContext(NonCancellable) { reply.await() } }, { opens++; LiveStream("synthetic", null) }) }
        runCurrent(); current = false; reply.complete(true); canceled { job.await() }; assertEquals(0, opens)
    }
    @Test fun `normal producer uses original epoch for both stages and ordinary capacity limit does not open`() = runTest {
        val owner = PlaybackSessionContext.capture(); val epochs = mutableListOf<Long>()
        val stream = readMultiscreenStart(owner, {}, { epochs += it; true }, { epochs += it; LiveStream("synthetic", "owned") })
        assertEquals(listOf(owner.epoch, owner.epoch), epochs); assertEquals("owned", stream.sessionId)
        try { readMultiscreenStart(owner, {}, { false }, { fail("No capacity"); stream }); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun `actual stale capacity and guide API wrappers reject before Android stores or HTTP`() = runTest {
        val owner = PlaybackSessionContext.capture(); ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        canceled { ApiClient.liveUsage("synthetic", owner.epoch) }; canceled { ApiClient.epgNow(listOf("synthetic"), owner.epoch) }
    }
    @Test fun `old source and closed tile cannot retry or publish into current tile`() {
        val owner = PlaybackSessionContext.capture(); var source = true; val life = TilePlaybackLifetime(owner) { source }; var effects = 0
        assertTrue(life.use { effects++ }); source = false; assertFalse(life.use { effects++ }); assertFalse(life.deny(VodAccessDenial.BLOCKED) { effects++ })
        source = true; life.close(); assertFalse(life.use { effects++ }); assertEquals(1, effects)
    }
    @Test fun `known denial retires tile before old retry or listener callback can act`() {
        val life = TilePlaybackLifetime(PlaybackSessionContext.capture()) { true }; var denials = 0; var retries = 0
        assertTrue(life.deny(VodAccessDenial.BLOCKED) { denials++ }); assertFalse(life.use { retries++ }); assertFalse(life.deny(VodAccessDenial.BLOCKED) { denials++ })
        assertEquals(1, denials); assertEquals(0, retries)
    }
    @Test fun `original tile rejects effects after epoch retirement even with unchanged source instance`() {
        val life = TilePlaybackLifetime(PlaybackSessionContext.capture()) { true }; var effects = 0
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        assertFalse(life.current); try { life.use { effects++ }; fail() } catch (_: CancellationException) { }
        assertEquals(0, effects)
    }
    @Test fun `actual stale launcher entry points return before Android Activity or credential access`() {
        val owner = PlaybackSessionContext.capture(); val channel = LiveChannel("synthetic", "Synthetic", "https://example.invalid/live")
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        MultiscreenLauncher.open(expectedContext = owner.epoch)
        MultiscreenLauncher.open(MultiscreenHandover(channel, LiveStream("synthetic", "owned"), owner), owner.epoch)
        MultiscreenLauncher.resume(SavedMultiscreen(listOf(channel, channel)), owner.epoch)
        assertNull(MultiscreenLauncher.take("untrusted-intent"))
    }
}
