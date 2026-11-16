package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.VodAccessDenial
import com.strmr.tv.api.VodDenialEvents
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.api.SkipSegment
import com.strmr.tv.api.VideoChapter
import com.strmr.tv.api.skipSegments
import com.strmr.tv.api.videoChapters
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Intro / recap / credits ranges for the playing item from `POST /api/video/segments`, which
 * merges IntroDB, SkipDB and chapter names server-side. Posted once the duration is known.
 * ExoPlayer doesn't surface container chapters, so when an episode comes back without credits
 * the file's chapters are read from the server's probe ([videoChapters]) and posted again, as
 * the Expo player posts the chapters mpv reports. On-device credits detection adds an outro
 * through [detectedCredits] when neither source has one.
 */
class SkipSegments(
    private val request: PlaybackRequest,
    private val streamPath: String?,
    private val expected: Long = ProfileDataContext.current,
    private val onDenied: (VodAccessDenial) -> Unit = {},
    private val readSegments: suspend (kotlinx.serialization.json.JsonObject, Long) -> List<SkipSegment> = { body, context -> ApiClient.skipSegments(body, context) },
    private val readChapters: suspend (String, Long) -> List<VideoChapter> = { path, context -> ApiClient.videoChapters(path, context) },
    private val log: (String) -> Unit = { AppLog.i(TAG, it) },
) {
    private var segments: List<SkipSegment> = emptyList()
    private var detected: SkipSegment? = null
    private var postedDuration = 0L
    private var job: Job? = null

    private var generation = 0L
    private var retired = false
    private fun current(ticket: Long = generation) = !retired && ticket == generation && expected == ProfileDataContext.current
    private suspend fun stageCurrent(ticket: Long): Boolean { currentCoroutineContext().ensureActive(); return current(ticket) }

    /** Whether the server's segments include credits (on-device detection then stays off). */
    val hasOutro: Boolean get() = current() && segments.any { it.type == "outro" }

    /** One request stage; denial is terminal, while ordinary unavailable metadata can fall back. */
    private suspend fun <T> read(ticket: Long, fetch: suspend () -> T): T? {
        if (!stageCurrent(ticket)) return null
        val attempt = VodDenialEvents.begin(request.title.titleId, expected)
        return try {
            val result = fetch()
            if (stageCurrent(ticket)) result else null
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            if (!stageCurrent(ticket)) return null
            error.vodDenial?.let { denial ->
                retired = true
                generation++
                segments = emptyList()
                detected = null
                VodDenialEvents.reject(attempt, denial)
                onDenied(denial)
            }
            null
        }
    }

    fun refresh(scope: CoroutineScope, durationMs: Long) {
        if (!current() || durationMs <= 0 || durationMs / 1000 == postedDuration) return
        postedDuration = durationMs / 1000
        val duration = durationMs / 1000.0
        val ticket = ++generation
        job?.cancel()
        job = scope.launch {
            val initial = read(ticket) { readSegments(body(duration, emptyList()), expected) }
            if (!stageCurrent(ticket)) return@launch
            if (initial != null) segments = initial
            val path = streamPath?.takeIf { request.isEpisode && !hasOutro } ?: return@launch
            val chapters = read(ticket) { readChapters(path, expected) }.orEmpty()
            if (!stageCurrent(ticket) || chapters.isEmpty()) return@launch
            val updated = read(ticket) { readSegments(body(duration, chapters), expected) }
            if (!stageCurrent(ticket) || updated == null) return@launch
            log("segments with ${chapters.size} chapters: ${updated.joinToString { it.type }}")
            segments = updated
        }
    }

    /** Credits found on screen at [startSec]: an outro to the end, unless the server has one. */
    fun detectedCredits(startSec: Double, durationSec: Double) {
        if (!current() || hasOutro || durationSec <= startSec) return
        detected = SkipSegment("outro", startSec, durationSec)
    }

    fun cancel() {
        retired = true
        generation++
        job?.cancel()
        segments = emptyList()
        detected = null
    }

    /**
     * The segment playback is inside, when it merits a button. Movie end credits are left
     * alone (skipping them only ends the film, and may skip a post-credits scene).
     */
    fun active(positionMs: Long): SkipSegment? {
        if (!current()) return null
        val t = positionMs / 1000.0
        val all = if (hasOutro) segments else segments + listOfNotNull(detected)
        return all.firstOrNull { t >= it.start && t < it.end - 1 && (it.type != "outro" || request.isEpisode) }
    }

    private fun body(duration: Double, chapters: List<VideoChapter>) = buildJsonObject {
        put("duration", duration)
        putJsonArray("chapters") {
            chapters.forEach { c ->
                add(buildJsonObject {
                    put("title", c.title)
                    put("start", c.start)
                    if (c.end > c.start) put("end", c.end)
                })
            }
        }
        request.title.imdbId?.let { put("imdbId", it) }
        request.episode?.let {
            put("season", it.season)
            put("episode", it.episode)
        }
    }

    private companion object {
        const val TAG = "SkipSegments"
    }
}

/** The contextual pill over the transport: a seek past a segment, or the next episode at the credits. */
sealed interface SkipAction {
    val label: String

    data class Skip(val segment: SkipSegment) : SkipAction {
        override val label = when (segment.type) {
            "intro" -> "Skip Intro"
            "recap" -> "Skip Recap"
            "outro" -> "Skip Credits"
            else -> "Skip Preview"
        }
    }

    /** `countdown` is the seconds left when it will play by itself (profile autoplay on). */
    data class NextEpisode(val next: PlaybackRequest, val countdown: Int?, val countdownPaused: Boolean = false) : SkipAction {
        override val label = if (countdownPaused) "Next Episode (countdown paused)" else countdown?.let { "Next Episode in $it" } ?: "Next Episode"
    }
}
