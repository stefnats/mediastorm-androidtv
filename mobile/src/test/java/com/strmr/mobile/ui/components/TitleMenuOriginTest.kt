package com.strmr.mobile.ui.components

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HomeLink
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual phone menu host state, independent of Compose/Android sheet execution. */
class TitleMenuOriginTest {
    private fun card() = DetailsTarget("tmdb:movie:17", "Synthetic", true).let { HomeCard(it.titleId, HomeLink.Details(it), it.name, "") }
    @After fun after() { TitleMenu.dismiss(); ProfileDataContext.invalidate() }
    @Test fun `show stores original selection before deferred host composition`() {
        val parent = Job(); val card = card(); val expected = ProfileDataContext.current
        TitleMenu.show(card, expected, parent, continueWatching = true)
        val selection = TitleMenu.selection!!; assertSame(card, selection.card); assertEquals(expected, selection.origin.context); assertTrue(selection.continueWatching)
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate(); assertNull(TitleMenu.selection); assertNull(TitleMenu.card); parent.cancel()
    }
    @Test fun `delayed old producer show cannot replace a current menu after A B A`() {
        val parent = Job(); val old = ProfileDataContext.current; val card = card()
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        TitleMenu.show(card.copy(title = "Current"), ProfileDataContext.current, parent)
        val current = TitleMenu.selection!!
        TitleMenu.show(card, old, parent); assertSame(current, TitleMenu.selection); parent.cancel()
    }
    @Test fun `equal-valued replacement closes old receipt and old dismiss cannot clear new menu`() {
        val parent = Job(); val card = card()
        TitleMenu.show(card, ProfileDataContext.current, parent); val old = TitleMenu.selection!!
        TitleMenu.show(card.copy(), ProfileDataContext.current, parent); val current = TitleMenu.selection!!
        assertNotSame(old, current); assertFalse(old.isCurrent); TitleMenu.dismiss(old); assertSame(current, TitleMenu.selection); parent.cancel()
    }
    @Test fun `cancelled original producer cannot be reopened through live global host`() {
        val parent = Job(); val card = card(); TitleMenu.show(card, ProfileDataContext.current, parent)
        parent.cancel(); assertNull(TitleMenu.selection)
        TitleMenu.show(card, ProfileDataContext.current, parent); assertNull(TitleMenu.selection)
    }
    @Test fun `current explicit dismiss retires actual original receipt synchronously`() {
        val parent = Job(); TitleMenu.show(card(), ProfileDataContext.current, parent); val selection = TitleMenu.selection!!
        TitleMenu.dismiss(selection); assertFalse(selection.isCurrent); assertNull(TitleMenu.card); parent.cancel()
    }
}
