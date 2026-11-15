package com.strmr.mobile.ui.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.shell.PageScaffold
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.mobile.ui.shell.Route
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.reminders.*
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext

fun reminderRoute(target: DetailsTarget) = Route.Details(target,prewarmOnOpen=false)

@Composable
fun RemindersScreen() {
    val profile by AppSession.profile.collectAsState()
    val context by ProfileDataContext.changes.collectAsState()
    if(!remindersAllowed(profile)) {PageScaffold("Reminders",showBack=true) {padding->Text("Reminders are not available for this profile.",Modifier.padding(padding).padding(16.dp))};return}
    val scope=rememberCoroutineScope()
    val navigator=LocalNavigator.current
    val controller=remember(context) {ReminderController(scope,expected=context)}
    val state by controller.state.collectAsState()
    var inbox by remember {mutableStateOf(true)}
    LaunchedEffect(controller) {controller.load()}
    DisposableEffect(controller) {onDispose {controller.close()}}
    PageScaffold("Reminders & Inbox",showBack=true,actions={TextButton(onClick={controller.load()},enabled=!state.loading && !state.busy && !state.closed) {Text("Refresh")}}) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Text(ReminderPassiveExplanation);Text("Stopping updates keeps previous inbox messages. Dismiss hides a message from the inbox.")}
            if(state.closed) item {Text("This profile or server changed. Reopen My Area.")}
            else if(state.loading) item {Text("Loading reminders...")}
            state.error?.let {message -> item {Text(message)}}
            if(state.supported && !state.closed) {
                item {Row {TextButton(onClick={inbox=true}) {Text(if(inbox) "Inbox selected" else "Inbox")};TextButton(onClick={inbox=false}) {Text(if(!inbox) "Reminders selected" else "Reminders")}}}
                if(inbox) {
                    item {TextButton(onClick={controller.filterUnread(!state.unreadOnly)},enabled=!state.busy && !state.loading) {Text(if(state.unreadOnly) "Show all messages" else "Show unread only")}}
                    if(state.events.isEmpty()) item {Text("No inbox updates. Updates appear after a later eligible check in the app.")}
                    items(state.events,key={it.id}) {event -> Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(event.title.name,style=MaterialTheme.typography.titleLarge)
                        Text(reminderDescription(event.eventKind,event.releaseType,event.episode))
                        Text(reminderRecordedLabel(event.occurredAt) ?: "Recorded time unavailable")
                        Text(if(event.readAt==null) "Unread" else "Read")
                        TextButton(onClick={controller.openEvent(event)?.let {navigator.push(reminderRoute(it))}},enabled=!state.busy) {Text("Open title")}
                        Row {TextButton(onClick={controller.update(event,read=event.readAt==null)},enabled=!state.busy) {Text(if(event.readAt==null) "Mark read" else "Mark unread")};TextButton(onClick={controller.update(event,dismissed=true)},enabled=!state.busy) {Text("Dismiss")}}
                    }}
                    if(state.nextCursor!=null) item {TextButton(onClick=controller::more,enabled=!state.busy) {Text("Load more messages")}}
                } else {
                    if(state.reminders.isEmpty()) item {Text("No active reminders. Open a title and choose Remind me.")}
                    items(state.reminders,key={it.id}) {reminder -> Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(reminder.title.name,style=MaterialTheme.typography.titleLarge)
                        Text(reminderDescription(reminder.eventKind,reminder.releaseType,reminder.episode))
                        reminderDeviceLabel(reminder)?.let {Text(it)}
                        Text(if(reminder.state=="triggered") "Update received" else "Waiting for a later app check")
                        TextButton(onClick={controller.openReminder(reminder)?.let {navigator.push(reminderRoute(it))}},enabled=!state.busy) {Text("Open title")}
                        TextButton(onClick={controller.revoke(reminder)},enabled=!state.busy) {Text("Stop updates")}
                    }}
                }
            }
        }
    }
}
