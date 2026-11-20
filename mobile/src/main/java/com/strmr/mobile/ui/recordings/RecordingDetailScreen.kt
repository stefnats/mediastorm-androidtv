package com.strmr.mobile.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.RecordingCaptureRules
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.mobile.ui.shell.PageScaffold
import com.strmr.tv.live.Recording
import com.strmr.tv.live.RecordingRules
import com.strmr.tv.player.RecordingPartCursorStore
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.ui.recordings.RecordingsState
import kotlinx.coroutines.delay

/**
 * One recording's page: status, title, channel, times, duration and size, description, error,
 * then its actions (Play / Play In Progress, Stop / Cancel, Cancel Series, Delete), each but
 * Play confirmed with the TV wording. Refreshes every 10 s while it is scheduled or recording.
 */
@Composable
fun RecordingDetailScreen(id: String, currentRoute: () -> Boolean = { true }) {
    val scope = rememberCoroutineScope()
    val context by ProfileDataContext.changes.collectAsState()
    val state = remember(id,context) { RecordingsState(scope, context, pageCurrent = currentRoute).also {it.selectedId=id} }
    val activePage = currentRoute()
    LaunchedEffect(state, activePage) { if (!activePage) { state.retireJoined(); state.retireTiming() } }
    DisposableEffect(state) {onDispose {state.dispose()}}
    val navigator = LocalNavigator.current
    val confirmations = remember(state) { RecordingConfirmations(state).also { it.onDeleted = { navigator.pop() } } }
    val sessionsEnded by PlaybackCoordinator.sessionsEnded.collectAsState()
    val r = state.recordings.firstOrNull { it.id == id }

    LaunchedEffect(state,sessionsEnded) {
        while (true) {
            state.load(quiet = true)
            delay(10_000)
        }
    }

    PageScaffold(r?.let { RecordingRules.title(it) } ?: "Recording", showBack = true) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                r != null -> Detail(r, state, confirmations)
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Text(state.error ?: "This recording is no longer on the server.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.align(Alignment.Center).padding(32.dp))
            }
        }
    }
    RecordingConfirmationDialog(confirmations)
}

@Composable
private fun Detail(r: Recording, state: RecordingsState, confirmations: RecordingConfirmations) {
    val busy = state.busyId == r.id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(statusIcon(r.status), contentDescription = null, tint = statusColor(r.status), modifier = Modifier.size(18.dp))
            Text(RecordingCaptureRules.status(r).uppercase(), color = statusColor(r.status), style = MaterialTheme.typography.labelLarge,
                 fontWeight = FontWeight.Bold)
        }
        Text(RecordingRules.title(r), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(r.channelName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Fact("Starts", RecordingRules.dateTime(r.startAt))
        Fact("Ends", RecordingRules.dateTime(r.endAt))
        RecordingRules.duration(r.startAt, r.endAt).takeIf { it.isNotEmpty() }?.let { Fact(if(r.capture==null)"Duration" else "Scheduled duration", it) }
        RecordingRules.size(RecordingCaptureRules.dataBytes(r))?.let { Fact(RecordingCaptureRules.dataLabel(r), it) }
        if (!r.ruleId.isNullOrEmpty()) Fact("Scheduled by", "A recurring rule")
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(r.description?.ifBlank { null } ?: "No program description available.", style = MaterialTheme.typography.bodyMedium)
        if (RecordingRules.canCancel(r)) Text(state.planFor(r.id)?.summary ?: "Recording capacity forecast unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RecordingCaptureRules.summary(r)?.let {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        RecordingRules.visibleError(r.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.size(12.dp))
        RecordingActions.of(r, state.ruleFor(r)).forEach { a ->
            val content: @Composable () -> Unit = {
                Icon(actionIcon(a.kind), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(a.label)
            }
            if (a.kind == RecordingActionKind.PLAY) {
                Button(onClick = { confirmations.run(r, a.kind) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { content() }
            } else {
                OutlinedButton(
                    onClick = { confirmations.run(r, a.kind) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    colors = if (a.kind == RecordingActionKind.DELETE) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.outlinedButtonColors(),
                ) { content() }
            }
        }
        state.joined(r)?.let { joined ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Joined recording copy", style = MaterialTheme.typography.titleMedium)
            joined.label(r)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (joined.busy) Text("Joined-copy action in progress…")
            OutlinedButton(onClick = { joined.read(r, false) }, enabled = !busy && !joined.busy) { Text("Read saved joined copy") }
            OutlinedButton(onClick = { joined.read(r, true) }, enabled = !busy && !joined.busy) { Text("Prepare joined copy") }
            joined.progressMessage?.let { Text(it) }
            OutlinedButton(onClick = { joined.readProgress(r) }, enabled = !busy && !joined.busy && joined.ready(r)) { Text("Read current progress") }
            if (joined.canResume(r)) OutlinedButton(onClick = { joined.playWithProgress(r, true) }, enabled = !busy && !joined.busy) { Text("Resume joined copy") }
            if (joined.progressAvailable(r)) OutlinedButton(onClick = { joined.playWithProgress(r, false) }, enabled = !busy && !joined.busy) { Text("From start") }
        }
        RecordingCaptureRules.known(r)?.let {capture ->
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            Text("Captured parts",style=MaterialTheme.typography.titleMedium)
            if(capture.parts.isEmpty())Text("No captured parts yet.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${capture.gaps.size} recorded capture gaps · ${capture.sourceChanges ?: 0} source changes",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(RecordingCaptureRules.queueParts(r)!=null)Text("Completed parts play in order with a new player for each part. Gaps are not filled.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            RecordingPartCursorStore.positionLabel(r,state.context)?.let {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            capture.gaps.forEach {gap->Text(RecordingCaptureRules.gapLabel(gap),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            capture.parts.forEach {part -> key(part.index) {
                Text("Part ${part.index}",fontWeight=FontWeight.SemiBold)
                Fact(RecordingCaptureRules.partDataLabel(part,r),RecordingCaptureRules.partDataSize(part,r))
                Fact("Capture started",RecordingRules.dateTime(part.startedAt))
                part.endedAt?.let {Fact("Capture ended",RecordingRules.dateTime(it))}
                Text(RecordingCaptureRules.endLabel(part,r),color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(RecordingCaptureRules.closed(part) && !RecordingCaptureRules.playable(r,part))Text("Playback is unavailable for this part.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                val action=state.partAction(r,part)
                OutlinedButton(onClick={action?.let(state::playPart)},enabled=!busy && action!=null,modifier=Modifier.fillMaxWidth()) {Text("Play part ${part.index} from start")}
                val timingAction = state.timingAction(r, part)
                state.timing(part)?.let { value ->
                    Text(value.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    value.observationLabel()?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                state.timingError(part)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.timingBusy == part.index) Text("Reading timing information…")
                if (timingAction != null) {
                    OutlinedButton(onClick = { state.readTiming(timingAction, false) }, enabled = !busy && state.timingBusy == null) { Text("Read saved timing") }
                    OutlinedButton(onClick = { state.readTiming(timingAction, true) }, enabled = !busy && state.timingBusy == null) { Text("Inspect container duration") }
                    Text("An inspection measures this closed part only. Estimates do not combine the parts or gaps.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }}
        }
        if (!RecordingRules.canDelete(r)) {
            Text("This recording belongs to a recurring rule. Pause or delete the rule under Rules & Buffers.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value)
    }
}
