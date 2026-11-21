package com.strmr.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.strmr.tv.api.PrequeueStatus
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener

/** Bounded staging DATA. Completion of this policy alone never authorizes playback. */
internal class ReplacementBufferBudget(val targetMs: Long, val deadlineNanos: Long,
    private val current: () -> Boolean, private val now: () -> Long = System::nanoTime) {
    private var bytes = 0L
    private var reserved = 0L
    class ReadPermit internal constructor(val limit: Int, internal val owner: ReplacementBufferBudget, internal val reserved: Boolean) {
        internal var completed = false
    }
    private var retired = false
    private var transferred = false
    private var rangeLoaded = false
    private var decoderReady = false
    private var claimed = false
    @Synchronized fun check() { check(!retired && current() && (transferred || now() < deadlineNanos)) { "Replacement context expired" } }
    @Synchronized fun allowance(requested: Int): ReadPermit {
        check(); require(requested >= 0)
        if (transferred || requested == 0) return ReadPermit(requested, this, false)
        val remaining = MAX_BYTES - bytes - reserved
        check(remaining > 0) { "Replacement read budget exceeded" }
        val allowed = minOf(requested.toLong(), remaining).toInt()
        reserved += allowed
        return ReadPermit(allowed, this, true)
    }
    @Synchronized fun consumed(permit: ReadPermit, count: Int) {
        check(permit.owner === this && !permit.completed && count in -1..permit.limit)
        permit.completed = true
        if (permit.reserved) {
            reserved -= permit.limit
            if (count > 0) bytes = Math.addExact(bytes, count.toLong())
            check(reserved >= 0 && bytes + reserved <= MAX_BYTES)
        }
        check()
    }
    @Synchronized fun abandon(permit: ReadPermit) {
        check(permit.owner === this)
        if (!permit.completed) {
            permit.completed = true
            if (permit.reserved) reserved -= permit.limit
            check(reserved >= 0)
        }
    }
    @Synchronized fun observeRange() { check(); rangeLoaded = true }
    @Synchronized fun observeDecoder(ready: Boolean) { check(); decoderReady = ready }
    @Synchronized fun ready(): Boolean = runCatching { check(); rangeLoaded && decoderReady && !claimed }.getOrDefault(false)
    @Synchronized fun claim() { check(ready()); claimed = true }
    @Synchronized fun promote() { check(); check(claimed && rangeLoaded && decoderReady && !transferred); transferred = true }
    @Synchronized fun retire() { retired = true }
    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        const val RANGE_MS = 3000L
        private var lease: ReplacementBufferBudget? = null
        @Synchronized fun acquire(value: ReplacementBufferBudget): Boolean = if (lease == null) { lease = value; true } else false
        @Synchronized fun releasedAfterActualJoin(value: ReplacementBufferBudget) { check(lease === value); lease = null }
        @Synchronized fun charged() = if (lease == null) 0 else 1
    }
}

/** Read-only actual device observations, never a source/player handle or adoption grant. */
data class ReplacementBufferObservation(
    val audioSelected: Boolean, val videoSelected: Boolean,
    val audioDecoderInit: Int, val audioDecoderReleased: Int,
    val videoDecoderInit: Int, val videoDecoderReleased: Int,
    val paused: Boolean, val positionMs: Long, val bufferedMs: Long, val durationMs: Long,
    val preloadThreadAlive: Boolean, val surfaceThreadAlive: Boolean, val openBodies: Int, val activeHttpCalls: Int, val originalWorkersAlive: Int,
    val playbackThreadAlive: Boolean, val cleanupJoined: Boolean,
    val decodedPcmFrames: Long, val decodedPcmGeneration: Long,
)

/** Actual factory parts share the original media-access object and captured headers. */
@OptIn(UnstableApi::class)
internal data class ReplacementMediaParts(val sources: MediaSource.Factory, val renderers: RenderersFactory,
    val capabilityRenderers: RenderersFactory, val access: VodMediaAccess, val decodedAudio: DecodedAudioObservation)

/**
 * One actual Media3 source/paused decoder. It is not a media authorization grant.
 * The lease follows a successful transfer until the new primary actually closes;
 * a full paused progressive loader may retain an open body even while READY.
 */
@OptIn(UnstableApi::class)
class ReplacementBuffer private constructor(
    private val context: Context, private val primary: PlayerController,
    internal val candidateSession: PlayerSession, override val targetMs: Long,
    internal val budget: ReplacementBufferBudget,
) : PreparedReleaseBuffer {
    private val self = this
    private var candidateBuildEntered = false
    private var candidateBuildActive = false
    internal fun buildActualPlayer(playerBuilder: ExoPlayer.Builder): ExoPlayer {
        check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
        check(!candidateBuildEntered && !candidateBuildActive && actualPlayer == null && !closing)
        candidateBuildEntered = true
        candidateBuildActive = true
        try { return builder.buildExoPlayer(playerBuilder) }
        finally { candidateBuildActive = false }
    }
    private var originalPlaybackThread: Thread? = null
    private var actualPlayer: ExoPlayer? = null
    private var constructingScope: CoroutineScope? = null
    internal fun retainActualScope(scope: CoroutineScope) { check(constructingScope == null); constructingScope = scope }
    internal fun retainActualPlayer(player: ExoPlayer) {
        check(actualPlayer == null && !closing)
        actualPlayer = player
        originalPlaybackThread = player.playbackLooper.thread
        check(player.playbackLooper === playbackLooper) { "Candidate did not retain its owned playback looper" }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requested = AtomicBoolean()
    private val closed = CompletableDeferred<Unit>()
    private val prepared = CompletableDeferred<Unit>()
    private var cleanupSucceeded = false
    private val reads = linkedSetOf<OwnedDataSource>()
    private val workers = linkedSetOf<Thread>()
    private val calls = linkedSetOf<Call>()
    private val callEvents = EventListener.Factory {
        object : EventListener() {
            override fun callStart(call: Call) {
                val cancel = synchronized(ioLock) { calls += call; closing || calls.size > 16 }
                if (cancel) call.cancel()
            }
            override fun callEnd(call: Call) { synchronized(ioLock) { calls -= call } }
            override fun callFailed(call: Call, ioe: IOException) { synchronized(ioLock) { calls -= call } }
        }
    }
    private val ioLock = Any()
    private val preloadThread = HandlerThread("replacement-source-preload")
    private val surfaceThread = HandlerThread("replacement-decoder-surface")
    internal val playbackLooper get() = preloadThread.looper
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglInitialized = false
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private val delegateLoad = DefaultLoadControl.Builder().setTargetBufferBytes(ReplacementBufferBudget.MAX_BYTES.toInt()).build()
    @Volatile internal var transferred = false; private set
    @Volatile internal var closing = false; private set
    private val loadControl = object : LoadControl by delegateLoad {
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
            (transferred || parameters.bufferedDurationUs < 3_500_000L) && delegateLoad.shouldContinueLoading(parameters)
    }
    internal lateinit var parts: ReplacementMediaParts; private set
    internal lateinit var builder: DefaultPreloadManager.Builder; private set
    private lateinit var manager: DefaultPreloadManager
    private var preloaderClosed: CompletableDeferred<Unit>? = null
    internal lateinit var loadedSource: MediaSource; private set
    internal var controller: PlayerController? = null; private set
    private var surface: Surface? = null
    private val item = MediaItem.fromUri(candidateSession.url)
    private val listener = object : PreloadManagerListener {
        override fun onCompleted(mediaItem: MediaItem) {
            if (mediaItem != item || controller != null || closing) return
            try {
                budget.observeRange()
                loadedSource = requireNotNull(manager.getMediaSource(item))
                val staged = primary.replacementController(context, candidateSession.copy(replacementBuffer = self))
                controller = staged
                originalPlaybackThread = staged.player.playbackLooper.thread
                staged.player.setVideoSurface(requireNotNull(surface))
            } catch (error: Exception) { prepared.completeExceptionally(error); close() }
        }
        override fun onError(error: PreloadException) { prepared.completeExceptionally(error); close() }
    }
    private var watcher: Job? = null
    private var abandoned: (suspend () -> Unit)? = null
    internal fun onAbandoned(cleanup: suspend () -> Unit) { check(!transferred && abandoned == null); abandoned = cleanup }
    private suspend fun start() {
        require(targetMs >= 0 && targetMs == budget.targetMs)
        createSurface()
        preloadThread.start()
        parts = primary.replacementMediaParts(context, candidateSession, ::boundedFactory, callEvents)
        builder = DefaultPreloadManager.Builder(context, TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(targetMs, ReplacementBufferBudget.RANGE_MS)
        }).setMediaSourceFactory(parts.sources).setRenderersFactory(RenderersFactory { handler, video, audio, text, metadata ->
            check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
            val actualFactory = if (candidateBuildActive) parts.renderers else parts.capabilityRenderers
            actualFactory.createRenderers(handler, video, audio, text, metadata)
        })
            .setLoadControl(loadControl).setPreloadLooper(preloadThread.looper)
        manager = builder.build()
        manager.addListener(listener); manager.add(item, 0); manager.invalidate()
        watcher = scope.launch {
            try {
                while (isActive) {
                    budget.check()
                    if (!transferred) {
                        check(primary.canUsePlayerAux() && primary.session === PlaybackCoordinator.pending) { "Original player changed" }
                        controller?.let { candidate ->
                            check(candidate.error.value == null && candidate.player.playerError == null) { "Replacement decoder failed" }
                            val player = candidate.player
                            val audio = player.audioDecoderCounters
                            val video = player.videoDecoderCounters
                            audio?.ensureUpdated(); video?.ensureUpdated()
                            val selectedAudio = player.currentTracks.ofType(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.format
                            val selectedVideo = player.currentTracks.ofType(C.TRACK_TYPE_VIDEO).firstOrNull { it.selected }?.format
                            val decoded = selectedAudio != null && selectedVideo != null && audio != null && video != null &&
                                audio.decoderInitCount > audio.decoderReleaseCount && video.decoderInitCount > video.decoderReleaseCount &&
                                player.audioFormat == selectedAudio && player.videoFormat == selectedVideo
                            val range = player.currentPosition == targetMs && player.bufferedPosition - targetMs >= ReplacementBufferBudget.RANGE_MS &&
                                player.duration != C.TIME_UNSET && targetMs <= player.duration - ReplacementBufferBudget.RANGE_MS
                            budget.observeDecoder(decoded && range && player.playbackState == Player.STATE_READY && !player.playWhenReady)
                            if (budget.ready() && !prepared.isCompleted) {
                                retirePreloaderAndDrain()
                                budget.check()
                                check(player.playerError == null && player.currentPosition == targetMs &&
                                    player.bufferedPosition - targetMs >= ReplacementBufferBudget.RANGE_MS)
                                prepared.complete(Unit)
                            }
                        }
                    }
                    delay(25)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { prepared.completeExceptionally(error); close() }
        }
    }
    private suspend fun retirePreloaderAndDrain() {
        preloaderClosed?.let { it.await(); return }
        val done = CompletableDeferred<Unit>()
        preloaderClosed = done
        withContext(NonCancellable) {
            try {
                if (::manager.isInitialized) {
                    manager.removeListener(listener)
                    manager.remove(item)
                    manager.release()
                }
                if (preloadThread.isAlive) {
                    val barrier = CompletableDeferred<Unit>()
                    check(Handler(preloadThread.looper).post { barrier.complete(Unit) })
                    withTimeout(15_000) { barrier.await() }
                    // This exact looper is still owned by the paused player/source. It moves
                    // with the charged actor and is joined only during actual final cleanup.
                }
                done.complete(Unit)
            } catch (error: Exception) { done.completeExceptionally(error); throw error }
        }
    }
    fun observe(): ReplacementBufferObservation {
        check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
        val player = controller?.player
        val audio = if (!closing) player?.audioDecoderCounters else null
        val video = if (!closing) player?.videoDecoderCounters else null
        audio?.ensureUpdated(); video?.ensureUpdated()
        val pcm = if (::parts.isInitialized) parts.decodedAudio.snapshot() else null
        return ReplacementBufferObservation(
            !closing && player?.currentTracks?.ofType(C.TRACK_TYPE_AUDIO)?.any { it.selected } == true,
            !closing && player?.currentTracks?.ofType(C.TRACK_TYPE_VIDEO)?.any { it.selected } == true,
            audio?.decoderInitCount ?: 0, audio?.decoderReleaseCount ?: 0,
            video?.decoderInitCount ?: 0, video?.decoderReleaseCount ?: 0,
            !closing && player?.playWhenReady == false, if (!closing) player?.currentPosition ?: 0 else 0,
            if (!closing) player?.bufferedPosition ?: 0 else 0, if (!closing) player?.duration ?: C.TIME_UNSET else C.TIME_UNSET,
            preloadThread.isAlive, surfaceThread.isAlive, synchronized(ioLock) { reads.size }, synchronized(ioLock) { calls.size },
            synchronized(ioLock) { workers.count { it.isAlive } }, originalPlaybackThread?.isAlive == true,
            closed.isCompleted && !closed.isCancelled && cleanupSucceeded,
            pcm?.frames ?: 0, pcm?.generation ?: 0,
        )
    }
    internal suspend fun awaitPrepared() { prepared.await(); check(isReady()) }
    override fun isReady(): Boolean = !closing && !transferred && budget.ready()
    internal fun transfer(primaryOwner: PlayerController): PlayerController {
        check(primaryOwner === primary && primaryOwner.canUsePlayerAux() && primaryOwner.session === PlaybackCoordinator.pending)
        check(isReady())
        val candidate = requireNotNull(controller)
        check(candidate.error.value == null && candidate.player.playerError == null && !candidate.player.playWhenReady &&
            candidate.player.playbackState == Player.STATE_READY && candidate.player.currentPosition == targetMs &&
            candidate.player.bufferedPosition - targetMs >= ReplacementBufferBudget.RANGE_MS)
        budget.claim()
        return candidate
    }
    internal fun promote(primaryOwner: PlayerController) {
        check(primaryOwner === primary && primaryOwner.canUsePlayerAux() && primaryOwner.session === PlaybackCoordinator.pending)
        budget.promote(); transferred = true
    }
    private fun boundedFactory(factory: DataSource.Factory) = DataSource.Factory { OwnedDataSource(factory.createDataSource()) }
    private inner class OwnedDataSource(private val delegate: DataSource) : DataSource {
        private val opened = AtomicBoolean()
        private fun originalWorker() { synchronized(ioLock) {
            workers.removeAll { !it.isAlive }
            val thread = Thread.currentThread()
            check(thread in workers || workers.size < 16) { "Replacement loader limit exceeded" }
            workers += thread
        } }
        override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
        override fun open(dataSpec: DataSpec): Long {
            originalWorker(); budget.check()
            synchronized(ioLock) { check(reads.size < 8 && opened.compareAndSet(false, true)); reads += this }
            return try { delegate.open(dataSpec).also { budget.check() } } catch (error: Exception) { close(); throw error }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            originalWorker(); val permit = budget.allowance(length)
            return try { delegate.read(buffer, offset, permit.limit).also { budget.consumed(permit, it) } }
            finally { budget.abandon(permit) }
        }
        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun close() { if (opened.compareAndSet(true, false)) try { delegate.close() } finally { synchronized(ioLock) { reads -= this } } }
    }
    /** Signal immediately; callers that own a transition must also await closeAndJoin. */
    internal fun close() { if (requested.compareAndSet(false, true)) { closing = true; budget.retire(); scope.launch(NonCancellable) { drain() } } }
    override suspend fun closeAndJoin() { withContext(NonCancellable) { close(); closed.await() } }
    private suspend fun createSurface() {
        surfaceThread.start()
        val made = CompletableDeferred<Unit>()
        check(Handler(surfaceThread.looper).post {
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(eglDisplay != EGL14.EGL_NO_DISPLAY)
                check(EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 0))
                eglInitialized = true
                val configs = arrayOfNulls<EGLConfig>(1)
                val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
                val config = requireNotNull(configs[0])
                eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                check(eglContext != EGL14.EGL_NO_CONTEXT)
                eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(eglSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
                val name = IntArray(1)
                GLES20.glGenTextures(1, name, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, name[0])
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                texture = SurfaceTexture(name[0])
                surface = Surface(requireNotNull(texture))
                made.complete(Unit)
            } catch (error: Exception) { made.completeExceptionally(error) }
        })
        withTimeout(15_000) { made.await() }
    }
    private suspend fun stopOwnedLooper(thread: HandlerThread) {
        if (!thread.isAlive) return
        val barrier = CompletableDeferred<Unit>()
        check(Handler(thread.looper).post { barrier.complete(Unit) })
        withTimeout(15_000) { barrier.await() }
        thread.quitSafely()
        withContext(Dispatchers.IO) { thread.join(15_000) }
        check(!thread.isAlive) { "Original owned looper cleanup pending" }
    }
    private suspend fun releaseSurfaceAndJoin() {
        if (surfaceThread.isAlive) {
            val released = CompletableDeferred<Unit>()
            check(Handler(surfaceThread.looper).post {
                var failure: Exception? = null
                fun step(action: () -> Unit) {
                    try { action() } catch (error: Exception) {
                        val first = failure
                        if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                    }
                }
                step { surface?.release(); surface = null }
                step { texture?.release(); texture = null }
                if (eglInitialized) {
                    step { check(EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) }
                    if (eglSurface != EGL14.EGL_NO_SURFACE) step { check(EGL14.eglDestroySurface(eglDisplay, eglSurface)) }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) step { check(EGL14.eglDestroyContext(eglDisplay, eglContext)) }
                    step { check(EGL14.eglReleaseThread()) }
                    step { check(EGL14.eglTerminate(eglDisplay)) }
                }
                val error = failure
                if (error == null) released.complete(Unit) else released.completeExceptionally(error)
            })
            try { withTimeout(15_000) { released.await() } }
            finally { stopOwnedLooper(surfaceThread) }
        }
    }
    private suspend fun drain() {
        var failure: Exception? = null
        suspend fun attempt(operation: suspend () -> Unit) {
            try { operation() } catch (error: Exception) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        try {
            attempt { watcher?.cancelAndJoin() }
            // Cancel only real owned calls, including requests still awaiting headers.
            synchronized(ioLock) { calls.toList() }.forEach { it.cancel() }
            // Close only actual owned bodies, never the shared HTTP dispatcher or primary.
            attempt { withContext(Dispatchers.IO) {
                var first: Exception? = null
                synchronized(ioLock) { reads.toList() }.forEach {
                    try { it.close() } catch (error: Exception) { if (first == null) first = error else first!!.addSuppressed(error) }
                }
                first?.let { throw it }
            } }
            attempt {
                val owned = controller
                if (owned != null) owned.release() else try { actualPlayer?.release() } finally { constructingScope?.cancel() }
            }
            attempt { retirePreloaderAndDrain() }
            attempt { stopOwnedLooper(preloadThread) }
            attempt { withContext(Dispatchers.IO) {
                val deadline = System.nanoTime() + 15_000_000_000L
                val actualWorkers = synchronized(ioLock) { workers.toList() }
                actualWorkers.forEach { thread -> if (thread !== Thread.currentThread() && thread.isAlive) {
                    thread.join(((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1))
                    check(!thread.isAlive) { "Original media loader cleanup pending" }
                } }
            } }
            attempt { withTimeout(15_000) { while (synchronized(ioLock) { calls.isNotEmpty() }) delay(10) } }
            attempt { releaseSurfaceAndJoin() }
            attempt { check(synchronized(ioLock) { reads.isEmpty() }) }
            if (!transferred) withTimeoutOrNull(5_000) { runCatching { abandoned?.invoke() } }
            failure?.let { throw it }
            ReplacementBufferBudget.releasedAfterActualJoin(budget)
            cleanupSucceeded = true
            closed.complete(Unit)
        } catch (error: Exception) { closed.completeExceptionally(error) }
        finally { scope.cancel() }
    }
    override fun toString() = "ReplacementBuffer(original media owner)"
    companion object {
        /** Resource observation only; no caller can mint or consume an actor through it. */
        fun ownedLeaseCount(): Int = ReplacementBufferBudget.charged()
        internal suspend fun prepare(context: Context, primary: PlayerController, status: PrequeueStatus, targetMs: Long,
            deadlineNanos: Long, recovery: Boolean): ReplacementBuffer {
            primary.requireReplacementOwner()
            val session = PlaybackCoordinator.prepareReplacement(primary.session, status, targetMs, primary.replacementCarriedTracks(), primary.speed.value, recovery)
            val budget = ReplacementBufferBudget(targetMs, deadlineNanos, { session.canOpenPlayer() })
            check(ReplacementBufferBudget.acquire(budget)) { "Another replacement is still owned" }
            var value: ReplacementBuffer? = null
            try {
                value = ReplacementBuffer(context.applicationContext, primary, session, targetMs, budget)
                value.start()
                value.awaitPrepared(); primary.requireReplacementOwner(); return value
            } catch (error: Exception) {
                if (value != null) try { value.closeAndJoin() } catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
                else ReplacementBufferBudget.releasedAfterActualJoin(budget)
                throw error
            }
        }
    }
}
