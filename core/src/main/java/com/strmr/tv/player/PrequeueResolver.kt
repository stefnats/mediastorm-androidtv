package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.PlaybackSettings
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.playbackSettings
import com.strmr.tv.api.prequeueStatus
import com.strmr.tv.api.startPrequeue
import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared automatic preparation for details warm-up, Up Next prefetch and Play. Main thread only. */
internal class PrequeueResolver(
    private val scope: CoroutineScope,
    private val progress: (stage: String, detail: String?) -> Unit,
    private val settings: suspend (Long) -> PlaybackSettings = { expected -> ApiClient.playbackSettings(expected) },
    private val start: suspend (JsonObject, Long) -> String = { body, expected -> ApiClient.startPrequeue(body, expected).prequeueId },
    private val status: suspend (String, Long) -> PrequeueStatus = { id, expected -> ApiClient.prequeueStatus(id, expected) },
    private val profile: () -> String = { ApiClient.profileId },
    private val client: () -> String = { Config.clientId },
    private val sourcePlan: suspend (PlaybackRequest, PlaybackSettings, Long) -> PlaybackSourcePlan = { request, playback, expected ->
        selectPlaybackSource(request, playback, expected)
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val warmValue = MutableStateFlow<WarmPrequeue?>(null)
    val warm: StateFlow<WarmPrequeue?> = warmValue
    private var warmed: Warmed? = null
    private var warmGeneration = 0L

    private class Warmed(
        val request: PlaybackRequest, val profile: String, val context: Long, val started: Long,
        var prequeue: Deferred<String?>?,
    )

    fun invalidateWarm() {
        warmGeneration++
        warmed?.prequeue?.cancel()
        warmed = null
        warmValue.value = null
    }

    fun invalidateWarmFor(titleId:String,expected:Long) {
        warmed?.takeIf {it.context==expected && it.request.title.titleId==titleId}?.let {invalidateWarm()}
    }

    fun prewarm(target: DetailsTarget) {
        if (target.fromLibrary) return
        val expected = ProfileDataContext.current
        val currentProfile = profile()
        warmed?.let {
            if (it.request.title.titleId == target.titleId && it.profile == currentProfile &&
                it.context == expected && now() - it.started < 600_000) return
        }
        warmRequest(PlaybackRequest(target), expected, currentProfile, display = true)
    }

    fun prefetch(request: PlaybackRequest) {
        if (request.libraryVersion != null) return
        warmRequest(request, ProfileDataContext.current, profile(), display = false)
    }

    private fun warmRequest(request: PlaybackRequest, expected: Long, currentProfile: String, display: Boolean) {
        val generation = ++warmGeneration
        // A replacement detaches background work; an adopted active preparation is never cancelled.
        warmed?.prequeue?.cancel()
        if (display) warmValue.value = WarmPrequeue(request.title.titleId, currentProfile, WarmPrequeue.Phase.RESOLVING, context = expected)
        else warmValue.value = null
        val task = scope.async {
            val preparation = try {
                requirePlaybackContext(expected)
                val playback = settings(expected)
                requirePlaybackContext(expected)
                if (!playback.prequeueOnOpen) null else {
                    when (val plan = sourcePlan(request, playback, expected)) {
                        is PlaybackSourcePlan.Library -> null
                        is PlaybackSourcePlan.Prequeue -> {
                            requirePlaybackContext(expected)
                            if (display && generation == warmGeneration) warmValue.value = warmValue.value?.copy(automaticLibrarySelection = plan.automaticLibrarySelection)
                            val body = prequeueRequestBody(request, plan.automaticLibrarySelection, currentProfile, client())
                            val id = start(body, expected)
                            requirePlaybackContext(expected)
                            if (generation != warmGeneration) return@async null
                            if (display) scope.launch { watchWarm(id, body, request, currentProfile, expected, generation, plan.automaticLibrarySelection) }
                            id
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { null }
            if (preparation == null && display) settleWarm(request.title.titleId, currentProfile, WarmPrequeue.Phase.UNAVAILABLE, null, generation)
            preparation
        }
        warmed = Warmed(request, currentProfile, expected, now(), task)
    }

    private suspend fun watchWarm(
        firstId: String, body: JsonObject, request: PlaybackRequest, currentProfile: String,
        expected: Long, generation: Long, automatic: Boolean,
    ) {
        var id = firstId
        var restarted = false
        val deadline = now() + 180_000
        while (generation == warmGeneration && now() < deadline) {
            try {
                requirePlaybackContext(expected)
                val reply = status(id, expected)
                requirePlaybackContext(expected)
                if (reply.automaticLibrarySelection != automatic) break
                if (reply.status == "ready") {
                    if (!prequeueMatchesEpisode(reply, request.episode, automatic)) break
                    return settleWarm(request.title.titleId, currentProfile, WarmPrequeue.Phase.READY, reply, generation)
                }
                if (reply.status == "failed" || reply.status == "expired") break
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (!isGone(error) || restarted || generation != warmGeneration) break
                restarted = true
                requirePlaybackContext(expected)
                id = try { start(body, expected)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { break }
                requirePlaybackContext(expected)
                if (generation != warmGeneration) return
                warmed?.takeIf { it.context == expected && it.request.title.titleId == request.title.titleId && it.prequeue != null }
                    ?.prequeue = CompletableDeferred(id)
            }
            delay(1500)
        }
        settleWarm(request.title.titleId, currentProfile, WarmPrequeue.Phase.UNAVAILABLE, null, generation)
    }

    private fun isGone(error: Exception): Boolean = error is ApiError && error.statusCode == 404

    private fun settleWarm(titleId: String, currentProfile: String, phase: WarmPrequeue.Phase, reply: PrequeueStatus?, generation: Long) {
        if (generation != warmGeneration) return
        val before = warmValue.value ?: return
        if (before.titleId == titleId && before.profile == currentProfile && before.phase == WarmPrequeue.Phase.RESOLVING)
            warmValue.value = before.copy(phase = phase, status = reply)
    }

    suspend fun resolve(request: PlaybackRequest, automaticLibrarySelection: Boolean, expected: Long): PrequeueStatus {
        requirePlaybackContext(expected)
        val currentProfile = profile()
        // Warm IDs remain reachable for reports even after their policy fingerprint was
        // retired. Re-POST the current request so the server, not a by-ID GET, decides reuse.
        warmed?.takeIf { it.context == expected && it.profile == currentProfile && it.request.title.titleId == request.title.titleId }
            ?.prequeue = null
        val body = prequeueRequestBody(request, automaticLibrarySelection, currentProfile, client())
        return try {
            await(start(body, expected), request.episode, automaticLibrarySelection, expected)
        } catch (error: ApiError) {
            if (!isGone(error)) throw error
            requirePlaybackContext(expected)
            await(start(body, expected), request.episode, automaticLibrarySelection, expected)
        }
    }

    private suspend fun await(id: String, episode: PlaybackRequest.EpisodeInfo?, automatic: Boolean, expected: Long): PrequeueStatus {
        val deadline = now() + 180_000
        while (now() < deadline) {
            requirePlaybackContext(expected)
            val reply = status(id, expected)
            requirePlaybackContext(expected)
            if (reply.automaticLibrarySelection != automatic) throw ApiError.NotReady("Prequeue uses a different source policy")
            when (reply.status) {
                "ready" -> {
                    if (!prequeueMatchesEpisode(reply, episode, automatic)) throw ApiError.NotReady("Prequeue is for a different episode")
                    if (reply.streamPath.isNullOrEmpty()) throw ApiError.NotReady("Stream resolved without a path")
                    return reply
                }
                "failed", "expired" -> throw ApiError.NotReady(reply.error ?: "No playable stream found")
                else -> describe(reply).let { (stage, detail) -> progress(stage, detail) }
            }
            delay(750)
        }
        throw ApiError.NotReady("Timed out waiting for a stream")
    }

    private fun describe(reply: PrequeueStatus): Pair<String, String?> {
        val detail = reply.progressDetail?.ifEmpty { null }
        return when (reply.progressStage ?: reply.status) {
            "queued", "metadata" -> "Starting…" to null
            "local_fallback" -> "Checking library…" to null
            "searching" -> "Searching…" to null
            "ranking", "preparing_candidates" -> "Ranking…" to null
            "resolving_candidate", "waiting_provider" -> "Fetching…" to detail
            "validating_candidate" -> "Checking…" to detail
            "analyzing_media", "probing", "selecting_tracks" -> "Inspecting…" to null
            "preparing_playback" -> "Preparing…" to null
            else -> "Searching…" to null
        }
    }
}

internal fun prequeueMatchesEpisode(reply: PrequeueStatus, episode: PlaybackRequest.EpisodeInfo?, automatic: Boolean): Boolean {
    if (episode == null) return true
    val actual = reply.targetEpisode ?: return false
    if (actual.seasonNumber != episode.season || actual.episodeNumber != episode.episode) return false
    if (!automatic) return true
    if (!episode.metadataEpisodeId.isNullOrEmpty() && actual.episodeId != episode.metadataEpisodeId) return false
    episode.numbering?.let { expected ->
        val owner = actual.numbering ?: return false
        if (owner.seriesId != expected.seriesId) return false
        fun ordering(value: String?) = value?.trim()?.lowercase().orEmpty().let { if (it in setOf("", "aired", "default")) "official" else it }
        if (ordering(owner.ordering) != ordering(expected.ordering)) return false
    }
    return true
}

/** The metadata leaf is distinct from progress identity; false leaves the legacy body unchanged. */
internal fun prequeueRequestBody(request: PlaybackRequest, automatic: Boolean, profile: String, client: String): JsonObject = buildJsonObject {
    val title = request.title
    put("titleId", title.titleId)
    put("titleName", title.name)
    put("mediaType", if (title.isMovie) "movie" else "series")
    put("userId", profile)
    put("clientId", client)
    put("reason", "details")
    put("skipHLS", true)
    if (automatic) put("automaticLibrarySelection", true)
    title.imdbId?.let { put("imdbId", it) }
    title.tmdbId?.let { put("tmdbId", it) }
    title.tvdbId?.let { put("tvdbId", it) }
    title.year?.let { put("year", it) }
    request.episode?.let { episode ->
        put("seasonNumber", episode.season)
        put("episodeNumber", episode.episode)
        episode.absolute?.takeIf { it > 0 }?.let { put("absoluteEpisodeNumber", it) }
        if (automatic) episode.metadataEpisodeId?.takeIf { it.isNotEmpty() }?.let { put("episodeId", it) }
        episode.numbering?.let { numbering ->
            put("numbering", buildJsonObject {
                put("seriesId", numbering.seriesId)
                numbering.ordering?.takeIf { it.isNotEmpty() }?.let { put("ordering", it) }
            })
        }
    }
    request.startPosition?.let { put("startOffset", it) }
}
