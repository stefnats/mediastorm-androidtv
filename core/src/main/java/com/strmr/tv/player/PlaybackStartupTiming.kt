package com.strmr.tv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local coordinator-to-first-render timing. These samples contain no content or owner identity. */
internal enum class StartupOutcome { RENDERED, FAILED, CANCELLED, RELEASED, OWNER_LOST, TIMEOUT, EXTERNAL_HANDOFF }
internal enum class StartupInput { UNKNOWN, READY_PREPARATION_SUPPLIED, RESOLVED_INPUT_SUPPLIED, DIRECT_INPUT_SUPPLIED }
internal fun startupInput(prepared: com.strmr.tv.api.PrequeueStatus?, resolved: Boolean): StartupInput = when {
    prepared?.status == "ready" && !prepared.streamPath.isNullOrBlank() -> StartupInput.READY_PREPARATION_SUPPLIED
    resolved -> StartupInput.RESOLVED_INPUT_SUPPLIED
    else -> StartupInput.UNKNOWN
}
internal data class StartupSample(val outcome: StartupOutcome, val elapsedMillis: Long, val input: StartupInput)

/** Opaque, transient attempt carried by one original session. Never a playback permission. */
class PlaybackStartupAttempt internal constructor(
    private val recorder: PlaybackStartupRecorder,
    internal val owner: PlaybackSessionContext,
    internal val startedNanos: Long,
    internal val input: StartupInput,
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
    private val samples = ArrayDeque<StartupSample>()
    private var active: PlaybackStartupAttempt? = null
    @Synchronized fun snapshot(): List<StartupSample> = samples.toList()
    @Synchronized fun begin(owner: PlaybackSessionContext, input: StartupInput = StartupInput.UNKNOWN, startedNanos: Long = clock()): PlaybackStartupAttempt {
        active?.let { finish(it, StartupOutcome.CANCELLED) }
        val attempt = PlaybackStartupAttempt(this, owner, startedNanos, input)
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
        samples.addLast(StartupSample(actual, elapsed, attempt.input))
        while (samples.size > capacity) samples.removeFirst()
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
    fun snapshot(): List<StartupSample> = recorder.snapshot()
}
