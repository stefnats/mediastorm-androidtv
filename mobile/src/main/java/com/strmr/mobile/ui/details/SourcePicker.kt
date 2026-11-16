package com.strmr.mobile.ui.details

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.strmr.mobile.ui.components.cardClicks
import com.strmr.mobile.ui.library.LibraryVersionItem
import kotlinx.coroutines.flow.first
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Episode
import com.strmr.tv.api.SourceHealth
import com.strmr.tv.api.SourceResult
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.player.ManualSourceTracks
import com.strmr.tv.ui.details.SourceTracks

/**
 * Choose Source as a full-screen page over the details page: the movie's or episode's library
 * copies first, then the server's indexer and scraper results with their debrid cache / usenet
 * health (pending checks long-polled until they settle). Long press on a result opens its
 * Audio & Subtitles ([SourceTracks]); the tracks chosen there also apply when the result is
 * tapped directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePicker(title:DetailsTarget,episode:Episode?,libraryVersions:List<LibraryVersion>,onPick:(SourceResult,ManualSourceTracks?)->Unit,onPickLibrary:(LibraryVersion)->Unit,onDismiss:()->Unit) {
    val scope=rememberCoroutineScope();val expected=remember {com.strmr.tv.library.ProfileDataContext.current}
    val target=com.strmr.tv.sources.SourceSearchTarget.from(title,episode)
    val state=remember(expected,target,title.name,title.year,title.imdbId) {com.strmr.tv.sources.SourceSearchState(scope,title,episode,expected)}
    val tracks=remember(state,state.sourceGeneration) {SourceTracks(scope,expected,onProbeChanged=state::probeChanged,onProbeError=state::probeFailed,denialTitleId=title.titleId)}
    var manualIntent by remember(state,state.sourceGeneration) {mutableStateOf<com.strmr.tv.sources.SourceSearchState.ManualSelection?>(null)}
    val closeTracks={tracks.close();state.endManual();manualIntent=null}
    LaunchedEffect(state.requiresReload) {if(state.requiresReload)closeTracks()}
    androidx.compose.runtime.DisposableEffect(state) {onDispose {state.dispose()}}
    androidx.compose.runtime.DisposableEffect(tracks) {onDispose {tracks.dispose()}}
    LaunchedEffect(state) {state.reload()}
    LaunchedEffect(expected) {com.strmr.tv.library.ProfileDataContext.changes.first {it!=expected};onDismiss()}
    val rows=state.rows;val actions=rows.associate {it.result to state.selection(it)}
    LaunchedEffect(tracks,rows,state.viewId) {rows.forEach {tracks.reconcile(it.result,it.expectedFileVersion,state.viewId)}}
    val pick={result:SourceResult,manual:ManualSourceTracks? -> actions[result]?.let {selection->state.use(selection){row->onDismiss();onPick(row.result,manual)}};Unit}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Scaffold(topBar={TopAppBar(title={Text(episode?.let {"${title.name} · ${it.code}"} ?: title.name,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onDismiss){Icon(Icons.Filled.Close,contentDescription="Close")}})}) {padding ->
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(top=padding.calculateTopPadding(),bottom=padding.calculateBottomPadding()+24.dp)) {
                if(state.supported)item("filters") {androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {SourceFilterControls(state,onApply={closeTracks();state.apply()},onReload={closeTracks();state.reload()})}}
                if(libraryVersions.isNotEmpty()) {
                    section("In Your Libraries")
                    if(state.supported)item("library-filter-scope") {Text("Library copies are manual choices and are not filtered.",Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(libraryVersions,key={"library:${it.id}"}) {version->LibraryVersionItem(version){state.manualLibrary {onDismiss();onPickLibrary(version)}}}
                }
                section("Streams")
                when {
                    state.loading->item("loading") {Row(Modifier.padding(24.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text(if(state.supported)"Applying filters…" else "Searching sources…")}}
                    state.error!=null->item("error") {androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)){Text(state.error!!,color=MaterialTheme.colorScheme.error);if(state.vodDenied==null)androidx.compose.material3.OutlinedButton(onClick={closeTracks();state.retry()}){Text(if(state.requiresReload)"Reload source list" else "Retry")}}}
                    rows.isEmpty()->item("empty") {Text(if(state.supported && state.applied.active)"No matches in these retained search results." else "No sources found",Modifier.padding(24.dp))}
                    else->items(rows,key={"${state.sourceGeneration}:${it.key}"}) {row ->
                        val action=actions[row.result]
                        SourceRow(row.result,state.healthFor(row),tracks.hasChoice(row.result),
                            com.strmr.tv.ui.rememberMediaFacts(expected,evidence=(if(state.metadataChanged)row.result.mediaEvidence else row.mediaEvidence ?: row.result.mediaEvidence),target=title,episode=episode,originCandidateVersion=row.candidateVersion),
                            enabled=action!=null,filterLabel=com.strmr.tv.sources.SourceFilterMenus.rowLabel(row,state),
                            onLongClick={action?.let {state.use(it){current->state.beginManual(current)?.let {intent->manualIntent=intent;tracks.reconcile(current.result,current.expectedFileVersion,state.viewId);tracks.open(current.result)}}}}){action?.let {state.use(it){current->tracks.reconcile(current.result,current.expectedFileVersion,state.viewId);pick(current.result,tracks.manualFor(current.result))}}}
                    }
                }
                state.pageError?.let {message->item("page-error") {androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)){Text(message);if(state.vodDenied==null)androidx.compose.material3.OutlinedButton(onClick={closeTracks();state.retry()}){Text(if(state.expiredNow)"Apply filters again" else "Retry page")}}}}
                if(state.hasMore)item("more") {androidx.compose.material3.OutlinedButton(onClick=state::loadMore,enabled=!state.loadingMore,modifier=Modifier.padding(16.dp)){Text(if(state.loadingMore)"Loading…" else "Load more")}}
            }
        }
        tracks.openResult?.let {result ->
            val snapshot=tracks.probeState(result);val intent=manualIntent
            TracksSheet(release=tracks.release(result),state=snapshot,defaults=tracks.trackDefaults,choice=tracks.choice(result),
                mediaFacts=com.strmr.tv.ui.rememberMediaFacts(expected,evidence=(snapshot.probe as? com.strmr.tv.api.TrackProbe.Tracks)?.mediaEvidence,target=title,episode=episode,
                    originCandidateVersion=rows.firstOrNull {it.result===result}?.candidateVersion ?: result.mediaEvidence?.candidateVersion),
                onChoice={if(intent?.let {it.result===result && state.acceptsManual(it)}==true)tracks.choose(result,it,snapshot)},
                onRetry={if(intent?.let {it.result===result && state.acceptsManual(it)}==true)tracks.retry(result,snapshot)},
                onPlay={if(intent?.let {it.result===result && state.acceptsManual(it)}==true)tracks.play(result,snapshot){source,manual->
                    intent?.takeIf {it.result===source}?.let {state.useManual(it){owned->onDismiss();onPick(owned,manual)}}
                }},onDismiss=closeTracks)
        }
    }
}

private fun LazyListScope.section(title: String) = item(key = "section-$title") {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceRow(result: SourceResult, health: SourceHealth?, tracksChosen: Boolean, facts: com.strmr.tv.ui.MediaFactsPresentation,enabled:Boolean=true,filterLabel:String?=null, onLongClick: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val attrs = result.attributes
    val badges = SourceRowText.badges(attrs)
    val size = result.sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
    val details = SourceRowText.details(attrs - setOf("audio","channels","languages"), size, result.serviceType, result.indexer, tracksChosen)
    val claims = facts.compactReleaseClaims ?: (badges + listOfNotNull(attrs["audio"],attrs["channels"],attrs["languages"]))
        .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "Release claims (unverified): $it" }
    ListItem(
        overlineContent = claims?.let { { Text(it, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        headlineContent = { Text(SourceRowText.release(result.title, attrs), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { androidx.compose.foundation.layout.Column {
            filterLabel?.let {Text(it)}
            Text(details.joinToString("  ·  "), maxLines = 2, overflow = TextOverflow.Ellipsis)
            facts.compactFileMetadata?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            facts.compactQualification?.let { Text(it) }
        } },
        leadingContent = { HealthBadge(health) },
        modifier = if(enabled)Modifier.cardClicks(onClick,onLongClick)else Modifier,
    )
}

/** Debrid cache / Usenet health status, independent of media tags or playback success. */
@Composable
private fun HealthBadge(health: SourceHealth?) {
    when (SourceRowText.health(health?.state)) {
        SourceRowText.Health.PENDING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        SourceRowText.Health.GOOD -> Icon(Icons.Filled.CheckCircle, contentDescription = if (health?.state == "cached") "Cached" else "Healthy",
                                          tint = Color(0xFF30D158))
        SourceRowText.Health.BAD -> Icon(Icons.Filled.Cancel, contentDescription = if (health?.state == "not_cached") "Not Cached" else "Unhealthy",
                                         tint = Color(0xFFFF453A))
        SourceRowText.Health.NONE -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.Transparent)
    }
}
