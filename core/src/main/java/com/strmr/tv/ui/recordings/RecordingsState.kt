package com.strmr.tv.ui.recordings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.AppLog
import com.strmr.tv.live.Recording
import com.strmr.tv.live.RecordingPlanItem
import com.strmr.tv.live.recordingPlan
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.optionalProfileRead
import com.strmr.tv.live.RecordingFilter
import com.strmr.tv.live.RecordingRules
import com.strmr.tv.live.SeriesRule
import com.strmr.tv.live.cancelRecording
import com.strmr.tv.live.deleteRecording
import com.strmr.tv.live.deleteRecordingRule
import com.strmr.tv.live.recordingRules
import com.strmr.tv.live.recordings
import com.strmr.tv.ui.AppToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.live.RecordingPart
import com.strmr.tv.live.RecordingCaptureRules
import com.strmr.tv.player.PlaybackCoordinator

/**
 * The Recordings page's data (Expo `NewRecordingsScreen` on TV): the profile's recordings in
 * the server's order (newest start first), the filter, the selected recording, and cancel /
 * delete with a toast and a reload, as Expo does. A failed refresh keeps the last list.
 */
class RecordingsState(
    private val scope: CoroutineScope,
    val context:Long=ProfileDataContext.current,
    private val access:RecordingsAccess=ApiRecordingsAccess,
    private val log:(Boolean,String)->Unit={warning,text->if(warning)AppLog.w("Recordings",text)else AppLog.i("Recordings",text)},
    private val notify:(String)->Unit={AppToast.show(it)},
    private val playback:(Recording,RecordingPart?,Long)->Unit={r,part,expected->if(part==null)PlaybackCoordinator.playRecording(r,expected)else PlaybackCoordinator.playRecordingPart(r,part,expected)},
) {
    private var open=true
    private var sequence=0L
    private var generation by mutableLongStateOf(0L)
    val isCurrent get()=open && context==ProfileDataContext.current
    private fun check() {ProfileDataContext.requireCurrent(context);if(!open)throw CancellationException("Recordings page closed")}
    fun dispose() {open=false;sequence++;generation++;recordings=emptyList();plans=emptyMap();rules=emptyList()}
    class PartAction internal constructor(val recordingId:String,val part:RecordingPart,internal val context:Long,internal val generation:Long)
    fun partAction(r:Recording,part:RecordingPart):PartAction? = if(isCurrent && recordings.any {it==r} && selected?.id==r.id && RecordingCaptureRules.playable(r,part))PartAction(r.id,part,context,generation)else null
    fun playPart(action:PartAction) {
        if(!isCurrent || busyId!=null || action.context!=context || action.generation!=generation || selected?.id!=action.recordingId)return
        val r=recordings.firstOrNull {it.id==action.recordingId} ?: return
        if(!RecordingCaptureRules.playable(r,action.part))return
        playback(r,action.part,context)
    }
    fun play(r:Recording) {if(isCurrent && busyId==null && recordings.any {it==r} && RecordingRules.canPlay(r))playback(r,null,context)}
    var recordings by mutableStateOf<List<Recording>>(emptyList())
        private set
    /** The profile's recording rules, so a rule's airing offers to cancel the whole series. */
    var rules by mutableStateOf<List<SeriesRule>>(emptyList())
        private set
    var plans by mutableStateOf<Map<String, RecordingPlanItem>>(emptyMap())
        private set
    private var plansContext: Long? = null
    fun planFor(id: String): RecordingPlanItem? = if (plansContext == ProfileDataContext.current) plans[id] else null

    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busyId by mutableStateOf<String?>(null)
        private set
    var filter by mutableStateOf(RecordingFilter.ALL)
    var selectedId by mutableStateOf<String?>(null)
    /** Bumped after a delete: the deleted row had focus, so the page puts it on the new selection. */
    var deletions by mutableIntStateOf(0)
        private set

    val visible: List<Recording> get() = recordings.filter { filter.matches(it) }
    val selected: Recording? get() = visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()

    fun count(f: RecordingFilter) = recordings.count { f.matches(it) }

    /** `quiet`: a poll or a reload after an action, without the Refresh button's spinner. */
    suspend fun load(quiet: Boolean = false) {
        check()
        val request=++sequence
        if (plansContext != context) plans = emptyMap()
        if (!quiet) refreshing = true
        try {
            val loaded = access.recordings(context)
            currentCoroutineContext().ensureActive();check();if(request!=sequence)return
            recordings=loaded;generation++;plans=emptyMap();plansContext=context
            val loadedRules=optionalProfileRead {check();access.rules(context)}
            currentCoroutineContext().ensureActive();check();if(request!=sequence)return
            loadedRules?.let {rules=it}
            val forecast=optionalProfileRead {check();access.plan(context)}
            currentCoroutineContext().ensureActive();check();if(request!=sequence)return
            plans = forecast?.recordings.orEmpty().mapNotNull { item -> item.recordingId?.let { it to item } }.toMap()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(true,"Recordings refresh failed")
            if (isCurrent && request==sequence) error = "Recordings could not be refreshed. Try again."
        } finally {
            if (isCurrent && request==sequence) { loading = false; refreshing = false }
        }
    }

    fun refresh() = scope.launch { load() }

    /** Cancels a scheduled recording or stops one in progress. */
    fun cancel(r: Recording) = act(r, "cancel", "Cancelled", "Failed to cancel recording.") { access.cancel(r.id,context) }

    /** The rule that scheduled a recording still to come or in progress (else null). */
    fun ruleFor(r: Recording): SeriesRule? =
        r.ruleId?.takeIf { RecordingRules.canCancel(r) }?.let { id -> rules.firstOrNull { it.id == id } }

    /** Deletes the recording's rule; the server cancels the airings it scheduled. */
    fun cancelSeries(r: Recording) {
        val rule = ruleFor(r) ?: return
        act(r, "cancel series", "Cancelled the series", "Failed to cancel the series recording.") { access.deleteRule(rule.id,context) }
    }

    /** The deleted row's neighbour (the next, else the previous) becomes the selection, so its actions aren't a different recording's by surprise. */
    fun delete(r: Recording,onDeleted:()->Unit={}) = act(r, "delete", "Deleted", "Failed to delete recording.",onSuccess=onDeleted) {
        access.delete(r.id,context)
        check()
        val list = visible
        val i = list.indexOfFirst { it.id == r.id }
        selectedId = (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.id
        recordings = recordings.filter { it.id != r.id }
        deletions++
    }

    private fun act(r: Recording, verb: String, done: String, failed: String,onSuccess:()->Unit={}, call: suspend () -> Unit) {
        if (!isCurrent || busyId != null || recordings.none {it==r}) return
        busyId = r.id
        scope.launch {
            try {
                check();call();currentCoroutineContext().ensureActive();check()
                log(false,"$verb ${r.id} (${r.status})")
                notify("$done ${RecordingRules.title(r)}.")
                load(quiet = true)
                check();onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if(isCurrent) {log(true,"$verb failed");notify(failed)}
            } finally {
                if(isCurrent)busyId = null
            }
        }
    }
}
