package com.strmr.tv.player

import com.strmr.tv.live.RecordingProgressCommand
import com.strmr.tv.live.RecordingProgressGateway
import com.strmr.tv.live.RecordingProgressWriteObservation
import kotlinx.coroutines.*

/** Finite native work only. This object contains no account, credential or media permission. */
internal class RecordingProgressResource private constructor(parent: CoroutineScope) {
    private val lifetime = SupervisorJob()
    val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private var closing: Job? = null
    private var parentStop: DisposableHandle? = null
    init { parentStop = parent.coroutineContext[Job]?.invokeOnCompletion { close() } }

    @Synchronized fun transfer() { check(closing == null); parentStop?.dispose(); parentStop = null }

    /** Every caller receives the same actual completion, including cancellation/body Close. */
    fun close(): Job {
        val job = synchronized(this) {
            closing?.let { return it }
            val cleanup = CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.LAZY) {
                lifetime.join()
                synchronized(this@RecordingProgressResource) { parentStop?.dispose(); parentStop = null }
                synchronized(capacity) { check(capacity.used > 0); capacity.used-- }
            }
            // Publish before cancel: a canceled operation may re-enter close synchronously.
            closing = cleanup
            cleanup
        }
        // No cancellation callback or actual IO runs under the resource mutex.
        lifetime.cancel()
        job.start()
        return job
    }

    companion object {
        private object capacity { var used = 0 }
        fun acquire(parent: CoroutineScope): RecordingProgressResource? = synchronized(capacity) {
            if (capacity.used >= 2 || parent.coroutineContext[Job]?.isActive == false) null
            else { capacity.used++; RecordingProgressResource(parent) }
        }
        internal fun charged(): Int = synchronized(capacity) { capacity.used }
    }
}

/** One acknowledged CAS chain. Reply deadlines limit adoption, not retained counter DATA. */
internal class RecordingProgressReporter(
    private val resource: RecordingProgressResource,
    initial: RecordingProgressWriteObservation,
    private val gateway: RecordingProgressGateway,
    private val current: () -> Boolean,
    private val sample: () -> Long?,
    private val playing: () -> Boolean,
    private val now: () -> Long = System::nanoTime,
    private val failed: () -> Unit,
) {
    private val lock = Any()
    private var acknowledged = initial
    private var pending: Long? = null
    private var operation: Job? = null
    private var periodic: Job? = null
    private var watcher: Job? = null
    private var enabled = false
    private var terminal = false
    private var closing = false
    private var completion: Job? = null

    fun start() = synchronized(lock) {
        if (enabled || terminal || closing) return@synchronized
        enabled = true
        periodic = resource.scope.launch {
            while (isActive) { delay(10_000); if (playing()) sample()?.let(::offer) }
        }
        watcher = resource.scope.launch {
            while (isActive) {
                if (!current()) { discard(); return@launch }
                delay(250)
            }
        }
    }

    fun offer(positionMs: Long) = synchronized(lock) {
        if (!enabled || terminal || closing) return@synchronized
        if (!current() || positionMs !in 0..acknowledged.data.identity.durationMs) {
            retire(); return@synchronized
        }
        pending = positionMs
        pump()
    }

    private fun pump() {
        if (operation != null || terminal || closing) return
        val job = resource.scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val position = synchronized(lock) {
                        if (terminal || closing) null else pending.also { pending = null }
                    } ?: break
                    save(position)
                }
            } catch (_: Exception) { synchronized(lock) { retire() } }
            finally {
                synchronized(lock) { operation = null; if (pending != null) pump() }
            }
        }
        operation = job
        job.start()
    }

    private suspend fun save(position: Long) {
        currentCoroutineContext().ensureActive()
        check(current())
        val previous = synchronized(lock) { acknowledged }
        val command = RecordingProgressCommand.touch(previous, position)
        val reply = gateway.write(previous.original, command, previous.expectedContext)
        currentCoroutineContext().ensureActive()
        check(current() && reply.original === previous.original && reply.expectedContext == previous.expectedContext && reply.adoptable(now()))
        synchronized(lock) {
            check(!terminal)
            // accept() already checks the exact operation, position, tuple and CAS arithmetic.
            check(reply.data.identity == previous.data.identity && reply.data.revision.value == Math.addExact(previous.data.revision.value, 1L) &&
                reply.data.writerGeneration == previous.data.writerGeneration && reply.data.positionMs == position)
            acknowledged = reply
        }
    }

    /** Signal-only from active operations/watchers; resource Close joins externally. */
    private fun retire() {
        if (terminal) return
        terminal = true; pending = null
        periodic?.cancel(); watcher?.cancel(); operation?.cancel()
        resource.close()
        failed()
    }

    fun discard() = synchronized(lock) { retire() }

    /** Normal exit may save one final sample; a failed/unknown chain never invents its CAS. */
    fun close(positionMs: Long?, afterCleanup: () -> Unit): Job = synchronized(lock) {
        completion?.let { return@synchronized it }
        closing = true; pending = null
        periodic?.cancel()
        // Keep the original-source watcher alive through the final network operation.
        val active = operation
        val job = CoroutineScope(resource.scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.LAZY) {
            try {
                active?.join()
                val final = synchronized(lock) {
                    positionMs?.takeIf { enabled && !terminal && current() && it in 0..acknowledged.data.identity.durationMs }
                }
                if (final != null) {
                    val actual = resource.scope.async { save(final) }
                    actual.await()
                }
            } catch (_: Exception) { synchronized(lock) { retire() } }
            finally { resource.close().join(); afterCleanup() }
        }
        completion = job
        job.start()
        job
    }
}
