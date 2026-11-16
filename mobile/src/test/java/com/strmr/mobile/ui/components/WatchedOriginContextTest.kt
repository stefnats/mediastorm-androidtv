package com.strmr.mobile.ui.components

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HomeLink
import com.strmr.tv.library.ProfileDataContext
import com.strmr.mobile.ui.details.WatchStateOrigin
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual phone host/origin state; no Compose composition, Android activation or metadata IO. */
class WatchedOriginContextTest {
    private val target = DetailsTarget("tmdb:series:41", "Synthetic", false)
    private fun card() = HomeCard(target.titleId, HomeLink.Details(target), target.name, "")
    @After fun after() { TitleMenu.watchStateFor?.let(TitleMenu::clearWatchState); TitleMenu.dismiss(); ProfileDataContext.invalidate() }
    @Test fun `actual menu carries original producer before dismiss and deferred watch-state composition`() {
        val parent = Job(); val epoch = ProfileDataContext.current
        TitleMenu.show(card(), epoch, parent); val selection = TitleMenu.selection!!
        val original = TitleMenu.parentFor(selection)!!; assertSame(parent, original)
        TitleMenu.dismiss(selection); TitleMenu.openWatchState(target, epoch, original)
        val opened = TitleMenu.watchStateFor!!; assertSame(target, opened.target); assertSame(parent, opened.parent); assertEquals(epoch, opened.context)
        parent.cancel(); assertNull(TitleMenu.watchStateFor)
    }
    @Test fun `late original A B A cannot open new owner or retire replacement dialog`() {
        val parent = Job(); val old = ProfileDataContext.current
        TitleMenu.openWatchState(target, old, parent); val original = TitleMenu.watchStateFor!!
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); assertNull(TitleMenu.watchStateFor)
        TitleMenu.openWatchState(target.copy(), ProfileDataContext.current, parent); val current = TitleMenu.watchStateFor!!
        TitleMenu.openWatchState(target, old, parent); TitleMenu.clearWatchState(original)
        assertSame(current, TitleMenu.watchStateFor); parent.cancel()
    }
    @Test fun `cancelled producer and stale constructor cannot mint a usable watch-state origin`() {
        val parent = Job(); parent.cancel(); TitleMenu.openWatchState(target, ProfileDataContext.current, parent); assertNull(TitleMenu.watchStateFor)
        val epoch = ProfileDataContext.current; ProfileDataContext.invalidate()
        assertTrue(runCatching { WatchStateOrigin(target, epoch, Job()) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
    @Test fun `equal-valued replacement menu never supplies new parent to old selection`() {
        val first = Job(); val second = Job(); TitleMenu.show(card(), ProfileDataContext.current, first); val old = TitleMenu.selection!!
        TitleMenu.show(card(), ProfileDataContext.current, second); val current = TitleMenu.selection!!
        assertNull(TitleMenu.parentFor(old)); assertSame(second, TitleMenu.parentFor(current)); first.cancel(); second.cancel()
    }
}
