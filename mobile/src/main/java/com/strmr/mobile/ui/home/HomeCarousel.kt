package com.strmr.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.strmr.mobile.ui.components.ArtImage
import com.strmr.mobile.ui.components.ProgressLine
import com.strmr.mobile.ui.components.cardClicks
import com.strmr.mobile.ui.components.showsProgress
import com.strmr.tv.api.Artwork
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HeroActions
import com.strmr.tv.home.heroCta
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.CustomListSelectionKey
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.ui.SourceHintBadge
import com.strmr.tv.ui.rememberSourceHint
import com.strmr.tv.ui.rememberVisibleTitleSource
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.mobile.ui.shell.Route
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** How long a carousel slide stays before the next slides in (the iPhone app's eight seconds). */
private const val SLIDE_MS = 8_000L

/**
 * Home's Top Carousel on a phone: the slides from the row Home Layout picks (Top 10, else
 * Trending, else the first row with titles; `CarouselPick`), as a paged row of large backdrop
 * tiles with the title's logo and line. It moves on by itself every eight seconds; a swipe
 * starts the eight seconds over. Tap opens, long press shows the title menu.
 */
@Composable
fun HomeCarousel(slides: List<HomeCard>, onCard: (HomeCard) -> Unit, onMenu: (HomeCard) -> Unit,
                 dataContext: Long, parent: Job, selected: () -> Boolean, currentSlides: () -> List<HomeCard>) {
    if (slides.isEmpty()) return
    val pager = rememberPagerState { slides.size }
    val navigator = LocalNavigator.current
    val landscapePhone = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.smallestScreenWidthDp < 600 }
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress, slides.size) {
        if (slides.size < 2 || pager.isScrollInProgress) return@LaunchedEffect
        delay(SLIDE_MS)
        pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileWidth = maxWidth - HomeMetrics.gutter * 2
        // Shorter on a tablet, and a fixed strip on a phone on its side, so rows show under it.
        val height = when {
            landscapePhone -> 210.dp
            HomeMetrics.wide() -> tileWidth * 9f / 21f
            else -> tileWidth * 9f / 16f
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalPager(
                pager,
                contentPadding = PaddingValues(horizontal = HomeMetrics.gutter),
                pageSpacing = 12.dp,
                modifier = Modifier.height(height),
                key = { slides[it].id },
            ) { page ->
                val card = slides[page]
                fun selectedHero() = dataContext == ProfileDataContext.current && parent.isActive && selected() && page == pager.currentPage && !pager.isScrollInProgress && currentSlides().getOrNull(page) === card
                val active = selectedHero()
                val visibleSource = rememberVisibleTitleSource(card, enabled = active)
                val source by rememberUpdatedState(visibleSource.source)
                val hint = rememberSourceHint(visibleSource.source)
                val actions = remember(CustomListSelectionKey(card), dataContext, parent, active) {
                    HeroActions(card, dataContext, parent,
                        selected = { selectedHero() },
                        currentSlide = { currentSlides().getOrNull(pager.currentPage) }, source = { source },
                        prepareBeforeOpen = false, prepare = PlaybackCoordinator::prewarm,
                        discard = { value, expected -> PlaybackCoordinator.invalidateWarmSources(value.titleId, expected) },
                        open = { value, autoplay, prewarm -> navigator.push(Route.Details(value, autoplay = autoplay, prewarmOnOpen = prewarm)) })
                }
                DisposableEffect(actions) { onDispose { actions.close() } }
                CarouselTile(card, hint, Modifier.fillMaxSize().then(visibleSource.modifier).cardClicks({
                    if (card.target != null) actions.details(heroCta(card.progress, hint).confirmedMissing) else if (selectedHero()) onCard(card)
                }, { if (selectedHero()) onMenu(card) }))
            }
            if (slides.size > 1) PageDots(slides.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun CarouselTile(card: HomeCard, sourceHint: com.strmr.tv.home.SourceHint?, modifier: Modifier) {
    val shown = com.strmr.tv.ui.details.PersonalArtwork.store.card(card)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        ArtImage(shown.backdropUrl ?: shown.artworkUrl, "w1280", card.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val logo = Artwork.logo(card.logoUrl)
            val name = @Composable {
                Text(card.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (logo != null) {
                SubcomposeAsyncImage(
                    logo, card.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                    error = { name() }, modifier = Modifier.size(220.dp, 70.dp),
                )
            } else {
                name()
            }
            if (card.subtitle.isNotEmpty()) {
                Text(card.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
            sourceHint?.let { SourceHintBadge(it) }
            if (heroCta(card.progress, sourceHint).confirmedMissing) {
                Text("Details", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            if (showsProgress(card.progress)) ProgressLine(card.progress, Modifier.padding(top = 4.dp))
        }
    }
}

/** The carousel's page dots under it. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.size(if (i == current) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
