package com.strmr.tv.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.strmr.tv.live.RecordingPlanChoice
import com.strmr.tv.live.RecordingScheduling
import com.strmr.tv.ui.ChoiceDialog
import com.strmr.tv.ui.DialogChoice

/** Cancel is the first focused choice; Back and held Select cannot schedule. */
@Composable
fun RecordingPlanDialog(scheduling: RecordingScheduling) {
    val pending by scheduling.prompt.collectAsState()
    val prompt = pending ?: return
    key(prompt) {
        ChoiceDialog(
            title = "Recording capacity forecast",
            message = "Recording: ${prompt.title}\n\n${prompt.message}",
            choices = listOf(
                DialogChoice("Cancel") { scheduling.answer(prompt, RecordingPlanChoice.CANCEL) },
                DialogChoice("Check again") { scheduling.answer(prompt, RecordingPlanChoice.RETRY) },
            ) + if (prompt.expired) emptyList() else listOf(
                DialogChoice("Schedule anyway") { scheduling.answer(prompt, RecordingPlanChoice.SCHEDULE) },
            ),
            onDismiss = { scheduling.answer(prompt, RecordingPlanChoice.CANCEL) },
            steady = true,
            dismissOnChoice = false,
        )
    }
}
