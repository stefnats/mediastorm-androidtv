package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class RecordingPlanChoice { CANCEL, RETRY, SCHEDULE }

class RecordingPlanPrompt internal constructor(
    val title: String,
    val forecast: RecordingPlanItem?,
    val expired: Boolean,
    internal val context: Long,
    internal val expiresAt: Long,
) {
    internal val answer = CompletableDeferred<RecordingPlanChoice>()
    val message: String get() = (forecast?.summary ?: "The recording capacity forecast is unavailable.") + "\n\n" +
        "This checks scheduled recordings, including padding. Live viewers, external apps and future rule matches may change demand. No connection is reserved. Recordings wait when capacity is full and do not stop viewers." +
        if (expired) "\n\nThis forecast expired. Check again before scheduling." else ""
}

/** One explicit scheduling intent, owned by its Live screen's coroutine scope. */
class RecordingScheduling internal constructor(
    private val scope: CoroutineScope,
    private val contexts: StateFlow<Long>,
    private val preview: suspend (RecordingDraft) -> RecordingPlan,
    private val create: suspend (RecordingDraft) -> Unit,
    private val notify: (String) -> Unit,
    private val onCreated: suspend () -> Unit,
    private val clock: () -> Long,
) {
    constructor(scope: CoroutineScope, notify: (String) -> Unit, onCreated: suspend () -> Unit) : this(
        scope, ProfileDataContext.changes, { ApiClient.previewRecording(it) }, { ApiClient.createRecording(it) },
        notify, onCreated, { System.nanoTime() / 1_000_000 },
    )
    private val pending = MutableStateFlow<RecordingPlanPrompt?>(null)
    val prompt: StateFlow<RecordingPlanPrompt?> = pending
    private var job: Job? = null
    private var generation = 0L

    fun schedule(draft: RecordingDraft) {
        if (job?.isActive == true || contexts.value != draft.context) return
        val attempt = ++generation
        job = scope.launch {
            val owner = currentCoroutineContext().job
            val changed = launch {
                contexts.first { it != draft.context }
                owner.cancel(CancellationException("Recording context changed"))
            }
            fun requireCurrent() {
                if (contexts.value != draft.context || generation != attempt) throw CancellationException("Recording selection changed")
            }
            try {
                while (true) {
                    requireCurrent()
                    val plan = try {
                        // A transport/server failure can be overridden only by a
                        // deliberate UI choice. Authentication and input refusals cannot.
                        preview(draft)
                    } catch (e: CancellationException) { throw e
                    } catch (e: ApiError) {
                        requireCurrent()
                        if (e is ApiError.SignedOut || e.statusCode?.let { it in 400..499 } == true) {
                            notify("Recording preview was rejected. Check the server version, profile, channel and times.")
                            return@launch
                        }
                        null
                    } catch (_: Exception) { null }
                    requireCurrent()
                    if (plan?.candidate?.isClear != true) {
                        var request = RecordingPlanPrompt(draft.title, plan?.candidate, false, draft.context, clock() + 15_000)
                        pending.value = request
                        var choice = withTimeoutOrNull(15_000) { request.answer.await() }
                        requireCurrent()
                        if (choice == null || choice == RecordingPlanChoice.SCHEDULE && clock() >= request.expiresAt) {
                            request = RecordingPlanPrompt(draft.title, plan?.candidate, true, draft.context, clock())
                            pending.value = request
                            choice = request.answer.await()
                            requireCurrent()
                        }
                        if (pending.value === request) pending.value = null
                        when (choice) {
                            RecordingPlanChoice.CANCEL -> return@launch
                            RecordingPlanChoice.RETRY -> continue
                            RecordingPlanChoice.SCHEDULE -> Unit
                            null -> return@launch
                        }
                    }
                    requireCurrent()
                    create(draft)
                    requireCurrent()
                    notify("Recording scheduled for ${draft.title}.")
                    onCreated()
                    return@launch
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (contexts.value == draft.context) notify("The recording could not be scheduled. Refresh the list before trying again.")
            } finally {
                changed.cancel()
                if (generation == attempt) pending.value = null
            }
        }
    }

    fun answer(request: RecordingPlanPrompt, choice: RecordingPlanChoice) {
        if (pending.value !== request || contexts.value != request.context) return
        if (choice == RecordingPlanChoice.SCHEDULE && (request.expired || clock() >= request.expiresAt)) return
        request.answer.complete(choice)
    }

    /** Screen disposal or a changed UI selection cancels even a pending request. */
    fun dismiss() {
        generation++
        pending.value = null
        job?.cancel()
    }
}
