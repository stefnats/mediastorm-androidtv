package com.strmr.mobile.ui.recordings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.strmr.tv.live.RecordingPlanChoice
import com.strmr.tv.live.RecordingScheduling

/** Scrollable text and explicit choices on phones, including small displays. */
@Composable
fun RecordingPlanDialog(scheduling: RecordingScheduling) {
    val pending by scheduling.prompt.collectAsState()
    val prompt = pending ?: return
    AlertDialog(
        onDismissRequest = { scheduling.answer(prompt, RecordingPlanChoice.CANCEL) },
        title = { Text("Recording capacity forecast") },
        text = { Text("Recording: ${prompt.title}\n\n${prompt.message}", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            Column {
                TextButton(onClick = { scheduling.answer(prompt, RecordingPlanChoice.RETRY) }) { Text("Check again") }
                if (!prompt.expired) TextButton(onClick = { scheduling.answer(prompt, RecordingPlanChoice.SCHEDULE) }) { Text("Schedule anyway") }
            }
        },
        dismissButton = { TextButton(onClick = { scheduling.answer(prompt, RecordingPlanChoice.CANCEL) }) { Text("Cancel") } },
    )
}
