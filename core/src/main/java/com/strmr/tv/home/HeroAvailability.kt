package com.strmr.tv.home

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.SourceAvailability
import com.strmr.tv.player.PlaybackSessionContext
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** A reload may replace equal-valued cards; selected hero actions need the current instances. */
fun heroSlides(shelf: HomeShelf?): List<HomeCard> = shelf?.let(CarouselPick::slides).orEmpty()

/** A source hint changes presentation, never title, profile or playback permission. */
data class HeroCta(val label: String, val confirmedMissing: Boolean, val hint: SourceHint?)

fun heroCta(progress: Float, hint: SourceHint?): HeroCta {
    val missing = hint?.kind == SourceHint.Kind.MISSING
    return HeroCta(if (missing) "Details" else if (progress > 0f) "Resume" else "Play", missing, hint)
}

/** One selected hero's synchronous actions; a covered/replaced producer cannot revive them. */
class HeroActions(
    private val card: HomeCard,
    private val expectedContext: Long,
    private val producer: Job,
    private val selected: () -> Boolean,
    private val currentSlide: () -> HomeCard?,
    private val source: () -> SourceAvailability?,
    private val prepareBeforeOpen: Boolean,
    private val prepare: (DetailsTarget) -> Unit,
    private val discard: (DetailsTarget, Long) -> Unit,
    private val open: (DetailsTarget, Boolean, Boolean) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    private val owner = try { PlaybackSessionContext.capture(expectedContext) } catch (_: CancellationException) { null }
    private var retired = false

    private fun current(): Boolean {
        if (retired) return false
        if (owner?.isCurrent() != true || !producer.isActive || !selected() || currentSlide() !== card) {
            retired = true
            return false
        }
        return true
    }

    fun close() { retired = true }

    fun primary(presentedMissing: Boolean = false): Boolean = perform(primary = true, presentedMissing = presentedMissing)
    fun details(presentedMissing: Boolean = false): Boolean = perform(primary = false, presentedMissing = presentedMissing)

    private fun perform(primary: Boolean, presentedMissing: Boolean): Boolean {
        val target = card.target ?: return false
        if (!current()) return false
        var prepared = false
        return try {
            requireNotNull(owner).use {
                if (!current()) return@use false
                val initial = heroCta(card.progress, SourceHint.from(source(), now()))
                if (prepareBeforeOpen && !presentedMissing && !initial.confirmedMissing) {
                    prepare(target)
                    prepared = true
                }
                if (!current()) {
                    if (prepared) discard(target, expectedContext)
                    return@use false
                }
                // Accept source changes before leaving Home, rather than retaining the drawn label.
                val accepted = heroCta(card.progress, SourceHint.from(source(), now()))
                if (prepared && accepted.confirmedMissing) {
                    discard(target, expectedContext)
                    prepared = false
                }
                open(target, primary && !presentedMissing && !accepted.confirmedMissing, !presentedMissing && !accepted.confirmedMissing)
                true
            }
        } catch (_: CancellationException) {
            if (prepared) discard(target, expectedContext)
            false
        }
    }
}
