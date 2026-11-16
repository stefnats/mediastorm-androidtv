package com.strmr.tv.live

import android.content.Context
import android.os.SystemClock
import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.app.App
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import java.net.URLEncoder

/**
 * Live TV's remembered "this provider needs HLS" (Expo `livePlaybackPreference`, tvOS
 * `LiveHLSPreference`). When a direct stream won't start the player retries once as an HLS
 * session ([com.strmr.tv.player.LiveHlsFallback]); once that has played for a while the choice
 * is kept on this device for the server and live source, and later tunes of that source ask
 * for HLS at once, unless a direct stream of the source has played here before, which rules the
 * fallback out for it. Settings › Try Direct Again clears both marks.
 */
object LiveHlsPreference {
    /** The server (its remote-access identity) and live source a preference belongs to. */
    data class Scope(val server: String, val sourceId: String)

    private val prefs by lazy { App.context.getSharedPreferences("strmr.liveHls", Context.MODE_PRIVATE) }
    private const val PREFIX = "live.hlsPreference.v1:"

    @Serializable
    private data class Identity(val serverId: String? = null)

    private var serverId: String? = null
    /** The base URL [serverId] was looked up on; another server is looked up afresh. */
    private var identityBase: HttpUrl? = null
    private var lookup: Deferred<String?>? = null
    private var lookupContext: Long? = null
    private var retryAt = 0L

    /**
     * The channel's scope. With [wait] false it answers from the cached server identity only
     * (starting the lookup), so a tune is never held up by it.
     */
    suspend fun scope(channel: LiveChannel, wait: Boolean, expectedContext: Long = ProfileDataContext.current): Scope? {
        val source = channel.sourceId?.trim()?.ifEmpty { null } ?: return null
        val (cached, pending) = ApiClient.inContext(expectedContext) { synchronized(this) {
            resetIfServerChanged()
            serverId to if (serverId == null) startLookup(expectedContext) else null
        } }
        cached?.let { return Scope(it, source) }
        val id = if (wait) pending?.await() else null
        ApiClient.inContext(expectedContext) { }
        return id?.let { Scope(it, source) }
    }

    /** True when the channel's source is remembered as needing HLS (cached identity only). */
    suspend fun prefersHls(channel: LiveChannel): Boolean = scope(channel, wait = false)?.let(::prefersHls) == true

    fun prefersHls(scope: Scope): Boolean = prefs.getString(key(scope), null) == "hls"

    /**
     * Never over a source whose direct stream has played: a direct stream that worked once and
     * then struggles is a provider having a bad moment, and the server's HLS session reads the
     * same provider.
     */
    fun remember(scope: Scope) {
        if (directWorked(scope)) return
        prefs.edit().putString(key(scope), "hls").apply()
    }

    /** A direct stream of this source played on this device. */
    fun directWorked(scope: Scope): Boolean = prefs.getString(key(scope), null) == "direct"

    fun rememberDirectWorked(scope: Scope) {
        prefs.edit().putString(key(scope), "direct").apply()
    }

    /** Forgets every remembered source; returns how many were remembered as needing HLS. */
    fun resetAll(): Int {
        val entries = prefs.all.filterKeys { it.startsWith(PREFIX) }
        prefs.edit().apply { entries.keys.forEach(::remove) }.apply()
        return entries.values.count { it == "hls" }
    }

    internal fun key(scope: Scope): String =
        PREFIX + URLEncoder.encode(scope.server, "UTF-8") + ":" + URLEncoder.encode(scope.sourceId, "UTF-8")

    private fun resetIfServerChanged() {
        val base = ApiClient.baseUrl
        if (identityBase == base) return
        identityBase = base
        serverId = null
        lookup = null
        lookupContext = null
        retryAt = 0L
    }

    /** One lookup at a time; a failed one isn't retried for five minutes. */
    private fun startLookup(expected: Long): Deferred<String?>? {
        lookup?.takeIf { lookupContext == expected }?.let { return it }
        if (SystemClock.elapsedRealtime() < retryAt) return null
        val base = identityBase
        val result = CompletableDeferred<String?>()
        lookup = result
        lookupContext = expected
        MainScope().launch {
            try {
                val id = readLiveIdentity(expected, {
                    ApiClient.send<Identity>("GET", "/api/remote-access/identity", promptOnLock = false,
                        timeoutMs = 5_000, expectedContext = expected).serverId?.ifEmpty { null }
                }) { value -> synchronized(this@LiveHlsPreference) {
                    if (identityBase == base && lookup === result) {
                        if (value != null) serverId = value else retryAt = SystemClock.elapsedRealtime() + 5 * 60_000
                        lookup = null; lookupContext = null
                    }
                } }
                result.complete(id)
            } catch (error: CancellationException) {
                synchronized(this@LiveHlsPreference) { if (lookup === result) { lookup = null; lookupContext = null } }
                result.complete(null)
                throw error
            } catch (_: Exception) {
                try { ApiClient.inContext(expected) { synchronized(this@LiveHlsPreference) {
                    if (identityBase == base && lookup === result) {
                        retryAt = SystemClock.elapsedRealtime() + 5 * 60_000
                        lookup = null; lookupContext = null
                    }
                } } } catch (_: CancellationException) { }
                result.complete(null)
            }
        }
        return result
    }
}

/** Original deferred producer, including publication; identity caching confers no content permission. */
internal suspend fun readLiveIdentity(expected: Long, read: suspend () -> String?, publish: (String?) -> Unit): String? {
    currentCoroutineContext().ensureActive(); ApiClient.inContext(expected) { }
    val value = read()
    currentCoroutineContext().ensureActive()
    ApiClient.inContext(expected) { publish(value) }
    return value
}
