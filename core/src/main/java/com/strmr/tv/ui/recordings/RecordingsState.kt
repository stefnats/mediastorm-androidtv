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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import com.strmr.tv.live.RecordingPartTiming
import com.strmr.tv.live.recordingPartTiming
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
    private val playQueue:(Recording,Long)->Unit={r,expected->PlaybackCoordinator.playRecordingParts(r,expected)},
    private val timingRead:suspend (String,Int,Long)->RecordingPartTiming={id,index,expected->ApiClient.recordingPartTiming(id,index,false,expected)},
    private val timingInspect:suspend (String,Int,Long)->RecordingPartTiming={id,index,expected->ApiClient.recordingPartTiming(id,index,true,expected)},
    private val pageCurrent:()->Boolean={true},
    private val playback:(Recording,RecordingPart?,Long)->Unit={r,part,expected->if(part==null)PlaybackCoordinator.playRecording(r,expected)else PlaybackCoordinator.playRecordingPart(r,part,expected)},
) {
    private var joinedTools: RecordingDerivedState? = null
    fun joined(recording: Recording): RecordingDerivedState? {
        if (!isCurrent || selected !== recording || !pageCurrent()) return null
        val tools = joinedTools ?: RecordingDerivedState(scope, context, { selected },
            { ApiClient.inContext(context) { ApiClient.profileId } },
            { com.strmr.tv.app.AppSession.profile.value?.isKidsProfile == false },
            { isCurrent && pageCurrent() }).also { joinedTools = it }
        return tools.takeIf { it.eligible(recording) }
    }
    fun retireJoined() { joinedTools?.retire() }
    private var open=true
    private var sequence=0L
    private var generation by mutableLongStateOf(0L)
    val isCurrent get()=open && context==ProfileDataContext.current && scope.coroutineContext[Job]?.isActive!=false
    private fun check() {ProfileDataContext.requireCurrent(context);if(!open)throw CancellationException("Recordings page closed")}
    fun dispose() {joinedTools?.dispose();retireTiming();open=false;sequence++;generation++;recordings=emptyList();plans=emptyMap();rules=emptyList()}
    class PartAction internal constructor(val recordingId:String,val part:RecordingPart,internal val context:Long,internal val generation:Long)
    fun partAction(r:Recording,part:RecordingPart):PartAction? = if(isCurrent && recordings.any {it==r} && selected?.id==r.id && RecordingCaptureRules.playable(r,part))PartAction(r.id,part,context,generation)else null
    fun playPart(action:PartAction) {
        if(!isCurrent || busyId!=null || action.context!=context || action.generation!=generation || selected?.id!=action.recordingId)return
        val r=recordings.firstOrNull {it.id==action.recordingId} ?: return
        if(!RecordingCaptureRules.playable(r,action.part))return
        playback(r,action.part,context)
    }
    fun play(r:Recording) {
        if(!isCurrent || busyId!=null || recordings.none {it==r} || !RecordingRules.canPlay(r))return
        if(RecordingCaptureRules.queueParts(r)!=null)playQueue(r,context)else playback(r,null,context)
    }
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
    private var filterState by mutableStateOf(RecordingFilter.ALL)
    var filter: RecordingFilter
        get() = filterState
        set(value) { if (value != filterState) { retireJoined(); retireTiming(); filterState = value } }
    private var selectedState by mutableStateOf<String?>(null)
    var selectedId: String?
        get() = selectedState
        set(value) { if (value != selectedState) { retireJoined(); retireTiming(); selectedState = value } }
    /** Bumped after a delete: the deleted row had focus, so the page puts it on the new selection. */
    var deletions by mutableIntStateOf(0)
        private set

    val visible: List<Recording> get() = recordings.filter { filter.matches(it) }
    val selected: Recording? get() = visible.firstOrNull { it.id == selectedId } ?: visible.firstOrNull()

    fun count(f: RecordingFilter) = recordings.count { f.matches(it) }

    /** `quiet`: a poll or a reload after an action, without the Refresh button's spinner. */
    suspend fun load(quiet: Boolean = false) {
        check()
        retireTiming()
        val request=++sequence
        listReading = true
        if (plansContext != context) plans = emptyMap()
        if (!quiet) refreshing = true
        try {
            val loaded = access.recordings(context)
            currentCoroutineContext().ensureActive();check();if(request!=sequence)return
            recordings=loaded;joinedTools?.observeCurrent(selected);generation++;plans=emptyMap();plansContext=context
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
            if (isCurrent && request==sequence) { loading = false; refreshing = false; listReading = false }
        }
    }

    fun refresh() = scope.launch { retireJoined(); load() }

    /** Each button carries its exact original state, recording/part instances and list generation. */
    class TimingAction internal constructor(
        internal val state: RecordingsState,
        internal val recording: Recording,
        internal val part: RecordingPart,
        internal val context: Long,
        internal val generation: Long,
        internal val lifetime: Long,
    )
    private var listReading by mutableStateOf(false)
    private var timingLifetime = 0L
    private var timingGeneration = 0L
    private var timingJob: Job? = null
    private var timingBusyState by mutableStateOf<Int?>(null)
    private var timingValues by mutableStateOf<Map<Int, RecordingPartTiming>>(emptyMap())
    private var timingErrors by mutableStateOf<Map<Int, String>>(emptyMap())
    val timingBusy get() = if (isCurrent && pageCurrent()) timingBusyState else null
    fun timing(part: RecordingPart) = if (isCurrent && pageCurrent() && selected?.capture?.parts?.any { it === part } == true) timingValues[part.index] else null
    fun timingError(part: RecordingPart) = if (isCurrent && pageCurrent() && selected?.capture?.parts?.any { it === part } == true) timingErrors[part.index] else null
    fun timingAction(recording: Recording, part: RecordingPart): TimingAction? =
        if (isCurrent && pageCurrent() && !listReading && selected === recording && recordings.any { it === recording } &&
            recording.status in setOf("completed", "failed", "cancelled") &&
            recording.capture?.parts?.any { it === part } == true && RecordingCaptureRules.playable(recording, part))
            TimingAction(this, recording, part, context, generation, timingLifetime) else null
    private fun timingCurrent(action: TimingAction) = isCurrent && pageCurrent() && !listReading && action.state === this &&
        action.context == context && action.generation == generation && action.lifetime == timingLifetime && selected === action.recording &&
        recordings.any { it === action.recording } && action.recording.capture?.parts?.any { it === action.part } == true
    /** UI retirement cancels the actual registered child, independently of other producer siblings. */
    fun retireTiming() {
        timingLifetime++
        timingGeneration++
        timingJob?.cancel(); timingJob = null
        timingBusyState = null; timingValues = emptyMap(); timingErrors = emptyMap()
    }
    fun readTiming(action: TimingAction, inspect: Boolean) {
        if (!timingCurrent(action) || busyId != null || timingBusyState != null) return
        val intent = ++timingGeneration
        timingBusyState = action.part.index
        timingErrors = timingErrors - action.part.index
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun checkTiming() {
                check()
                if (!timingCurrent(action) || intent != timingGeneration) throw CancellationException("Recording timing selection changed")
            }
            try {
                currentCoroutineContext().ensureActive(); checkTiming()
                val value = if (inspect) timingInspect(action.recording.id, action.part.index, context)
                    else timingRead(action.recording.id, action.part.index, context)
                currentCoroutineContext().ensureActive(); checkTiming()
                require(value.recordingId == action.recording.id && value.partIndex == action.part.index)
                timingValues = timingValues + (action.part.index to value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (timingCurrent(action) && intent == timingGeneration) {
                    timingValues = timingValues - action.part.index
                    timingErrors = timingErrors + (action.part.index to "Timing information is unavailable. Choose an action again to retry.")
                }
            } finally {
                if (timingCurrent(action) && intent == timingGeneration) { timingBusyState = null; timingJob = null }
            }
        }
        timingJob = job
        job.start()
    }

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
        retireJoined()
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
