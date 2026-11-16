package com.strmr.mobile.ui.downloads

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.strmr.mobile.downloads.OfflineAccess
import com.strmr.mobile.downloads.OfflineContext
import com.strmr.tv.app.AppSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.strmr.mobile.downloads.DownloadItem
import com.strmr.mobile.downloads.DownloadPlayback
import com.strmr.mobile.downloads.Downloads
import com.strmr.tv.api.StartPoint
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import com.strmr.tv.ui.AppToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Downloads prompts (the iPhone app's `MobileDownloadsPrompts`): Delete (one download, a
 * show's or a selection) and, before a finished download plays, Resume / Start Over when a
 * resume point is known (the server's, else the one kept on the device).
 */
class DownloadPrompts(private val scope: CoroutineScope) {
    data class Deletion(val ids: List<String>, val title: String,val owner:OfflineContext)
    data class Resume(val item: DownloadItem, val start: StartPoint,val context:Long=ProfileDataContext.current)

    var deletion by mutableStateOf<Deletion?>(null)
    var resume by mutableStateOf<Resume?>(null)
    /** The download whose resume point is being looked up (its row shows a spinner). */
    var checkingResume by mutableStateOf<String?>(null)

    fun play(item: DownloadItem) {
        if (checkingResume != null || !OfflineAccess.allows(item)) return
        checkingResume = item.id
        val expected=ProfileDataContext.current
        scope.launch {
            try {
                val start = DownloadPlayback.resumePoint(item,expected)
                ProfileDataContext.requireCurrent(expected)
                if (start != null) resume = Resume(item, start,expected) else start(item, null,expected)
            } catch(error:CancellationException) {throw error
            } catch(error:Exception) {AppToast.show(error.vodDenial?.message ?: "This download could not be opened.")
            } finally {checkingResume=null}
        }
    }

    fun start(item: DownloadItem, at: StartPoint?,expectedContext:Long=ProfileDataContext.current) {
        if(expectedContext!=ProfileDataContext.current)return
        DownloadPlayback.play(item, at)?.let(AppToast::show)
    }

    fun confirmDelete(item: DownloadItem) {
        val context=OfflineAccess.current() ?: return
        if(!OfflineAccess.allows(item,context))return
        deletion = Deletion(listOf(item.id), if (item.isActive) "Cancel this download?" else "Delete “${item.displayTitle}”?",context)
    }

    fun confirmDelete(ids: List<String>) {
        val context=OfflineAccess.current() ?: return
        if (ids.isEmpty() || ids.any {Downloads.item(it)==null}) return
        deletion = Deletion(ids, if (ids.size == 1) "Delete 1 download?" else "Delete ${ids.size} downloads?",context)
    }
}

@Composable
fun rememberDownloadPrompts(): DownloadPrompts {
    val scope = rememberCoroutineScope()
    val epoch by ProfileDataContext.changes.collectAsState()
    val phase by AppSession.phase.collectAsState()
    val profile by AppSession.profile.collectAsState()
    val pin by AppSession.pinChallenge.collectAsState()
    val prompts=remember(epoch,phase,profile,pin) {DownloadPrompts(scope)}
    return prompts
}

/** The prompts' dialogs; [onDeleted] runs after a confirmed delete (leave selection mode). */
@Composable
fun DownloadPromptDialogs(prompts: DownloadPrompts, onDeleted: () -> Unit = {}) {
    prompts.deletion?.takeIf {it.owner==OfflineAccess.current()}?.let { d ->
        AlertDialog(
            onDismissRequest = { prompts.deletion = null },
            title = { Text(d.title) },
            text = { Text(if (d.ids.size == 1) "The file is removed from this device." else "The files are removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    prompts.deletion = null
                    if(d.owner!=OfflineAccess.current())return@TextButton
                    Downloads.delete(d.ids)
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { prompts.deletion = null }) { Text("Cancel") } },
        )
    }
    prompts.resume?.takeIf {it.context==ProfileDataContext.current && OfflineAccess.allows(it.item)}?.let { r ->
        AlertDialog(
            onDismissRequest = { prompts.resume = null },
            title = { Text(r.item.displayTitle) },
            text = { Text("Resume from ${r.start.label} or start over?") },
            confirmButton = {
                TextButton(onClick = {
                    prompts.resume = null
                    prompts.start(r.item, r.start,r.context)
                }) { Text("Resume") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prompts.resume = null
                    prompts.start(r.item, null,r.context)
                }) { Text("Start Over") }
            },
        )
    }
}

/** A row's tap: play a finished download, pause a running one, resume or retry the rest. */
fun DownloadPrompts.primary(item: DownloadItem) {
    when (item.status) {
        DownloadItem.Status.COMPLETED -> play(item)
        DownloadItem.Status.DOWNLOADING, DownloadItem.Status.PENDING -> Downloads.pause(item.id)
        DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> Downloads.resume(item.id)
    }
}
