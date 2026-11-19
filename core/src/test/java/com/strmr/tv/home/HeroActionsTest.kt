package com.strmr.tv.home

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.SourceAvailability
import com.strmr.tv.library.ProfileDataContext
import java.time.Instant
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HeroActionsTest {
    private val now = Instant.parse("2026-11-19T18:00:00Z")
    private fun missing() = SourceAvailability("not_found", now.minusSeconds(60).toString(), now.plusSeconds(60).toString(), complete = true, reason = "reported_candidates")
    private fun card(progress: Float = 0f) = HomeCard("hero", HomeLink.Details(DetailsTarget("tmdb:movie:42", "Synthetic hero", true)), "Synthetic hero", "", progress = progress)
    private data class Open(val target: DetailsTarget, val autoplay: Boolean, val prewarm: Boolean)
    private inner class Fixture(val original: HomeCard = card(), val tv: Boolean = true) {
        val context = ProfileDataContext.current
        val producer = Job()
        var selected = true
        var slide: HomeCard? = original
        var source: SourceAvailability? = null
        val prepared = mutableListOf<DetailsTarget>()
        val discarded = mutableListOf<Pair<DetailsTarget, Long>>()
        val opened = mutableListOf<Open>()
        var duringPrepare: () -> Unit = {}
        val actions = HeroActions(original, context, producer, { selected }, { slide }, { source }, tv,
            prepare = { prepared += it; duringPrepare() }, discard = { target, expected -> discarded += target to expected },
            open = { target, autoplay, prewarm -> opened += Open(target, autoplay, prewarm) }, now = { now })
    }
    @Before fun before() { ProfileDataContext.invalidate() }
    @After fun after() { ProfileDataContext.invalidate() }

    @Test fun `actual TV primary and Info callbacks do not prepare or autoplay a missing hero`() {
        for (info in listOf(false, true)) {
            val f = Fixture(card(.75f)); f.source = missing()
            assertTrue(if (info) f.actions.details() else f.actions.primary())
            assertTrue(f.prepared.isEmpty()); assertTrue(f.discarded.isEmpty())
            assertEquals(listOf(Open(f.original.target!!, false, false)), f.opened)
        }
    }

    @Test fun `actual TV candidate unknown and local callbacks retain existing explicit playback and Details behavior`() {
        for (source in listOf(null, missing().copy(state = "found", reason = "matching_candidates"), missing().copy(state = "found", sourceType = "local", reason = "local_file_checked"))) {
            for (info in listOf(false, true)) {
                val f = Fixture(); f.source = source
                assertTrue(if (info) f.actions.details() else f.actions.primary())
                assertEquals(listOf(f.original.target), f.prepared)
                assertEquals(listOf(Open(f.original.target!!, !info, true)), f.opened)
            }
        }
    }

    @Test fun `mobile Details route disables only missing-source preparation and never autoplays`() {
        for (source in listOf(null, missing())) {
            val f = Fixture(tv = false); f.source = source
            assertTrue(f.actions.details())
            assertTrue(f.prepared.isEmpty())
            assertEquals(listOf(Open(f.original.target!!, false, source == null)), f.opened)
        }
    }

    @Test fun `unbound embedded catalogue DTO and progress are not current viewport source proof`() {
        val original = card(.75f).copy(sourceAvailability = missing())
        val f = Fixture(original)
        assertTrue(f.actions.primary())
        assertEquals(listOf(Open(original.target!!, true, true)), f.opened)
        assertEquals(listOf(original.target), f.prepared)
    }

    @Test fun `a changed source is rechecked at press and after preparation before opening`() {
        val before = Fixture(); before.source = missing()
        assertTrue(before.actions.primary()); assertTrue(before.prepared.isEmpty())
        val during = Fixture()
        during.duringPrepare = { during.source = missing() }
        assertTrue(during.actions.primary())
        assertEquals(listOf(during.original.target!! to during.context), during.discarded)
        assertEquals(listOf(Open(during.original.target!!, false, false)), during.opened)
    }

    @Test fun `original epoch producer page and exact selected slide gate every action`() {
        for (change in listOf("epoch", "producer", "page", "slide")) {
            val f = Fixture()
            when (change) {
                "epoch" -> { ProfileDataContext.invalidate(); ProfileDataContext.invalidate() }
                "producer" -> f.producer.cancel()
                "page" -> f.selected = false
                "slide" -> f.slide = f.original.copy()
            }
            assertFalse(change, f.actions.primary()); assertFalse(change, f.actions.details())
            assertTrue(f.prepared.isEmpty()); assertTrue(f.opened.isEmpty())
            f.selected = true; f.slide = f.original
            assertFalse("retired action revived after $change", f.actions.primary())
        }
    }

    @Test fun `identity or selection lost during preparation retires publication and cleans only its original warm key`() {
        for (change in listOf("epoch", "producer", "page", "slide")) {
            val f = Fixture()
            f.duringPrepare = {
                when (change) {
                    "epoch" -> { ProfileDataContext.invalidate(); ProfileDataContext.invalidate() }
                    "producer" -> f.producer.cancel()
                    "page" -> f.selected = false
                    "slide" -> f.slide = card()
                }
            }
            assertFalse(change, f.actions.primary())
            assertTrue(f.opened.isEmpty())
            assertEquals(listOf(f.original.target!! to f.context), f.discarded)
        }
    }

    @Test fun `a presented missing Details action cannot upgrade to autoplay after its evidence expires`() {
        for (tv in listOf(false, true)) {
            val f = Fixture(tv = tv)
            // The button was drawn while MISSING; the current hint has since become unknown.
            f.source = null
            assertTrue(if (tv) f.actions.primary(presentedMissing = true) else f.actions.details(presentedMissing = true))
            assertTrue(f.prepared.isEmpty())
            assertEquals(listOf(Open(f.original.target!!, false, false)), f.opened)
        }
    }

    @Test fun `equal-valued replacement shelves expose current instances and renew hero actions without reviving old callbacks`() {
        val first = card()
        val replacement = first.copy()
        val originalShelf = HomeShelf("hero", "Hero", listOf(first))
        val replacedShelf = originalShelf.copy(cards = listOf(replacement))
        assertEquals(originalShelf, replacedShelf)
        assertNotSame(originalShelf, replacedShelf)
        assertNotSame(first, replacement)
        assertSame(first, heroSlides(originalShelf).single())
        assertSame(replacement, heroSlides(replacedShelf).single())
        assertNotEquals(com.strmr.tv.library.CustomListSelectionKey(first), com.strmr.tv.library.CustomListSelectionKey(replacement))
        val context = ProfileDataContext.current
        val producer = Job()
        var selected = heroSlides(originalShelf).single()
        val opened = mutableListOf<DetailsTarget>()
        fun actions(value: HomeCard) = HeroActions(value, context, producer, { true }, { selected }, { null },
            prepareBeforeOpen = false, prepare = {}, discard = { _, _ -> }, open = { target, _, _ -> opened += target }, now = { now })
        val old = actions(first)
        selected = heroSlides(replacedShelf).single()
        val fresh = actions(replacement)
        assertFalse(old.primary())
        assertTrue(fresh.primary())
        assertEquals(listOf(replacement.target), opened)
        selected = first
        assertFalse(old.primary())
        assertFalse(fresh.primary())
        assertEquals(1, opened.size)
    }

    @Test fun `disposed hero callbacks and non-detail cards cannot publish playback or navigation`() {
        val f = Fixture(); f.actions.close(); assertFalse(f.actions.primary()); assertFalse(f.actions.details())
        assertTrue(f.prepared.isEmpty()); assertTrue(f.opened.isEmpty())
        val other = Fixture(card().copy(link = HomeLink.Action(HomeAction.SURPRISE)))
        assertFalse(other.actions.primary()); assertTrue(other.prepared.isEmpty()); assertTrue(other.opened.isEmpty())
    }
}
