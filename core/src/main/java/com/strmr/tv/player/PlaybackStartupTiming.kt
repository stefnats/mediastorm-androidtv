package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local startup timing. Completed samples contain no content or owner identity. */
internal enum class StartupOutcome { RENDERED, FAILED, CANCELLED, RELEASED, OWNER_LOST, TIMEOUT, EXTERNAL_HANDOFF, NOT_STARTED, CHOICE_REQUIRED, SUPERSEDED }
internal enum class StartupInput { UNKNOWN, READY_PREPARATION_SUPPLIED, RESOLVED_INPUT_SUPPLIED, DIRECT_INPUT_SUPPLIED }
internal fun startupInput(prepared: com.strmr.tv.api.PrequeueStatus?, resolved: Boolean): StartupInput = when {
    prepared?.status == "ready" && !prepared.streamPath.isNullOrBlank() -> StartupInput.READY_PREPARATION_SUPPLIED
    resolved -> StartupInput.RESOLVED_INPUT_SUPPLIED
    else -> StartupInput.UNKNOWN
}
internal enum class StartupOrigin { USER_ACTION, COORDINATOR_ONLY }
internal enum class StartupEntry { HERO_PRIMARY, DETAILS_PLAY, MANUAL_SOURCE, LIBRARY_VERSION, STREAM_INSTEAD, PROGRAMMATIC }
internal enum class StartupPreparation { UNKNOWN, RESOLVING, READY, UNAVAILABLE }
internal fun startupPreparation(warm: WarmPrequeue?, titleId: String, context: Long, profile: String): StartupPreparation {
    if (warm == null || warm.titleId != titleId || warm.context != context || warm.profile != profile) return StartupPreparation.UNKNOWN
    return when (warm.phase) {
        WarmPrequeue.Phase.RESOLVING -> StartupPreparation.RESOLVING
        WarmPrequeue.Phase.READY -> StartupPreparation.READY
        WarmPrequeue.Phase.UNAVAILABLE -> StartupPreparation.UNAVAILABLE
    }
}
internal data class StartupSample(val outcome: StartupOutcome, val elapsedMillis: Long, val input: StartupInput)

/** Additional fixed observations wrap the existing sample without changing its legacy contract. */
internal data class StartupRecord(
    val sample: StartupSample,
    val origin: StartupOrigin = StartupOrigin.COORDINATOR_ONLY,
    val entry: StartupEntry = StartupEntry.PROGRAMMATIC,
    val preparation: StartupPreparation = StartupPreparation.UNKNOWN,
) {
    val outcome get() = sample.outcome
    val elapsedMillis get() = sample.elapsedMillis
    val input get() = sample.input
}

/** Process-local measurement receipt, never a playback or access permission. */
class PlaybackStartupIntent internal constructor(
    private val recorder: PlaybackStartupRecorder,
    internal val owner: PlaybackSessionContext,
    private val target: DetailsTarget,
    internal val startedNanos: Long,
    internal val entry: StartupEntry,
    internal val preparation: StartupPreparation,
) {
    internal var terminal = false
    internal var bound = false
    internal var current: () -> Boolean = { true }
    internal var timer: Job? = null
    internal var lifetime: DisposableHandle? = null
    internal fun matches(value: DetailsTarget, context: Long) = target === value && owner.epoch == context
    /** Bind once to the original mounted page, while metadata may still be loading. */
    fun bind(value: DetailsTarget, context: Long, producer: Job? = null, current: () -> Boolean): Boolean = recorder.bind(this, value, context, producer, current)
    /** Only an untransferred receipt is cancelled; expected player navigation keeps its attempt. */
    fun cancel() { recorder.finishPending(this, StartupOutcome.CANCELLED) }
    fun failed() { recorder.finishPending(this, StartupOutcome.FAILED) }
    fun notStarted() { recorder.finishPending(this, StartupOutcome.NOT_STARTED) }
    internal fun finish(outcome: StartupOutcome) = recorder.finishPending(this, outcome)
}

/** Opaque, transient attempt carried by one original session. Never a playback permission. */
class PlaybackStartupAttempt internal constructor(
    private val recorder: PlaybackStartupRecorder,
    internal val owner: PlaybackSessionContext,
    internal val startedNanos: Long,
    internal val input: StartupInput,
    internal val origin: StartupOrigin = StartupOrigin.COORDINATOR_ONLY,
    internal val entry: StartupEntry = StartupEntry.PROGRAMMATIC,
    internal val preparation: StartupPreparation = StartupPreparation.UNKNOWN,
) {
    internal var completed = false
    internal var claimed = false
    internal var timeout: Job? = null
    internal fun finish(outcome: StartupOutcome) = recorder.finish(this, outcome)
    internal fun attach(session: PlayerSession): PlayerSession = if (session.ownership === owner) session.copy(startupTiming = this) else session
    internal fun claim(owner: PlaybackSessionContext?) = recorder.claim(this, owner)
}

/** Production reducer; monotonic clock and optional scheduler are injectable for deterministic tests. */
internal class PlaybackStartupRecorder(
    private val clock: () -> Long = System::nanoTime,
    private val scope: CoroutineScope? = null,
    private val capacity: Int = 128,
    private val timeoutMillis: Long = 120_000,
) {
    init { require(capacity in 1..1024 && timeoutMillis in 1..600_000) }
    private val samples = ArrayDeque<StartupRecord>()
    private var active: PlaybackStartupAttempt? = null
    private var pending: PlaybackStartupIntent? = null
    @Synchronized fun inFlight(): Int = (if (active == null) 0 else 1) + (if (pending == null) 0 else 1)
    @Synchronized fun intent(owner: PlaybackSessionContext, target: DetailsTarget, entry: StartupEntry,
        preparation: StartupPreparation = StartupPreparation.UNKNOWN): PlaybackStartupIntent {
        pending?.let { finishPending(it, StartupOutcome.SUPERSEDED) }
        val value = PlaybackStartupIntent(this, owner, target, clock(), entry, preparation)
        pending = value
        if (!owner.isCurrent()) finishPending(value, StartupOutcome.OWNER_LOST) else armPending(value)
        return value
    }
    @Synchronized fun bind(value: PlaybackStartupIntent, target: DetailsTarget, context: Long, producer: Job?, current: () -> Boolean): Boolean {
        if (pending !== value || value.terminal || value.bound) return false
        if (!value.matches(target, context)) { finishPending(value, StartupOutcome.CANCELLED); return false }
        value.bound = true
        value.current = current
        val lifetime = producer?.invokeOnCompletion { value.cancel() }
        if (value.terminal) lifetime?.dispose() else value.lifetime = lifetime
        return !value.terminal
    }
    private fun pendingElapsed(value: PlaybackStartupIntent) = (clock() - value.startedNanos).coerceAtLeast(0) / 1_000_000
    private fun armPending(value: PlaybackStartupIntent) {
        val scheduler = scope ?: return
        value.timer?.cancel()
        val remaining = (timeoutMillis - pendingElapsed(value)).coerceAtLeast(1)
        val timer = scheduler.launch(start = CoroutineStart.LAZY) { delay(remaining); expirePending(value) }
        value.timer = timer
        timer.start()
    }
    @Synchronized fun expirePending(value: PlaybackStartupIntent): Boolean {
        if (pending !== value || value.terminal) return false
        if (!value.owner.isCurrent()) return finishPending(value, StartupOutcome.OWNER_LOST)
        if (pendingElapsed(value) >= timeoutMillis) return finishPending(value, StartupOutcome.TIMEOUT)
        armPending(value)
        return false
    }
    @Synchronized fun finishPending(value: PlaybackStartupIntent, outcome: StartupOutcome): Boolean {
        if (pending !== value || value.terminal) return false
        val elapsed = pendingElapsed(value)
        val actual = when {
            !value.owner.isCurrent() -> StartupOutcome.OWNER_LOST
            elapsed >= timeoutMillis -> StartupOutcome.TIMEOUT
            else -> outcome
        }
        value.terminal = true; pending = null
        value.timer?.cancel(); value.timer = null
        value.lifetime?.dispose(); value.lifetime = null
        value.current = { false }
        append(StartupRecord(StartupSample(actual, elapsed, StartupInput.UNKNOWN), StartupOrigin.USER_ACTION, value.entry, value.preparation))
        return true
    }
    @Synchronized fun adopt(value: PlaybackStartupIntent?, target: DetailsTarget, context: Long, input: StartupInput): PlaybackStartupAttempt? {
        if (value == null || pending !== value || value.terminal) return null
        if (!value.owner.isCurrent()) { finishPending(value, StartupOutcome.OWNER_LOST); return null }
        if (pendingElapsed(value) >= timeoutMillis) { finishPending(value, StartupOutcome.TIMEOUT); return null }
        if (!value.matches(target, context) || !runCatching(value.current).getOrDefault(false)) {
            finishPending(value, StartupOutcome.CANCELLED); return null
        }
        // The exact original owner crosses into the player; never recapture the current profile.
        value.terminal = true; pending = null
        value.timer?.cancel(); value.timer = null
        value.lifetime?.dispose(); value.lifetime = null
        value.current = { false }
        return begin(value.owner, input, value.startedNanos, StartupOrigin.USER_ACTION, value.entry, value.preparation)
    }
    private fun append(sample: StartupRecord) {
        samples.addLast(sample)
        while (samples.size > capacity) samples.removeFirst()
    }
    @Synchronized fun snapshot(): List<StartupSample> = samples.map { it.sample }
    @Synchronized fun records(): List<StartupRecord> = samples.toList()
    @Synchronized fun report(): PlaybackStartupReport = startupReport(samples.toList(), inFlight())
    @Synchronized fun begin(owner: PlaybackSessionContext, input: StartupInput = StartupInput.UNKNOWN, startedNanos: Long = clock(),
        origin: StartupOrigin = StartupOrigin.COORDINATOR_ONLY, entry: StartupEntry = StartupEntry.PROGRAMMATIC,
        preparation: StartupPreparation = StartupPreparation.UNKNOWN): PlaybackStartupAttempt {
        active?.let { finish(it, StartupOutcome.CANCELLED) }
        val attempt = PlaybackStartupAttempt(this, owner, startedNanos, input, origin, entry, preparation)
        active = attempt
        if (!owner.isCurrent()) finish(attempt, StartupOutcome.OWNER_LOST)
        else if (elapsed(attempt) >= timeoutMillis) finish(attempt, StartupOutcome.TIMEOUT)
        else arm(attempt)
        return attempt
    }
    private fun elapsed(attempt: PlaybackStartupAttempt) = (clock() - attempt.startedNanos).coerceAtLeast(0) / 1_000_000
    private fun arm(attempt: PlaybackStartupAttempt) {
        val scheduler = scope ?: return
        attempt.timeout?.cancel()
        val remaining = (timeoutMillis - elapsed(attempt)).coerceAtLeast(1)
        val timer = scheduler.launch(start = CoroutineStart.LAZY) { delay(remaining); expire(attempt) }
        attempt.timeout = timer
        timer.start()
    }
    @Synchronized fun expire(attempt: PlaybackStartupAttempt): Boolean {
        if (attempt.completed || active !== attempt) return false
        if (!attempt.owner.isCurrent()) return finish(attempt, StartupOutcome.OWNER_LOST)
        if (elapsed(attempt) >= timeoutMillis) return finish(attempt, StartupOutcome.TIMEOUT)
        // The scheduler and the monotonic observation clock can round a wakeup differently.
        arm(attempt)
        return false
    }
    @Synchronized fun claim(attempt: PlaybackStartupAttempt, owner: PlaybackSessionContext?): Boolean {
        if (attempt.completed || active !== attempt || attempt.claimed || attempt.owner !== owner) return false
        if (!attempt.owner.isCurrent()) { finish(attempt, StartupOutcome.OWNER_LOST); return false }
        if (expire(attempt)) return false
        attempt.claimed = true
        return true
    }
    @Synchronized fun finish(attempt: PlaybackStartupAttempt, outcome: StartupOutcome): Boolean {
        if (attempt.completed || active !== attempt) return false
        val elapsed = elapsed(attempt)
        val actual = when {
            !attempt.owner.isCurrent() -> StartupOutcome.OWNER_LOST
            elapsed >= timeoutMillis -> StartupOutcome.TIMEOUT
            else -> outcome
        }
        attempt.completed = true; active = null
        attempt.timeout?.cancel(); attempt.timeout = null
        append(StartupRecord(StartupSample(actual, elapsed, attempt.input), attempt.origin, attempt.entry, attempt.preparation))
        return true
    }
}

/** Actual player callback adapter: an older controller cannot finish a replacement attempt. */
internal class PlaybackStartupPlayer(session: PlayerSession, private val current: () -> Boolean) {
    private val attempt = session.startupTiming?.takeIf { it.claim(session.ownership) }
    private var closed = false
    fun firstFrame(): Boolean {
        if (closed) return false
        if (!current()) return end(StartupOutcome.OWNER_LOST)
        return end(StartupOutcome.RENDERED)
    }
    fun end(outcome: StartupOutcome): Boolean {
        if (closed) return false
        closed = true
        return attempt?.finish(outcome) == true
    }
}

/** No network endpoint, persisted sample, content label or credential is emitted. */
internal object PlaybackStartupTiming {
    private val recorder by lazy { PlaybackStartupRecorder(scope = MainScope()) }
    fun now(): Long = System.nanoTime()
    fun begin(owner: PlaybackSessionContext, input: StartupInput = StartupInput.UNKNOWN, startedNanos: Long = now()) = recorder.begin(owner, input, startedNanos)
    fun intent(owner: PlaybackSessionContext, target: DetailsTarget, entry: StartupEntry,
        preparation: StartupPreparation = StartupPreparation.UNKNOWN) = recorder.intent(owner, target, entry, preparation)
    fun adopt(value: PlaybackStartupIntent?, target: DetailsTarget, context: Long, input: StartupInput) = recorder.adopt(value, target, context, input)
    fun snapshot(): List<StartupSample> = recorder.snapshot()
    fun report() = recorder.report()
}
