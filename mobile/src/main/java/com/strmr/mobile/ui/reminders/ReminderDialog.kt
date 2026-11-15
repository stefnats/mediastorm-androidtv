package com.strmr.mobile.ui.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.AppToast
import com.strmr.tv.ui.reminders.*
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.mobile.ui.shell.Route
import com.strmr.tv.library.ProfileDataContext

@Composable
fun ReminderDialog(target: ReminderTarget, expectedContext: Long, reloadTitle: suspend () -> Unit, onDismiss: () -> Unit) {
    val profile by AppSession.profile.collectAsState()
    val context by ProfileDataContext.changes.collectAsState()
    if(!remindersAllowed(profile) || context!=expectedContext) return
    val scope=rememberCoroutineScope()
    val navigator=LocalNavigator.current
    val controller=remember(target,expectedContext) { ReminderController(scope,expected=expectedContext) }
    val state by controller.state.collectAsState()
    val choices=remember(target) { reminderChoices(target) }
    LaunchedEffect(controller) { controller.load(withInbox=false) }
    LaunchedEffect(state.closed) { if(state.closed) onDismiss() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    if(state.closed) return
    AlertDialog(
        onDismissRequest=controller::close,
        title={Text("Remind me: ${target.title.name}")},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(ReminderPassiveExplanation)
            Text("Announced dates and new episode metadata do not confirm playback. Source updates describe candidates. Manage or stop updates in My Area.")
            when {
                state.loading -> Text("Loading this profile's reminders...")
                state.busy -> Text("Saving reminder...")
                else -> {
                    state.error?.let {Text(it)}
                    if(state.supported) choices.forEach {choice -> TextButton(onClick={controller.create(choice) {AppToast.show("Reminder saved for this profile.");controller.close()}}) {Text(choice.label)} }
                }
            }
        }},
        confirmButton={if(!state.loading && !state.busy && state.error!=null) when(state.recovery) {
            ReminderRecovery.REFRESH -> TextButton(onClick={controller.load(withInbox=false)}) {Text("Reload")}
            ReminderRecovery.RELOAD_TITLE -> TextButton(onClick={controller.reloadTitle(reloadTitle) {AppToast.show("Title reloaded. Open Remind me again.")}}) {Text("Reload title")}
            ReminderRecovery.MANAGE_REMINDERS -> TextButton(onClick={controller.manageReminders {navigator.push(Route.Reminders)}}) {Text("Manage reminders")}
        }},
        dismissButton={TextButton(onClick=controller::close) {Text("Cancel")}},
    )
}
