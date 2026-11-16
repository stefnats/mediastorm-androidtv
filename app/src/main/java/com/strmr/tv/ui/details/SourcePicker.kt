package com.strmr.tv.ui.details

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import com.strmr.tv.ui.MarqueeText
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.first
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Episode
import com.strmr.tv.api.SourceHealth
import com.strmr.tv.api.SourceResult
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.player.ManualSourceTracks
import com.strmr.tv.ui.Accent
import com.strmr.tv.ui.BrandBackground
import com.strmr.tv.ui.library.LibraryVersionItem

/**
 * Full-screen list of the backend's indexer / scraper results for one movie or episode, with
 * debrid cache / usenet health badges (pending ones long-polled until they settle). Long press
 * on a result opens its track panel ([SourceTracks]); the tracks chosen there are remembered
 * for this visit and also apply when the result is selected directly. The movie's or episode's
 * library copies are listed first, as the Expo app does.
 */
@Composable
fun SourcePicker(
    title:DetailsTarget,episode:Episode?,onPick:(SourceResult,ManualSourceTracks?)->Unit,onDismiss:()->Unit,
    libraryVersions:List<LibraryVersion> = emptyList(),onPickLibrary:(LibraryVersion)->Unit={},
) {
    val expected=remember {com.strmr.tv.library.ProfileDataContext.current}
    val scope=androidx.compose.runtime.rememberCoroutineScope()
    val target=com.strmr.tv.sources.SourceSearchTarget.from(title,episode)
    val state=remember(expected,target,title.name,title.year,title.imdbId) {com.strmr.tv.sources.SourceSearchState(scope,title,episode,expected)}
    val tracks=rememberSourceTracks(expected,state to state.sourceGeneration,state::probeChanged,state::probeFailed,denialTitleId=title.titleId)
    var manualIntent by remember(state,state.sourceGeneration) {mutableStateOf<com.strmr.tv.sources.SourceSearchState.ManualSelection?>(null)}
    val closeTracks={tracks.close();state.endManual();manualIntent=null}
    LaunchedEffect(state.requiresReload) {if(state.requiresReload)closeTracks()}
    androidx.compose.runtime.DisposableEffect(state) {onDispose {state.dispose()}}
    androidx.compose.runtime.DisposableEffect(tracks) {onDispose {tracks.dispose()}}
    LaunchedEffect(state) {state.reload()}
    LaunchedEffect(expected) {com.strmr.tv.library.ProfileDataContext.changes.first {it!=expected};onDismiss()}
    val rows=state.rows
    val actions=rows.associate {it.result to state.selection(it)}
    LaunchedEffect(tracks,rows,state.viewId) {rows.forEach {tracks.reconcile(it.result,it.expectedFileVersion,state.viewId)}}
    val pick={result:SourceResult,manual:ManualSourceTracks? -> actions[result]?.let {selection -> state.use(selection) {row ->onDismiss();onPick(row.result,manual)}};Unit}
    val heading=episode?.let {"${title.name} · ${it.code}"} ?: title.name
    val first=remember {FocusRequester()};val firstCopy=remember {FocusRequester()}
    var focusPlaced by remember(state) {mutableStateOf(false)}
    LaunchedEffect(libraryVersions.isNotEmpty(),rows.isNotEmpty()) {
        if(focusPlaced)return@LaunchedEffect
        val focus=if(libraryVersions.isNotEmpty())firstCopy else if(rows.isNotEmpty())first else return@LaunchedEffect
        withFrameNanos {};focusPlaced=runCatching {focus.requestFocus()}.isSuccess
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(BrandBackground),contentAlignment=Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max=760.dp),contentPadding=PaddingValues(vertical=36.dp,horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                item("heading") {Text(heading,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=12.dp))}
                if(state.supported)item("filters") {SourceFilterControls(state,onApply={closeTracks();state.apply()},onReload={closeTracks();state.reload()})}
                if(libraryVersions.isNotEmpty()) {
                    item("libraries") {Column {Text("In your libraries",color=Color.White.copy(alpha=0.6f),fontWeight=FontWeight.SemiBold);if(state.supported)Text("Library copies are manual choices and are not filtered.",color=Color.White.copy(alpha=0.6f))}}
                    items(libraryVersions,key={"library:${it.id}"}) {version ->LibraryVersionItem(version,if(version==libraryVersions.first())Modifier.focusRequester(firstCopy)else Modifier) {state.manualLibrary {onDismiss();onPickLibrary(version)}}}
                    item("streams") {Text("Streams",color=Color.White.copy(alpha=0.6f),fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=18.dp))}
                }
                when {
                    state.loading->item("loading") {Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {CircularProgressIndicator(Modifier.size(20.dp),color=Accent,strokeWidth=2.dp);Text(if(state.supported)"Applying filters…" else "Searching sources…")}}
                    state.error!=null->item("error") {Column {Text(state.error!!);if(state.vodDenied==null)androidx.tv.material3.OutlinedButton(onClick={closeTracks();state.retry()}) {Text(if(state.requiresReload)"Reload source list" else "Retry")}}}
                    rows.isEmpty()->item("empty") {Text(if(state.supported && state.applied.active)"No matches in these retained search results." else "No sources found")}
                    else->items(rows,key={"${state.sourceGeneration}:${it.key}"}) {row ->
                        val action=actions[row.result]
                        SourceRow(row.result,state.healthFor(row),tracks.hasChoice(row.result),
                            com.strmr.tv.ui.rememberMediaFacts(expected,evidence=(if(state.metadataChanged)row.result.mediaEvidence else row.mediaEvidence ?: row.result.mediaEvidence),target=title,episode=episode,originCandidateVersion=row.candidateVersion),
                            if(row===rows.first())Modifier.focusRequester(first)else Modifier,
                            enabled=action!=null,filterLabel=com.strmr.tv.sources.SourceFilterMenus.rowLabel(row,state),
                            onLongClick={action?.let {state.use(it){current->state.beginManual(current)?.let {intent->manualIntent=intent;tracks.reconcile(current.result,current.expectedFileVersion,state.viewId);tracks.open(current.result)}}}}) {action?.let {state.use(it){current->tracks.reconcile(current.result,current.expectedFileVersion,state.viewId);pick(current.result,tracks.manualFor(current.result))}}}
                    }
                }
                state.pageError?.let {message->item("page-error") {Column {Text(message);if(state.vodDenied==null)androidx.tv.material3.OutlinedButton(onClick={closeTracks();state.retry()}) {Text(if(state.expiredNow)"Apply filters again" else "Retry page")}}}}
                if(state.hasMore)item("more") {androidx.tv.material3.OutlinedButton(onClick=state::loadMore,enabled=!state.loadingMore) {Text(if(state.loadingMore)"Loading…" else "Load more")}}
            }
        }
        val intent=manualIntent
        tracks.Panel(title,episode,expected,actionCurrent={result->intent?.let {it.result===result && state.acceptsManual(it)}==true},
            onPanelDismiss={state.endManual();manualIntent=null},originCandidateVersion=intent?.let {owned->rows.firstOrNull {it.result===owned.result}?.candidateVersion},onPlay={result,manual->intent?.takeIf {it.result===result}?.let {state.useManual(it){owned->onDismiss();onPick(owned,manual)}}})
    }
}

@Composable
private fun SourceRow(result: SourceResult, health: SourceHealth?, tracksChosen: Boolean, facts: com.strmr.tv.ui.MediaFactsPresentation, modifier: Modifier,enabled:Boolean=true,filterLabel:String?=null, onLongClick: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val attrs = result.attributes
    // Release name: the first line of the scraper's raw title when present.
    val release = (attrs["raw_title"] ?: result.title).lineSequence().firstOrNull() ?: result.title
    val badges = buildList {
        attrs["resolution"]?.takeIf { it.isNotEmpty() }?.let { add(if (it == "2160p") "4K" else it) }
        if (attrs["hasDV"] == "true") add("DV")
        attrs["hdr"]?.let { if (it.contains("HDR10+")) add("HDR10+") else if (it.contains("HDR")) add("HDR") }
        attrs["quality"]?.takeIf { it.isNotEmpty() }?.let { add(it) }
        attrs["codec"]?.takeIf { it.isNotEmpty() }?.let { add(it.uppercase()) }
    }
    val details = buildList {
        if (result.sizeBytes > 0) add(Formatter.formatShortFileSize(context, result.sizeBytes))
        add(if (result.serviceType == "usenet") "Usenet" else attrs["tracker"] ?: result.indexer)
        attrs["seeders"]?.takeIf { it.isNotEmpty() && result.serviceType != "usenet" }?.let { add("$it seeders") }
        if (tracksChosen) add("Tracks chosen")
    }.filter { it.isNotBlank() }
    val claims = facts.compactReleaseClaims ?: (badges + listOfNotNull(attrs["audio"],attrs["channels"],attrs["languages"]))
        .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "Release claims (unverified): $it" }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    ListItem(
        selected = false,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        overlineContent = claims?.let { { Text(it, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        headlineContent = { MarqueeText(release, active = focused) },
        supportingContent = { Column {
            filterLabel?.let {Text(it)}
            Text(details.joinToString("  ·  "), maxLines = 1, overflow = TextOverflow.Ellipsis)
            facts.compactFileMetadata?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            facts.compactQualification?.let { Text(it) }
        } },
        leadingContent = health?.let { { HealthBadge(it) } },
        modifier = modifier,
        interactionSource = interaction,
    )
}

/** Debrid cache / Usenet health status, independent of media tags or playback success. */
@Composable
private fun HealthBadge(health: SourceHealth) {
    when (health.state) {
        "pending" -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        "healthy", "cached" -> Icon(Icons.Filled.CheckCircle, contentDescription = if (health.state == "cached") "Cached" else "Healthy", tint = Color(0xFF30D158))
        "unhealthy", "not_cached" -> Icon(Icons.Filled.Clear, contentDescription = if (health.state == "not_cached") "Not Cached" else "Unhealthy", tint = Color(0xFFFF453A))
        else -> Box(Modifier.size(24.dp))
    }
}
