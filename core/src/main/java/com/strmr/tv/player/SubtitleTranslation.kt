package com.strmr.tv.player

import com.strmr.tv.api.rethrowVodDenial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Translating one embedded text track, for the automatic search and for Translate…: the
 * server extracts it to WebVTT (`/api/video/subtitles/start`, from the current position),
 * `/api/subtitles/translate` translates it a little more on every request, and the result is
 * shown as the external track once it has a few cues, then refreshed while it grows.
 */
internal class SubtitleTranslation(
    private val online: OnlineSubtitles,
    private val controller: PlayerController,
    private val area: String,
    private val prefix: String,
    private val ticket: SubtitlePlaybackAccess.Run,
) {
    private fun log(event: String, details: String = "") = online.log(area, "$prefix$event", details)

    /**
     * Translates subtitle stream `track` (its position among the file's subtitle streams) from
     * `sourceLanguage` to `targetLanguage`; true once the translation is on screen.
     */
    suspend fun run(track: Int, sourceLanguage: String, targetLanguage: String): Boolean {
        if (!online.current(ticket)) throw CancellationException("Subtitle operation retired")
        if (controller.session.streamPath == null) return false
        val offset = maxOf(0.0, controller.player.currentPosition / 1000.0, controller.request.startPosition ?: 0.0)
        val source = try {
            online.extract(track, offset, ticket).subtitleUrl.ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowVodDenial()
            log("source_extract_failed", "track=$track")
            null
        }
        if (source == null) {
            log("source_missing", "track=$track")
            return false
        }
        log("source_extracted", "track=$track offset=$offset from=$sourceLanguage to=$targetLanguage")
        online.setStatus("Translating subtitles…", run = ticket)
        val fetch: suspend () -> String = { online.translated(source, targetLanguage, sourceLanguage, ticket) }
        val body = online.waitForCues(area, "translation", attempts = 30, delayMs = 2_000,
                                      minCues = 4, minBytes = 1_200, acceptPartial = true, run = ticket, fetch = fetch)
        val cues = body?.let { online.parse(it, ticket) }.orEmpty()
        if (body == null || cues.isEmpty()) {
            log("translation_no_cues")
            return false
        }
        val title = "Translated: ${SubtitleLanguages.displayName(targetLanguage)}"
        online.show(ExternalTrack(title, "From ${SubtitleLanguages.displayName(sourceLanguage)}", cues), ticket)
        log("translation_ready", "cues=${cues.size}")
        online.setStatus("Translated subtitles ready", 2_000, run = ticket)
        keepTranslating(body, cues.size, fetch)
        return true
    }

    /**
     * Re-fetches every 10 s (after a first 10 s) and refreshes the cues while the translation
     * grows; stops after a minute without change, or when another external track replaces it.
     */
    private fun keepTranslating(first: String, firstCount: Int, fetch: suspend () -> String) {
        var shown = controller.external.track.value ?: return
        online.launchRefresh(ticket) {
            var lastLength = first.length
            var lastCount = firstCount
            var unchanged = 0
            delay(10_000)
            while (unchanged < 6 && online.current(ticket)) {
                if (controller.external.track.value !== shown) return@launchRefresh
                val body = try { fetch() } catch (error: CancellationException) { throw error
                } catch (error: Exception) { error.rethrowVodDenial(); null }
                if (body != null) {
                    val count = WebVtt.countCues(body)
                    if (count > lastCount || body.length - lastLength > 50) {
                        val cues = online.parse(body, ticket)
                        shown = online.update(shown, cues, ticket) ?: return@launchRefresh
                        lastCount = count
                        lastLength = body.length
                        unchanged = 0
                    } else unchanged++
                }
                delay(10_000)
            }
        }
    }
}
