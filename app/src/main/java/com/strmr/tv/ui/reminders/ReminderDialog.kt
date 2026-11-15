package com.strmr.tv.ui.reminders

import androidx.compose.runtime.*
import com.strmr.tv.app.AppSession
import com.strmr.tv.ui.AppToast
import com.strmr.tv.ui.ChoiceDialog
import com.strmr.tv.ui.DialogChoice
import com.strmr.tv.ui.LocalNavigator
import com.strmr.tv.ui.Page
import com.strmr.tv.library.ProfileDataContext

@Composable
fun ReminderDialog(target: ReminderTarget, expectedContext: Long, reloadTitle: suspend () -> Unit, onDismiss: () -> Unit) {
    val profile by AppSession.profile.collectAsState()
    val context by ProfileDataContext.changes.collectAsState()
    if (!remindersAllowed(profile) || context!=expectedContext) return
    val scope = rememberCoroutineScope()
    val navigator=LocalNavigator.current
    val controller = remember(target,expectedContext) { ReminderController(scope,expected=expectedContext) }
    val state by controller.state.collectAsState()
    val choices = remember(target) { reminderChoices(target) }
    LaunchedEffect(controller) { controller.load(withInbox=false) }
    LaunchedEffect(state.closed) { if(state.closed) onDismiss() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    if(state.closed) return
    ChoiceDialog(
        title="Remind me: ${target.title.name}",
        message=when {
            state.loading -> "Loading this profile's reminders..."
            state.busy -> "Saving reminder..."
            else -> state.error ?: "$ReminderPassiveExplanation Announced dates and new episode metadata are separate from source candidates. Manage or stop updates in My Area."
        },
        choices=buildList {
            add(DialogChoice("Cancel",controller::close))
            if(!state.loading && !state.busy && state.supported) choices.forEach { choice ->
                add(DialogChoice(choice.label) { controller.create(choice) { AppToast.show("Reminder saved for this profile."); controller.close() } })
            }
            if(!state.loading && !state.busy && state.error!=null) when(state.recovery) {
                ReminderRecovery.REFRESH -> add(DialogChoice("Reload") {controller.load(withInbox=false)})
                ReminderRecovery.RELOAD_TITLE -> add(DialogChoice("Reload title") {controller.reloadTitle(reloadTitle) {AppToast.show("Title reloaded. Open Remind me again.")}})
                ReminderRecovery.MANAGE_REMINDERS -> add(DialogChoice("Manage reminders") {controller.manageReminders {navigator.open(Page.Reminders)}})
            }
        },
        onDismiss=controller::close,steady=true,dismissOnChoice=false,
    )
}
