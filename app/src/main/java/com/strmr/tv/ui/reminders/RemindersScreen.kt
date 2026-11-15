package com.strmr.tv.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.LocalNavigator
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext

fun reminderPage(target: DetailsTarget) = com.strmr.tv.ui.Page.Details(target)

@Composable
fun RemindersScreen() {
    val profile by AppSession.profile.collectAsState()
    val context by ProfileDataContext.changes.collectAsState()
    if(!remindersAllowed(profile)) { Text("Reminders are not available for this profile.",modifier=Modifier.padding(36.dp));return }
    val scope=rememberCoroutineScope()
    val controller=remember(context) {ReminderController(scope,expected=context)}
    val state by controller.state.collectAsState()
    val navigator=LocalNavigator.current
    var inbox by remember {mutableStateOf(true)}
    LaunchedEffect(controller) {controller.load()}
    DisposableEffect(controller) {onDispose {controller.close()}}
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal=36.dp),contentPadding=PaddingValues(vertical=36.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={navigator.close()}) {Text("Back")}
                Text("Reminders & Inbox",style=MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick={controller.load()},enabled=!state.loading && !state.busy && !state.closed) {Text("Refresh")}
            }
            Text(ReminderPassiveExplanation)
            Text("Stopping updates keeps previous inbox messages. Dismiss hides a message from the inbox.")
        }
        if(state.closed) item {Text("This profile or server changed. Reopen My Area.")}
        else if(state.loading) item {Text("Loading reminders...")}
        state.error?.let {message -> item {Text(message)}}
        if(state.supported && !state.closed) {
            item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={inbox=true}) {Text(if(inbox) "Inbox selected" else "Inbox")}
                OutlinedButton(onClick={inbox=false}) {Text(if(!inbox) "Reminders selected" else "Reminders")}
                if(inbox) OutlinedButton(onClick={controller.filterUnread(!state.unreadOnly)},enabled=!state.busy && !state.loading) {Text(if(state.unreadOnly) "Show all messages" else "Show unread only")}
            }}
            if(inbox) {
                if(state.events.isEmpty()) item {Text("No inbox updates. Updates appear after a later eligible check in the app.")}
                items(state.events,key={it.id}) {event -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(event.title.name,style=MaterialTheme.typography.titleLarge)
                    Text(reminderDescription(event.eventKind,event.releaseType,event.episode))
                    Text(reminderRecordedLabel(event.occurredAt) ?: "Recorded time unavailable")
                    Text(if(event.readAt==null) "Unread" else "Read")
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick={controller.openEvent(event)?.let {navigator.open(reminderPage(it))}},enabled=!state.busy) {Text("Open title")}
                        OutlinedButton(onClick={controller.update(event,read=event.readAt==null)},enabled=!state.busy) {Text(if(event.readAt==null) "Mark read" else "Mark unread")}
                        OutlinedButton(onClick={controller.update(event,dismissed=true)},enabled=!state.busy) {Text("Dismiss")}
                    }
                }}
                if(state.nextCursor!=null) item {OutlinedButton(onClick=controller::more,enabled=!state.busy) {Text("Load more messages")}}
            } else {
                if(state.reminders.isEmpty()) item {Text("No active reminders. Open a title and choose Remind me.")}
                items(state.reminders,key={it.id}) {reminder -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(reminder.title.name,style=MaterialTheme.typography.titleLarge)
                    Text(reminderDescription(reminder.eventKind,reminder.releaseType,reminder.episode))
                    reminderDeviceLabel(reminder)?.let {Text(it)}
                    Text(if(reminder.state=="triggered") "Update received" else "Waiting for a later app check")
                    OutlinedButton(onClick={controller.openReminder(reminder)?.let {navigator.open(reminderPage(it))}},enabled=!state.busy) {Text("Open title")}
                    OutlinedButton(onClick={controller.revoke(reminder)},enabled=!state.busy) {Text("Stop updates")}
                }}
            }
        }
    }
}
