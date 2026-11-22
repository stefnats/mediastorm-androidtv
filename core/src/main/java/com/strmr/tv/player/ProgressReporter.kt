package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.reportProgress
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tracks cancelable report children even when the closing caller is non-cancelable. */
internal class ProgressWriteGate(private val current: () -> Boolean) {
    private var discarded = false
    private val jobs = mutableSetOf<Job>()
    val accepts: Boolean get() = synchronized(this) { !discarded && current() }

    fun discard() {
        val pending = synchronized(this) { discarded = true; jobs.toList() }
        pending.forEach { it.cancel() }
    }

    /** Stop new reports without interrupting a request already accepted by the server. */
    fun stopNewWrites() { synchronized(this) { discarded = true } }

    /** No new producer may enqueue while a room final snapshot is draining. */
    suspend fun joinExisting() {
        while (true) {
            val pending = synchronized(this) { jobs.toList() }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    /** Await actual write completion before sending an explicit history reset. */
    suspend fun joinStopped() {
        val pending = synchronized(this) { check(discarded); jobs.toList() }
        pending.forEach { it.join() }
    }

    fun use(action: () -> Unit): Boolean = synchronized(this) {
        if (discarded || !current()) false else { action(); true }
    }

    suspend fun <T> write(action: suspend () -> T): T? = coroutineScope {
        if (!accepts) return@coroutineScope null
        val child = async(start = CoroutineStart.LAZY) {
            currentCoroutineContext().ensureActive()
            if (!accepts) return@async null
            val result = action()
            currentCoroutineContext().ensureActive()
            if (accepts) result else null
        }
        synchronized(this@ProgressWriteGate) {
            if (discarded || !current()) child.cancel() else {
                jobs.add(child)
                child.invokeOnCompletion { synchronized(this@ProgressWriteGate) { jobs.remove(child) } }
            }
        }
        child.start()
        child.await()
    }
}

/**
 * A playback's progress reports to the server: every 10 s while playing, and on pause, end and
 * close, with the stream's health. A reply asking for a migration calls [onMigration].
 */
class ProgressReporter(
    private val player: ExoPlayer,
    private val session: PlayerSession,
    private val scope: CoroutineScope,
    private val context: Long = ProfileDataContext.current,
    private val onMigration: (reason: String) -> Unit,
) {
    private val request = session.request
    private val roomDrain = session.room?.let { RoomProgressDrain(it) }
    private var roomClosing = false
    private val writes = ProgressWriteGate {
        (if (roomClosing) roomDrain?.allowed() == true && session.ownership?.isCurrent() == true
            else session.playbackAllowed()) && context == ProfileDataContext.current
    }
    val acceptsCompletion get() = writes.accepts
    var lastStoredItemId: String? = null
        private set
    private var periodic: Job? = null
    /** The latest report in flight, which pages reloading after playback wait on. */
    var lastReport: Job? = null
        private set
    private val log = ProgressLog(request.title.name + (request.episode?.let { " · ${it.code}" } ?: ""))

    /** Starts the 10 s reports (once). */
    fun start() {
        if (periodic?.isActive == true) return
        periodic = scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) report()
            }
        }
    }

    /** Do not save further progress for a source the viewer identified as the wrong content. */
    fun discardFuture() { writes.discard(); stop() }

    /** Finish old reports before an explicit server-side history correction. */
    suspend fun stopAndDrainForHistoryReset() {
        writes.stopNewWrites()
        stop()
        player.pause()
        periodic?.join()
        writes.joinStopped()
        lastReport?.join()
    }

    fun stop() {
        periodic?.cancel()
    }

    /** Position and duration in seconds, or null before the duration is known. */
    private fun times(): Pair<Double, Double>? {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return player.currentPosition / 1000.0 to duration / 1000.0
    }

    fun report(paused: Boolean = false, ended: Boolean = false, closing: Boolean = false) {
        if (roomClosing || !session.reportsProgress || !acceptsCompletion) return
        val (position, duration) = times() ?: return
        val body = request.progressReport(position, duration, paused, ended, sourcePath = session.streamPath, stream = streamHealth())
        session.progressSink?.saved(position, duration, ended)
        lastReport = scope.launch {
            withContext(NonCancellable) { writeReport(body, position, duration, paused, ended, closing) }
        }
    }

    /**
     * Capture the actual final position before decoder release. Stop producer admission
     * immediately, join old writes, then admit at most this same original final snapshot.
     */
    fun closeRoom(): Job? {
        if (roomClosing) return lastReport
        val drain = roomDrain ?: return lastReport
        val sample = times()
        val admitted = acceptsCompletion && sample != null && drain.begin()
        val body = if (admitted) request.progressReport(sample!!.first, sample.second, paused = true,
            ended = false, sourcePath = session.streamPath, stream = streamHealth()) else null
        roomClosing = true
        stop()
        return MainScope().launch {
            try {
                // The global gate still honors discardFuture/context invalidation.
                writes.joinExisting()
                if (body != null && drain.allowed()) {
                    withContext(NonCancellable) { writeReport(body, sample!!.first, sample.second, true, false, true) }
                }
            } finally {
                writes.stopNewWrites()
                withContext(NonCancellable) { writes.joinStopped() }
            }
        }.also { lastReport = it }
    }

    private suspend fun writeReport(body: JsonObject, position: Double, duration: Double,
        paused: Boolean, ended: Boolean, closing: Boolean) {
        val kind = when {
            ended -> ProgressLog.Kind.END
            closing -> ProgressLog.Kind.CLOSE
            paused -> ProgressLog.Kind.PAUSE
            else -> ProgressLog.Kind.PERIODIC
        }
        try {
            writes.write {
                ProfileDataContext.requireCurrent(context)
                val reply = ApiClient.reportProgress(body, expectedContext = context)
                currentCoroutineContext().ensureActive()
                ApiClient.inContext(context) {
                    writes.use {
                        lastStoredItemId = reply.itemId?.takeIf { it.isNotBlank() } ?: lastStoredItemId
                        log.saved(kind, position, duration, reply)
                        if (reply.migrationRequested && !ended && !closing) {
                            onMigration("server: " + (reply.migrationReason ?: "migration requested"))
                        }
                    }
                }
            }
        } catch (error: CancellationException) { throw error
        } catch (e: Exception) {
            ApiClient.inContext(context) {
                writes.use {
                    log.failed(kind, position, duration, e)
                    if (e.vodDenial == null) session.progressSink?.undelivered(body)
                }
            }
        }
    }

    private fun streamHealth() = StreamHealth(
        buffering = player.playbackState == Player.STATE_BUFFERING,
        bufferAheadSeconds = player.totalBufferedDuration / 1000.0,
        requiredMbps = player.videoFormat?.bitrate?.takeIf { it > 0 }?.let { it / 1_000_000.0 },
    )
}


/** Five seconds to admit only the captured final write; actual accepted I/O is still joined. */
internal class RoomProgressDrain(
    private val room: com.strmr.tv.rooms.WatchRoomPlayback,
    private val now: () -> Long = System::nanoTime,
) {
    private var deadline: Long? = null
    fun begin(): Boolean {
        if (deadline != null || !room.current() || !room.progressAllowed) return false
        deadline = now() + 5_000_000_000L
        return true
    }
    fun allowed(): Boolean = deadline?.let { now() - it < 0 && room.originalProgressCurrent() } == true
}
