package com.strmr.tv.launcher

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ContinueWatchingItem
import com.strmr.tv.api.vodDenial
import com.strmr.tv.home.WatchlistItem
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Original input correlation, never a content, parent or playback grant. */
internal class LauncherSnapshot private constructor(
    val owner: PlaybackSessionContext,
    val base: HttpUrl,
    private val originalRoute: HttpUrl,
    val prefetched: List<ContinueWatchingItem>?,
    val hideWatched: Boolean,
) {
    val isCurrent get() = runCatching { requireCurrent(); true }.getOrDefault(false)
    fun requireCurrent() = owner.use {
        if (ApiClient.baseUrl != originalRoute) throw CancellationException("Launcher route changed")
    }
    fun absolute(raw: String): String? {
        val absolute = raw.toHttpUrlOrNull()
        if (absolute != null) return raw.takeIf {
            absolute.username.isEmpty() && absolute.password.isEmpty() && !(sameOrigin(absolute) && nativeQuery(absolute))
        }
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) return null
        val prefix = base.encodedPath.trimEnd('/')
        val value = if (raw.startsWith('/') && !raw.startsWith("//") && prefix.isNotEmpty() &&
            raw.substringBefore('?').substringBefore('#') != prefix && !raw.startsWith("$prefix/")) prefix + raw else raw
        val directory = base.newBuilder().encodedPath(prefix + "/").build()
        val resolved = directory.resolve(value) ?: return null
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
        if (sameOrigin(resolved) && nativeQuery(resolved)) return null
        if (!raw.startsWith("//") && resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port &&
            prefix.isNotEmpty() && resolved.encodedPath != prefix && !resolved.encodedPath.startsWith("$prefix/")) return null
        return resolved.toString()
    }
    /** Sanitize both visible art and the art embedded by existing DeepLink target serialization. */
    fun payload(watching: List<ContinueWatchingItem>, saved: List<WatchlistItem>): LauncherPayload {
        val safeWatching = watching.map { item -> item.copy(
            posterUrl = item.posterUrl?.let(::absolute), textPosterUrl = item.textPosterUrl?.let(::absolute),
            backdropUrl = item.backdropUrl?.let(::absolute), textBackdropUrl = item.textBackdropUrl?.let(::absolute),
            backdropUrls = item.backdropUrls?.mapNotNull(::absolute), logoUrl = item.logoUrl?.let(::absolute),
        ) }
        val safeSaved = saved.map { item -> item.copy(
            posterUrl = item.posterUrl?.let(::absolute), textPosterUrl = item.textPosterUrl?.let(::absolute),
            backdropUrl = item.backdropUrl?.let(::absolute), textBackdropUrl = item.textBackdropUrl?.let(::absolute),
            backdropUrls = item.backdropUrls?.mapNotNull(::absolute), logoUrl = item.logoUrl?.let(::absolute),
        ) }
        return LauncherPayload.build(safeWatching, safeSaved, owner.profileId, hideWatched, resolve = ::absolute)
    }
    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    private fun nativeQuery(url: HttpUrl) = url.queryParameterNames.any { name ->
        name.lowercase(java.util.Locale.ROOT) in setOf(
            "token", "access_token", "accesstoken", "session", "sessiontoken", "profileunlock", "unlocktoken",
            "authorization", "password", "accountpassword", "pin", "profilepin", "api_key", "apikey",
        )
    }
    companion object {
        fun capture(expected: Long = ProfileDataContext.current, prefetched: List<ContinueWatchingItem>?, hideWatched: Boolean) = ApiClient.inContext(expected) {
            val route = ApiClient.baseUrl
            val base = route.newBuilder().username("").password("").query(null).fragment(null).build()
            val rows = prefetched?.map { item -> item.copy(
                externalIds = item.externalIds?.let { java.util.Collections.unmodifiableMap(it.toMap()) },
                backdropUrls = item.backdropUrls?.let { java.util.Collections.unmodifiableList(it.toList()) },
            ) }?.let { java.util.Collections.unmodifiableList(it) }
            LauncherSnapshot(PlaybackSessionContext.capture(expected), base, route, rows, hideWatched)
        }
    }
}

internal interface LauncherProviderPort {
    suspend fun write(payload: LauncherPayload)
    suspend fun clear()
}
internal enum class LauncherOutcome { WRITTEN, CLEARED, FAILED }

/** A real serialized provider cycle. Waiting old work cannot write or clear a replacement. */
internal class LauncherOwnership(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val provider: LauncherProviderPort,
    private val load: suspend (LauncherSnapshot) -> LauncherPayload?,
    private val report: (LauncherOutcome) -> Unit = {},
    private val debounceMs: Long = 1_500,
) {
    private class Ticket(val number: Long, val input: LauncherSnapshot)
    private val lock = Mutex()
    private var sequence = 0L
    private var active: Ticket? = null
    private var pending: Job? = null
    @Volatile private var revision = 0L
    @Volatile private var assigned: Ticket? = null
    private var last: LauncherPayload? = null
    private fun current(ticket: Ticket) = active === ticket && sequence == ticket.number && ticket.input.isCurrent && scope.coroutineContext[Job]?.isActive != false

    fun request(input: LauncherSnapshot): Job? {
        if (!input.isCurrent) return null
        pending?.cancel()
        val ticket = Ticket(++sequence, input); active = ticket
        return scope.launch {
            try {
                delay(debounceMs)
                if (!current(ticket)) return@launch
                val payload = load(input) ?: return@launch
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@launch
                withContext(io + NonCancellable) {
                    lock.withLock {
                        if (!current(ticket)) return@withLock
                        if (last == payload && assigned?.input?.owner?.epoch == input.owner.epoch) return@withLock
                        revision++; assigned = ticket; last = null
                        var cleanupAttempted = false
                        try {
                            if (payload.isEmpty) provider.clear() else provider.write(payload)
                            if (current(ticket)) { last = payload; report(LauncherOutcome.WRITTEN) }
                        } catch (failure: Exception) {
                            if (assigned === ticket) {
                                cleanupAttempted = true
                                try { cleanup() } catch (_: Exception) { report(LauncherOutcome.FAILED) }
                            }
                            throw failure
                        } finally {
                            // Only this still-associated write can be retired before a new writer gets the lock.
                            if (!cleanupAttempted && !current(ticket) && assigned === ticket) cleanup()
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                report(LauncherOutcome.FAILED)
                if (failure.vodDenial != null && current(ticket)) clear()
            }
        }.also { pending = it }
    }

    /** Capture the resource revision now; a later write makes this clear ineligible. */
    fun clear(): Job {
        pending?.cancel(); pending = null; active = null; sequence++
        return queueClear(revision)
    }

    /** A delayed epoch observer must not cancel an already-current replacement request. */
    fun retireStale() {
        if (active?.input?.isCurrent == false) { pending?.cancel(); pending = null; active = null; sequence++ }
        reserveStaleClear()?.invoke()
    }

    /** Bind the actually observed resource before an observer can wait or lose its timeslice. */
    internal fun reserveStaleClear(): (() -> Job)? {
        val observed = assigned ?: return null
        if (observed.input.isCurrent) return null
        return { queueClear(revision, observed) }
    }

    private fun queueClear(expectedRevision: Long, expectedTicket: Ticket? = null): Job = scope.launch {
        withContext(io + NonCancellable) {
            lock.withLock {
                if (revision != expectedRevision || expectedTicket != null && assigned !== expectedTicket) return@withLock
                try { cleanup() } catch (_: Exception) { report(LauncherOutcome.FAILED) }
            }
        }
    }

    private suspend fun cleanup() {
        // Invalidate queued clears before touching the provider, even if its operation fails.
        revision++; last = null
        provider.clear(); assigned = null
        report(LauncherOutcome.CLEARED)
    }
}
