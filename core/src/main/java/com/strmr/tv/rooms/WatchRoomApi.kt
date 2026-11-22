package com.strmr.tv.rooms

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.HttpFailure
import com.strmr.tv.api.RequestContext
import com.strmr.tv.api.withoutAutomaticReplay
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** One original viewer, not an authorizer supplied by the room or another member. */
interface WatchRoomGateway {
    val profileId: String
    val clientId: String
    fun current(): Boolean
    suspend fun inbox(): List<WatchRoom>
    suspend fun accountInbox(): List<WatchRoomAccountInvite>
    suspend fun create(body: JsonObject): WatchRoom
    suspend fun read(id: String): WatchRoom
    suspend fun join(id: String): WatchRoom
    suspend fun ready(id: String, value: Boolean): WatchRoom
    suspend fun state(id: String, status: String, position: Double, duration: Double, revision: Long): WatchRoom
    suspend fun heartbeat(id: String, buffering: Boolean)
    suspend fun leave(id: String)
    suspend fun end(id: String)
    suspend fun invite(id: String, username: String): WatchRoomAccountInvite
    suspend fun outgoing(id: String): List<WatchRoomAccountInvite>
    suspend fun accept(id: String): WatchRoom
    suspend fun decline(id: String)
    suspend fun revoke(room: String, invite: String)
    /** Only a known admitted original membership may be cleaned up after UI departure. */
    suspend fun cleanupKnown(id: String, abandonCreated: Boolean = false)
}

/** Captured by ApiClient under its private context lock; no auth refresh or route replay. */
internal class WatchRoomTransport(
    private val context: RequestContext.Scope,
    private val base: HttpUrl,
    private val headers: Map<String, String>,
    override val profileId: String,
    override val clientId: String,
    client: OkHttpClient,
) : WatchRoomGateway {
    private val http = client.withoutAutomaticReplay().newBuilder()
        .followRedirects(false).followSslRedirects(false).build()
    private var knownCleanupId: String? = null
    private var knownCreated = false
    override fun current() = runCatching { context.check(); true }.getOrDefault(false)
    private fun segment(value: String) = ApiClient.segment(value.also { require(it.isNotBlank() && it.length <= 256) })
    private val profilePath = "/api/users/" + segment(profileId)
    private fun room(id: String) = profilePath + "/watch-rooms/" + segment(id)
    private suspend fun raw(method: String, path: String, body: JsonObject? = null, cleanup: Boolean = false): String {
        if (!cleanup) context.check()
        else {
            val id = requireNotNull(knownCleanupId) { "No original membership was confirmed." }
            require(method == "POST" && body == null &&
                (path == room(id) + "/leave" || knownCreated && path == room(id) + "/end"))
        }
        val request = Request.Builder().url(base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + path).query(null).fragment(null).build())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, if (method in setOf("POST", "PUT")) (body?.toString() ?: "{}")
                .toRequestBody("application/json".toMediaType()) else null).build()
        val result = http.newCall(request).apply { timeout().timeout(30, TimeUnit.SECONDS) }.roomReply()
        // A closed UI still observes this bounded response in its own admitted task.
        // Publication and any new cleanup request must retain this exact original context.
        if (result.first !in 200..299) throw ApiError.Http(HttpFailure(result.first, result.second))
        return result.second
    }
    private suspend inline fun <reified T> data(method: String, path: String, body: JsonObject? = null): T =
        ApiJson.decodeFromString<T>(raw(method, path, body))
    private fun checked(value: WatchRoom) = value.checked()
    override suspend fun inbox() = data<List<WatchRoom>>("GET", profilePath + "/watch-rooms/invitations")
        .also { require(it.size <= 256); it.forEach { room -> room.checked() } }
    override suspend fun accountInbox() = data<List<WatchRoomAccountInvite>>("GET", "/api/watch-room-account-invitations")
        .also { require(it.size <= 256) }
    override suspend fun create(body: JsonObject) = remember(checked(data("POST", profilePath + "/watch-rooms", body)), true)
    override suspend fun read(id: String) = checked(data("GET", room(id)))
    override suspend fun join(id: String) = remember(checked(data("POST", room(id) + "/join", buildJsonObject {
        put("clientId", clientId); put("capabilities", WatchRoomTarget.capabilities())
    })), false)
    private fun remember(value: WatchRoom, created: Boolean): WatchRoom {
        require(value.member(profileId, clientId) != null && (!created || value.creatorProfileId == profileId))
        knownCleanupId = value.id
        knownCreated = created
        return value
    }
    override suspend fun ready(id: String, value: Boolean) = checked(data("POST", room(id) + "/ready", buildJsonObject { put("ready", value) }))
    override suspend fun state(id: String, status: String, position: Double, duration: Double, revision: Long): WatchRoom {
        require(status in setOf("playing", "paused") && revision >= 0)
        roomMilliseconds(position); roomMilliseconds(duration)
        return checked(data("POST", room(id) + "/state", buildJsonObject {
            put("status", status); put("position", position); put("duration", duration); put("expectedRevision", revision)
        }))
    }
    override suspend fun heartbeat(id: String, buffering: Boolean) { raw("POST", room(id) + "/heartbeat", buildJsonObject { put("clientId", clientId); put("buffering", buffering) }) }
    override suspend fun leave(id: String) { raw("POST", room(id) + "/leave") }
    override suspend fun end(id: String) { raw("POST", room(id) + "/end") }
    override suspend fun invite(id: String, username: String) = data<WatchRoomAccountInvite>("POST", room(id) + "/account-invites", buildJsonObject { put("username", username) })
    override suspend fun outgoing(id: String) = data<List<WatchRoomAccountInvite>>("GET", room(id) + "/account-invites").also { require(it.size <= 256) }
    override suspend fun accept(id: String) = checked(data("POST", profilePath + "/watch-room-account-invitations/" + segment(id) + "/accept"))
    override suspend fun decline(id: String) { raw("POST", "/api/watch-room-account-invitations/" + segment(id) + "/decline") }
    override suspend fun revoke(room: String, invite: String) { raw("DELETE", room(room) + "/account-invites/" + segment(invite)) }
    override suspend fun cleanupKnown(id: String, abandonCreated: Boolean) {
        require(id == knownCleanupId) { "No original membership was confirmed." }
        require(!abandonCreated || knownCreated)
        // Only immutable original headers/URL, no current viewer credentials or refresh.
        raw("POST", room(id) + if (abandonCreated) "/end" else "/leave", cleanup = true)
        if (knownCleanupId == id) { knownCleanupId = null; knownCreated = false }
    }
    override fun toString() = "WatchRoomTransport(original viewer)"
}

/** Body bytes are bounded while reading, before allocation by the JSON parser. */
private suspend fun Call.roomReply(): Pair<Int, String> {
    val done = CompletableDeferred<Unit>()
    try { return suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    if (!continuation.isActive) { done.complete(Unit); return@suspendCancellableCoroutine }
    try { enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            try { if (continuation.isActive) continuation.resumeWithException(e) } finally { done.complete(Unit) }
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    val buffer = Buffer()
                    val source = it.body?.source()
                    if (source != null) while (source.read(buffer, minOf(8192L, 1048577L - buffer.size)) != -1L) {
                        check(buffer.size <= 1048576) { "Room reply is too large." }
                    }
                    it.code to buffer.readUtf8()
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            finally { done.complete(Unit) }
        }
    }) } catch (e: Exception) { done.complete(Unit); if (continuation.isActive) continuation.resumeWithException(e) }
    } } finally { withContext(NonCancellable) { done.await() } }
}
