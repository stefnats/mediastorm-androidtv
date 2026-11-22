package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.VodAccessDenial
import com.strmr.tv.api.vodDenial
import com.strmr.tv.api.VodDenialEvents
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.ReleaseReportOptions
import com.strmr.tv.api.ReleaseReportReply
import com.strmr.tv.api.SpokenAudioInput
import com.strmr.tv.api.SpokenAudioReply
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.api.cancelReleaseAlternative
import com.strmr.tv.api.prequeueStatus
import com.strmr.tv.api.releaseReportOptions
import com.strmr.tv.api.submitReleaseAction
import com.strmr.tv.api.undoReleaseReport
import com.strmr.tv.app.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All report I/O is replaceable by an isolated fixture in tests. */
interface ReleaseReportGateway {
    fun isCurrent(): Boolean
    suspend fun options(id: String): ReleaseReportOptions
    suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply
    suspend fun status(id: String): PrequeueStatus
    suspend fun cancel(id: String)
    suspend fun undo(id: String)
    suspend fun checkAudioLanguage(id: String, input: SpokenAudioInput): SpokenAudioReply = error("Spoken audio checking is unavailable")
}

/** Transport boundary used by the actual gateway and deferred, isolated tests. */
internal interface ReleaseReportRequests {
    suspend fun options(id: String, expected: Long): ReleaseReportOptions
    suspend fun submit(id: String, reporting: Boolean, body: JsonObject, expected: Long): ReleaseReportReply
    suspend fun status(id: String, expected: Long): PrequeueStatus
    suspend fun cancel(id: String, expected: Long)
    suspend fun undo(id: String, expected: Long)
    suspend fun checkAudioLanguage(id: String, input: SpokenAudioInput, expected: Long): SpokenAudioReply = error("Spoken audio checking is unavailable")
}

private object ApiReleaseReportRequests : ReleaseReportRequests {
    override suspend fun options(id: String, expected: Long) = ApiClient.releaseReportOptions(id, expected)
    override suspend fun submit(id: String, reporting: Boolean, body: JsonObject, expected: Long) = ApiClient.submitReleaseAction(id, reporting, body, expected)
    override suspend fun status(id: String, expected: Long) = ApiClient.prequeueStatus(id, expected)
    override suspend fun cancel(id: String, expected: Long) = ApiClient.cancelReleaseAlternative(id, expected)
    override suspend fun undo(id: String, expected: Long) = ApiClient.undoReleaseReport(id, expected)
    override suspend fun checkAudioLanguage(id: String, input: SpokenAudioInput, expected: Long) = ApiClient.checkSpokenAudioLanguage(id, input, expected)
}

/** An original playback owner survives every await; cleanup never acquires replacement credentials. */
class ServerReleaseReportGateway internal constructor(
    private val owner: PlaybackSessionContext,
    private val requests: ReleaseReportRequests,
) : ReleaseReportGateway {
    constructor(expected: Long = ProfileDataContext.current) : this(PlaybackSessionContext.capture(expected), ApiReleaseReportRequests)
    override fun isCurrent() = owner.isCurrent()
    override suspend fun options(id: String): ReleaseReportOptions {
        owner.requireCurrent()
        val value = requests.options(id, owner.epoch)
        owner.requireCurrent()
        return value
    }
    override suspend fun submit(id: String, reporting: Boolean, body: JsonObject): ReleaseReportReply {
        owner.requireCurrent()
        val value = requests.submit(id, reporting, body, owner.epoch)
        owner.requireCurrent()
        return value
    }
    override suspend fun status(id: String): PrequeueStatus {
        owner.requireCurrent()
        val value = requests.status(id, owner.epoch)
        owner.requireCurrent()
        return value
    }
    override suspend fun cancel(id: String) {
        if (!isCurrent()) return
        requests.cancel(id, owner.epoch)
        owner.requireCurrent()
    }
    override suspend fun checkAudioLanguage(id: String, input: SpokenAudioInput): SpokenAudioReply {
        owner.requireCurrent()
        val reply = requests.checkAudioLanguage(id, input, owner.epoch)
        owner.requireCurrent()
        return reply
    }
    override suspend fun undo(id: String) {
        owner.requireCurrent()
        requests.undo(id, owner.epoch)
        owner.requireCurrent()
    }
}

enum class ReleaseResume(val label: String) {
    BEGINNING("From the beginning"), CURRENT("At the current position"), REWIND("10 seconds back");
    fun position(currentMs: Long, wrongContent: Boolean, durationSeconds: Double? = null): Long {
        if (wrongContent || this == BEGINNING) return 0
        val offset = (currentMs.coerceAtLeast(0) - if (this == REWIND) 10_000 else 0).coerceAtLeast(0)
        return if (durationSeconds != null && durationSeconds > 0 && offset / 1000.0 >= durationSeconds) 0 else offset
    }
}

enum class ReleaseProblem(val key: String, val label: String) {
    WRONG_TITLE("wrong_title", "Wrong movie or series"), WRONG_EPISODE("wrong_episode", "Wrong episode"),
    LANGUAGE("missing_language", "Wanted audio language missing or mislabeled"), AUDIO("audio_problem", "Audio track is defective"),
    FILE("file_problem", "File is damaged or incomplete"), QUALITY("quality_problem", "Quality is unsuitable for me"),
    DEVICE("device_problem", "Does not play on this device"), PROVIDER("source_unavailable", "Source is temporarily unavailable");
    val wrongContent get() = this == WRONG_TITLE || this == WRONG_EPISODE
    fun scope(options: ReleaseReportOptions): String {
        val owner = if (options.canShare) "All profiles" else "This profile"
        return when (this) {
            WRONG_TITLE, WRONG_EPISODE, FILE -> "$owner, this title or exact episode only. Other package episodes remain available."
            LANGUAGE -> "$owner, ${languageLabel(options.audioLanguage)} audio requests only. Other languages remain available."
            AUDIO -> "$owner, the selected audio track only. Healthy tracks remain available."
            QUALITY -> "This profile only. Other profiles remain unaffected."
            DEVICE -> "This profile on this device only."
            PROVIDER -> "This source route for this profile, for 15 minutes."
        }
    }
}

fun languageLabel(code: String): String = if (code.isBlank()) "Unknown language" else
    runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault()).ifBlank { code } }.getOrDefault(code)

/** Local preparation DATA; only the concrete player owner may adopt its actual source. */
interface PreparedReleaseBuffer {
    val targetMs: Long
    fun isReady(): Boolean
    suspend fun closeAndJoin()
}

data class ReleaseReportState(
    val options: ReleaseReportOptions? = null,
    val problem: ReleaseProblem? = null,
    val track: Int? = null,
    val resume: ReleaseResume = ReleaseResume.REWIND,
    val busy: Boolean = false,
    val ready: PrequeueStatus? = null,
    val reportId: String? = null,
    val message: String? = null,
    val denial:VodAccessDenial?=null,
    val resetHistory: Boolean = false,
    val historyReset: Boolean = false,
    val historyResetUnconfirmed: Boolean = false,
    val progressStopped: Boolean = false,
    val audioLanguageCheckBusy: Boolean = false,
    val audioLanguageCheckMessage: String? = null,
    val audioLanguageCheckResult: SpokenAudioReply? = null,
)

/** Prepares one replacement while playback continues; adoption is always a separate user action. */
class ReleaseReports(
    private val sourceId: String,
    private val gateway: ReleaseReportGateway,
    private val scope: CoroutineScope,
    private val position: () -> Long,
    private val adopt: (PrequeueStatus, Long, Boolean) -> Unit,
    private val beforeOptions: suspend () -> Unit = {},
    private val clientId: String = Config.clientId,
    private val pollDelayMs: Long = 500,
    private val pollAttempts: Int = 120,
    private val denialTitleId:String?=null,
    private val denialContext:Long=ProfileDataContext.current,
    private val prepareLocal: (suspend (PrequeueStatus, Long, Long) -> PreparedReleaseBuffer)? = null,
    private val adoptLocal: ((PrequeueStatus, Long, Boolean, PreparedReleaseBuffer) -> Unit)? = null,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val beforeHistoryReset: (suspend () -> Unit)? = null,
    progressAlreadyDiscarded: Boolean = false,
    private val currentAudioTrack: ((ReleaseReportOptions) -> Int?)? = null,
) {
    private val mutable = MutableStateFlow(ReleaseReportState(progressStopped = progressAlreadyDiscarded))
    val state: StateFlow<ReleaseReportState> = mutable
    private var job: Job? = null
    private var pending: String? = null
    private var closed = false
    private var loadStarted = false
    private var localJob: Job? = null
    private var local: PreparedReleaseBuffer? = null
    val bufferedPreparation: PreparedReleaseBuffer? get() = local
    private var serverReady: PrequeueStatus? = null
    private var localDeadline: Long? = null
    private var rangeAttempt = 0L
    private var spokenJob: Job? = null
    private var spokenSequence = 0L
    private fun checkCurrent() { check(!closed && gateway.isCurrent()) { "Playback context changed. Reopen this menu." } }

    suspend fun load() {
        if (loadStarted || closed) return
        loadStarted = true
        mutable.value = mutable.value.copy(busy = true, message = null)
        val attempt=denialTitleId?.let {VodDenialEvents.begin(it,denialContext)}
        try {
            beforeOptions(); checkCurrent()
            val options = gateway.options(sourceId); checkCurrent()
            mutable.value = mutable.value.copy(options = options, busy = false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if(!closed && gateway.isCurrent() && e is ApiError.Http && attempt!=null)e.vodDenial?.let {VodDenialEvents.reject(attempt,it)}
            mutable.value = mutable.value.copy(busy=false,options=null,ready=null,denial=e.vodDenial,message=e.vodDenial?.message ?: "Could not load the selected release. This action needs a server with release reporting.") }
    }

    val canCheckAudioLanguage: Boolean get() = !closed && pending == null &&
        mutable.value.options?.audioLanguageCheckAvailable == true && currentAudioTrack != null

    /** Explicit observation only: never enters submit/report/replacement/history paths. */
    fun checkAudioLanguage(): Job? {
        if (!canCheckAudioLanguage || mutable.value.busy || mutable.value.denial != null) return null
        val original = mutable.value.options ?: return null
        val attempt = ++spokenSequence
        mutable.value = mutable.value.copy(busy = true, audioLanguageCheckBusy = true,
            audioLanguageCheckMessage = null, audioLanguageCheckResult = null, message = "Checking two short audio samples; nothing will be changed.")
        spokenJob = scope.launch {
            val authorityAttempt = denialTitleId?.let { VodDenialEvents.begin(it, denialContext) }
            try {
                beforeOptions()
                currentCoroutineContext().ensureActive(); checkCurrent()
                check(attempt == spokenSequence)
                val refreshed = gateway.options(sourceId)
                currentCoroutineContext().ensureActive(); checkCurrent()
                check(attempt == spokenSequence && refreshed.sourceVersion == original.sourceVersion &&
                    TrackPreferences.sameLanguage(refreshed.audioLanguage, original.audioLanguage))
                check(refreshed.audioLanguageCheckAvailable)
                val track = currentAudioTrack?.invoke(refreshed) ?: error("Current audio track cannot be mapped safely")
                val input = SpokenAudioInput(refreshed.sourceVersion, track, position().coerceAtLeast(0) / 1000.0)
                val reply = withTimeout(100_000) { gateway.checkAudioLanguage(sourceId, input) }
                currentCoroutineContext().ensureActive(); checkCurrent()
                check(attempt == spokenSequence && currentAudioTrack?.invoke(refreshed) == track)
                check(reply.sourceVersion == input.sourceVersion && reply.audioTrackIndex == track &&
                    TrackPreferences.sameLanguage(reply.requestedLanguage, refreshed.audioLanguage))
                mutable.value = mutable.value.copy(busy = false, audioLanguageCheckBusy = false, message = null,
                    audioLanguageCheckMessage = spokenAudioMessage(reply, refreshed.audioLanguage), audioLanguageCheckResult = reply)
            } catch (timeout: TimeoutCancellationException) {
                if (!closed && attempt == spokenSequence && gateway.isCurrent()) mutable.value = mutable.value.copy(
                    busy = false, audioLanguageCheckBusy = false, message = null,
                    audioLanguageCheckMessage = "Spoken language is unknown because the check timed out. Nothing was changed.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed && attempt == spokenSequence && gateway.isCurrent()) {
                    val denial = error.vodDenial
                    if (denial != null && authorityAttempt != null) VodDenialEvents.reject(authorityAttempt, denial)
                    mutable.value = mutable.value.copy(busy = false, audioLanguageCheckBusy = false, message = null,
                        denial = denial ?: mutable.value.denial,
                        options = if (denial != null) null else mutable.value.options,
                        audioLanguageCheckMessage = denial?.message ?: "Could not check the current audio safely. Reopen audio controls; no report or source change was made.")
                }
            }
        }
        return spokenJob
    }

    internal suspend fun stopAudioLanguageCheck() {
        val original = spokenJob
        cancelAudioLanguageCheck()
        original?.cancelAndJoin()
    }

    /** Track/intent changes retire even an A/B/A selection's delayed reply. */
    fun cancelAudioLanguageCheck() {
        spokenSequence++
        spokenJob?.cancel(); spokenJob = null
        if (mutable.value.audioLanguageCheckBusy) mutable.value = mutable.value.copy(
            busy = false, audioLanguageCheckBusy = false, message = null, audioLanguageCheckMessage = null, audioLanguageCheckResult = null)
        else if (mutable.value.audioLanguageCheckMessage != null) mutable.value = mutable.value.copy(audioLanguageCheckMessage = null, audioLanguageCheckResult = null)
    }

    fun choose(problem: ReleaseProblem?) {
        if (!mutable.value.busy && pending == null) mutable.value = mutable.value.copy(problem = problem, track = null, resetHistory = false, message = null)
    }
    val canResetHistory: Boolean get() = beforeHistoryReset != null &&
        mutable.value.options?.canResetHistory == true && mutable.value.problem?.wrongContent == true

    fun resetHistory(enabled: Boolean) {
        if (!closed && !mutable.value.busy && pending == null) {
            mutable.value = mutable.value.copy(resetHistory = enabled && canResetHistory)
        }
    }

    private fun playbackNotice(message: String): String = message + if (mutable.value.progressStopped) {
        (if (mutable.value.historyReset && !message.contains("history", ignoreCase = true) && !message.contains("unwatched"))
            " The history reset remains saved." else "") + " The old source remains paused without saving progress."
    } else " Playback can continue."

    fun track(index: Int) {
        if (!mutable.value.busy && mutable.value.options?.audioTracks?.any { it.index == index } == true) {
            mutable.value = mutable.value.copy(track = index)
        }
    }
    fun resume(mode: ReleaseResume) {
        if (closed || mode == mutable.value.resume) return
        mutable.value = mutable.value.copy(resume = mode)
        serverReady?.takeIf { prepareLocal != null }?.let(::startLocal)
    }

    private fun startLocal(status: PrequeueStatus) {
        val prepare = prepareLocal ?: return
        val previous = localJob
        previous?.cancel()
        val attempt = ++rangeAttempt
        val target = mutable.value.resume.position(position(), mutable.value.problem?.wrongContent == true, status.duration)
        val deadline = localDeadline ?: (monotonicNanos() + 15_000_000_000L).also { localDeadline = it }
        mutable.value = mutable.value.copy(ready = null, busy = true, message = playbackNotice("Buffering the alternative at ${target / 1000}s."))
        localJob = scope.launch {
            var owned: PreparedReleaseBuffer? = null
            try {
                previous?.cancelAndJoin()
                local?.closeAndJoin(); local = null
                checkCurrent(); check(monotonicNanos() < deadline) { "Alternative buffering timed out. Playback continues." }
                owned = prepare(status, target, deadline)
                checkCurrent(); check(attempt == rangeAttempt && owned.targetMs == target && owned.isReady())
                local = owned; owned = null
                mutable.value = mutable.value.copy(ready = status, busy = false,
                    message = playbackNotice("Alternative buffered at ${target / 1000}s. Switch to this saved position."))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed && attempt == rangeAttempt) mutable.value = mutable.value.copy(ready = null, busy = false,
                    message = playbackNotice("Could not buffer the alternative. Choose another position or source."))
            } finally { withContext(NonCancellable) { owned?.closeAndJoin() } }
        }
    }

    fun submit(reporting: Boolean, prepare: Boolean) {
        if (mutable.value.busy || pending != null || closed || mutable.value.denial!=null) return
        val options = mutable.value.options ?: return
        val problem = mutable.value.problem
        if (reporting && (problem == null || (problem == ReleaseProblem.AUDIO && mutable.value.track == null))) return
        val resetHistory = reporting && problem?.wrongContent == true && canResetHistory && mutable.value.resetHistory
        val body = buildJsonObject {
            put("sourceVersion", options.sourceVersion)
            if (reporting) { put("reason", problem!!.key); put("audioLanguage", options.audioLanguage) }
            if (reporting && problem == ReleaseProblem.AUDIO) put("audioTrackIndex", mutable.value.track!!)
            if (resetHistory) put("resetHistory", true)
            put("retry", prepare); put("startOffset", 0); put("skipHLS", true); put("clientId", clientId)
        }
        mutable.value = mutable.value.copy(busy = true, message = if (resetHistory) "Finishing earlier progress updates..." else if (prepare) playbackNotice("Preparing a suitable alternative.") else "Saving report...")
        job = scope.launch {
            var attempt=denialTitleId?.let {VodDenialEvents.begin(it,denialContext)}
            var submitted = false
            try {
                checkCurrent()
                if (resetHistory) {
                    mutable.value = mutable.value.copy(progressStopped = true)
                    requireNotNull(beforeHistoryReset).invoke()
                    checkCurrent()
                    mutable.value = mutable.value.copy(historyResetUnconfirmed = true)
                }
                submitted = true
                // Obtain the replacement ID even if Back is pressed while POST is in flight,
                // so cancellation can release the preparation instead of leaving an orphan.
                val reply = withContext(NonCancellable) { withTimeout(12_000) { gateway.submit(sourceId, reporting, body) } }
                pending = reply.replacement?.prequeueId
                if (closed || mutable.value.denial!=null || !gateway.isCurrent()) { cleanup(); return@launch }
                mutable.value = mutable.value.copy(reportId = reply.report?.id ?: mutable.value.reportId,
                    historyReset = mutable.value.historyReset || resetHistory && reply.historyReset,
                    historyResetUnconfirmed = if (resetHistory) !reply.historyReset else mutable.value.historyResetUnconfirmed)
                if (reply.historyResetError != null || resetHistory && !reply.historyReset) {
                    cleanup()
                    mutable.value = mutable.value.copy(busy = false, ready = null,
                        message = playbackNotice("Report saved, but the history reset could not be confirmed. Check history and try the reset again."))
                    return@launch
                }
                if (!prepare) {
                    mutable.value = mutable.value.copy(busy = false, message = playbackNotice(if (resetHistory)
                        "Report saved. This item is now unwatched and its saved position is cleared." else "Report saved."))
                    return@launch
                }
                val id = pending ?: error("No alternative preparation was returned.")
                val ready = withTimeout(90_000) {
                    repeat(pollAttempts) {
                        checkCurrent()
                        attempt=denialTitleId?.let {VodDenialEvents.begin(it,denialContext)}
                        val status = gateway.status(id)
                        checkCurrent()
                        when (status.status) {
                            "ready" -> {
                                check(!status.streamPath.isNullOrBlank()) { "Prepared source has no stream." }
                                return@withTimeout status
                            }
                            "failed", "expired" -> error("No suitable alternative was found. Your current source is unchanged.")
                        }
                        delay(pollDelayMs)
                    }
                    error("The alternative took too long. Try another source manually.")
                }
                serverReady = ready
                if (prepareLocal != null) startLocal(ready)
                else mutable.value = mutable.value.copy(busy = false, ready = ready, message = playbackNotice("Alternative ready. Choose where to start, then switch."))
            } catch (e: TimeoutCancellationException) {
                cleanup()
                mutable.value = mutable.value.copy(busy = false, message = if (mutable.value.progressStopped) playbackNotice(if (mutable.value.historyReset) "Preparation timed out. The history reset remains saved." else "Could not confirm the report or history reset. Check history.") else "Preparation timed out. The current source is unchanged.")
            } catch (e: CancellationException) { cleanup(); throw e }
            catch (e: Exception) {
                if(!closed && gateway.isCurrent() && e is ApiError.Http)attempt?.let {captured->e.vodDenial?.let {VodDenialEvents.reject(captured,it)}}
                cleanup()
                mutable.value = mutable.value.copy(busy = false,options=if(e.vodDenial!=null)null else mutable.value.options,ready=null,denial=e.vodDenial,message = if (resetHistory && !submitted) playbackNotice("Could not finish earlier progress updates. No report or history reset was sent. Try again.")
                    else if (mutable.value.progressStopped) playbackNotice(e.vodDenial?.message ?: if (mutable.value.historyReset) "Could not prepare another source. The history reset remains saved." else "Could not confirm the report or history reset. Check history.")
                    else e.vodDenial?.message ?: if (e is ApiError) "The server rejected this action. Reopen the menu to refresh the selection and language." else e.message ?: "Could not prepare another source.")
            }
        }
    }

    fun take() {
        val state = mutable.value
        val ready = state.ready ?: return
        if (closed || state.denial!=null || !gateway.isCurrent()) {
            mutable.value = state.copy(message = "Playback context changed. Reopen this menu.")
            scope.launch { cleanup() }
            return
        }
        val wrong = state.problem?.wrongContent == true
        val buffered = local
        if (prepareLocal != null && (buffered == null || !buffered.isReady())) {
            mutable.value = state.copy(ready = null, message = playbackNotice("The buffered alternative is no longer ready."))
            return
        }
        val offset = buffered?.targetMs ?: state.resume.position(position(), wrong, ready.duration)
        if (buffered != null) {
            try { requireNotNull(adoptLocal)(ready, offset, wrong, buffered) }
            catch (error: Exception) {
                mutable.value = state.copy(ready = null, message = playbackNotice("Could not switch."))
                return
            }
            local = null
        }
        pending = null // ownership is transferred to playback before the menu is disposed
        closed = true
        if (buffered == null) adopt(ready, offset, wrong)
    }

    fun undo() {
        val id = mutable.value.reportId ?: return
        if (mutable.value.busy || closed || mutable.value.denial!=null) return
        mutable.value = mutable.value.copy(busy = true)
        job = scope.launch {
            try {
                checkCurrent(); gateway.undo(id); checkCurrent()
                cleanup()
                mutable.value = mutable.value.copy(reportId = null, ready = null, busy = false,
                    message = when {
                        mutable.value.historyReset -> playbackNotice("Report removed. The history reset is not undone.")
                        mutable.value.historyResetUnconfirmed -> playbackNotice("Report removed. Any history correction is not undone. Check history.")
                        else -> "Report removed."
                    })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, message = "Could not remove the report.") }
        }
    }

    private suspend fun cleanup() {
        serverReady = null
        withContext(NonCancellable) { local?.closeAndJoin(); local = null }
        val id = pending ?: return
        pending = null
        withContext(NonCancellable) { withTimeoutOrNull(5_000) { runCatching { gateway.cancel(id) } } }
    }
    fun close() {
        if (closed) return
        closed = true
        spokenSequence++; spokenJob?.cancel(); spokenJob = null
        job?.cancel()
        val bufferedWork = localJob
        bufferedWork?.cancel()
        scope.launch { withContext(NonCancellable) { bufferedWork?.join(); cleanup() } }
    }
}
