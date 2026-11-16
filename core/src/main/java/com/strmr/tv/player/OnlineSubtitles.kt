package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.strmr.tv.api.*
import com.strmr.tv.app.AppLog
import java.util.IdentityHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Original playback identity and operation ordering; not a content permission or persisted grant. */
internal class SubtitlePlaybackAccess(
    private val owner: PlaybackSessionContext,
    private val titleId: String,
    private val playerCurrent: () -> Boolean = { true },
    private val onDenied: (VodAccessDenial) -> Unit = {},
) {
    class Run internal constructor()
    private class Search(val run: Run, val language: String)
    private var run: Run? = null
    private var closed = false
    private var refreshJob: Job? = null
    private val results = IdentityHashMap<SubtitleSearchResult, Search>()
    fun current(ticket: Run? = null) = !closed && owner.isCurrent() && playerCurrent() && (ticket == null || ticket === run)
    fun check(ticket: Run? = null) {
        owner.requireCurrent()
        if (!current(ticket)) throw CancellationException("Subtitle operation retired")
    }
    fun <T> use(ticket: Run? = null, action: () -> T): T = owner.use { check(ticket); action() }
    fun begin(): Run? {
        if (!current()) return null
        invalidate()
        return Run().also { run = it }
    }
    fun invalidate() { run = null; results.clear(); refreshJob?.cancel(); refreshJob = null }
    fun close() { closed = true; invalidate() }
    fun launchRefresh(scope: CoroutineScope, ticket: Run, work: suspend () -> Unit): Job = use(ticket) {
        refreshJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { check(ticket); work() }
        refreshJob = job
        job.start()
        job
    }
    suspend fun <T> guarded(ticket: Run? = null, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); check(ticket)
        val value = action()
        currentCoroutineContext().ensureActive(); check(ticket)
        return value
    }
    suspend fun <T> request(ticket: Run? = null, fetch: suspend (Long) -> T): T {
        currentCoroutineContext().ensureActive(); check(ticket)
        val attempt = VodDenialEvents.begin(titleId, owner.epoch)
        return try { guarded(ticket) { fetch(owner.epoch) }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive(); check(ticket)
            error.vodDenial?.let { denial ->
                close()
                VodDenialEvents.reject(attempt, denial)
                onDenied(denial)
                // UI search-language effects have no generic error handler. Retire them safely.
                throw CancellationException("Subtitle access denied").apply { initCause(error) }
            }
            throw error
        }
    }
    fun <T : Any> updateExpected(ticket: Run, expected: T, current: () -> T?, update: () -> T?): T? = use(ticket) {
        if (current() !== expected) null else update()
    }
    fun rememberSearch(ticket: Run, language: String, rows: List<SubtitleSearchResult>): List<SubtitleSearchResult> = use(ticket) {
        results.clear(); rows.forEach { results[it] = Search(ticket, language) }; rows
    }
    fun chooseSearch(result: SubtitleSearchResult, language: String): Run? {
        if (!current()) return null
        val binding = results[result] ?: return null
        if (!current(binding.run) || binding.language != language) return null
        return begin()
    }
}

/** One player's search, download and translation; every asynchronous stage keeps its original owner. */
class OnlineSubtitles(private val controller: PlayerController, private val scope: CoroutineScope) {
    private val session = controller.session
    private val access = SubtitlePlaybackAccess(requireNotNull(session.ownership), session.request.title.titleId,
        controller::canUsePlayerAux, { controller.denyVod(it) })
    val available: Boolean get() = access.current() && !session.isLive && session.reportsProgress && session.streamPath != null
    val releaseName: String? = session.releaseName
    val query = session.request.let { r -> SubtitleQuery(r.title.imdbId, r.title.name, releaseName, r.title.year, r.episode?.season, r.episode?.episode) }
    val status = MutableStateFlow<String?>(null)
    val serverTranslation = MutableStateFlow<Boolean?>(null)
    private var checked = false
    private var translateJob: Job? = null
    private var downloadJob: Job? = null
    private var autoJob: Job? = null
    private var statusJob: Job? = null
    private var runs = 0
    private var serverSettings: SubtitleServerSettings? = null
    private var playback: PlaybackSettings? = null

    init {
        if (available) scope.launch {
            try { server() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
    }
    fun log(area: String, event: String, details: String = "") {
        if (!access.current()) return
        val safe = if (details.contains("error=")) details.substringBefore("error=") + "error=unavailable" else details
        AppLog.i(area, if (safe.isEmpty()) event else "$event $safe")
    }
    internal fun current(run: SubtitlePlaybackAccess.Run) = access.current(run)
    internal fun launchRefresh(run: SubtitlePlaybackAccess.Run, work: suspend () -> Unit) = access.launchRefresh(scope, run, work)
    internal fun setStatus(message: String?, clearAfterMs: Long = 0, run: SubtitlePlaybackAccess.Run? = null) {
        if (!access.current(run)) return
        access.use(run) {
            statusJob?.cancel(); status.value = message
            if (message != null && clearAfterMs > 0) statusJob = scope.launch {
                delay(clearAfterMs); access.use(run) { status.value = null }
            }
        }
    }
    internal suspend fun server(run: SubtitlePlaybackAccess.Run? = null): SubtitleServerSettings {
        access.check(run)
        val value = serverSettings ?: access.request(run) { ApiClient.subtitleServerSettings(it) }.also { result ->
            access.use(run) { serverSettings = result; serverTranslation.value = result.translationEnabled }
        }
        return access.use(run) { value.copy(translationEnabled = value.translationEnabled && TrackOverrides.translation != false) }
    }
    private suspend fun playbackSettings(run: SubtitlePlaybackAccess.Run? = null): PlaybackSettings {
        access.check(run)
        return playback ?: session.playback ?: access.request(run) { ApiClient.playbackSettings(it) }.also { result -> access.use(run) { playback = result } }
    }
    private suspend fun preference(run: SubtitlePlaybackAccess.Run? = null): Pair<String?, String?> {
        access.check(run)
        val effective = controller.effectiveTracks
        if (session.tracks != null || session.playback != null) return access.use(run) { effective.subtitleLanguage to effective.subtitleMode }
        val settings = playbackSettings(run)
        return access.use(run) { (TrackOverrides.subtitleLanguage ?: settings.preferredSubtitleLanguage) to (TrackOverrides.subtitleMode ?: settings.preferredSubtitleMode) }
    }
    fun tracksReady(tracks: Tracks, pick: PlayerTrack?) {
        if (checked || !available) return
        checked = true
        val run = access.begin() ?: return
        autoJob = scope.launch {
            val (language, preferredMode) = preference(run)
            val playing = tracks.ofType(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.format?.language
            val mode = ClientTrackSelector.resolveMode(preferredMode, playing, language)
            if (mode != "on" || language == null) return@launch
            val code = SubtitleLanguages.canonical(language) ?: language
            val text = textTracks(tracks)
            if (pick?.let { TrackPreferences.sameLanguage(it.format.language, code) } == true || text.any { !it.format.isForcedSubtitle && TrackPreferences.sameLanguage(it.format.language, code) }) return@launch
            if (text.isNotEmpty() && text.none { it.format.knownLanguage != null }) return@launch
            val settings = server(run)
            if (!settings.providerConfigured && !(settings.translationEnabled && !SubtitleLanguages.isEnglish(language))) return@launch
            access.check(run)
            runs++
            AutoSubtitleRun(this@OnlineSubtitles, controller, "auto-$runs", language, settings, run).run()
        }
    }
    private fun stopJobs() { autoJob?.cancel(); translateJob?.cancel(); downloadJob?.cancel() }
    fun userChose() { access.invalidate(); stopJobs() }

    /** Both platform search effects call this instance; the effect's scope cannot change its owner. */
    suspend fun searchLanguages(): Pair<List<SubtitleLanguages.Option>, String> {
        val settings = playbackSettings()
        val preferred = preference().first?.let { SubtitleLanguages.searchCode(it) }
        return access.use {
            val options = SubtitleLanguages.allowed(settings.allowedTrackLanguages).ifEmpty { SubtitleLanguages.options }
            options to (options.firstOrNull { it.code == preferred }?.code ?: options.first().code)
        }
    }
    suspend fun search(language: String): List<SubtitleSearchResult> {
        val run = access.begin() ?: throw CancellationException("Subtitle operation retired")
        stopJobs()
        val rows = searchForRun(language, run)
        return access.rememberSearch(run, language, SubtitleMatching.sortForDisplay(rows, releaseName))
    }
    internal suspend fun searchForRun(language: String, run: SubtitlePlaybackAccess.Run): List<SubtitleSearchResult> =
        access.request(run) { ApiClient.searchSubtitles(query, language, it) }

    fun use(result: SubtitleSearchResult, language: String) {
        val run = access.chooseSearch(result, language) ?: return
        stopJobs()
        setStatus("Downloading subtitles…", run = run)
        downloadJob = scope.launch {
            val cues = download(result, language, SEARCH_TAG, "manual-download", run)
            if (cues == null) { setStatus("No usable subtitles found", 3_000, run); return@launch }
            show(ExternalTrack("Online: ${SubtitleLanguages.displayName(language)}", result.release.ifEmpty { result.provider }, cues), run)
            setStatus("Subtitles ready", 2_000, run)
        }
    }
    internal suspend fun download(result: SubtitleSearchResult, language: String, area: String, phase: String, run: SubtitlePlaybackAccess.Run): List<VttCue>? {
        val body = waitForCues(area, phase, 8, 200, run = run) { access.request(run) { ApiClient.downloadSubtitle(result, query, language, it) } }
        return body?.let { parse(it, run) }?.takeIf { it.isNotEmpty() }
    }
    internal suspend fun waitForCues(area: String, phase: String, attempts: Int, delayMs: Long, minCues: Int = 1, minBytes: Int = 0, acceptPartial: Boolean = true,
        run: SubtitlePlaybackAccess.Run, fetch: suspend () -> String): String? {
        var best: String? = null; var bestCount = 0
        for (attempt in 1..attempts) {
            val body = try { access.guarded(run) { fetch() }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { error.rethrowVodDenial(); return null }
            val count = WebVtt.countCues(body)
            if (count > 0 && (count >= minCues || body.length >= minBytes)) return body
            if (count >= bestCount && count > 0) { best = body; bestCount = count }
            if (attempt < attempts) { delay(delayMs); access.check(run) }
        }
        return if (acceptPartial) best else null
    }
    internal suspend fun parse(body: String, run: SubtitlePlaybackAccess.Run): List<VttCue> = access.guarded(run) { withContext(Dispatchers.Default) { WebVtt.parse(body) } }
    internal suspend fun extract(track: Int, offset: Double, run: SubtitlePlaybackAccess.Run): SubtitleExtractSession =
        access.request(run) { ApiClient.startSubtitleExtract(requireNotNull(session.streamPath), track, offset, it) }
    internal suspend fun translated(source: String, target: String, language: String, run: SubtitlePlaybackAccess.Run): String =
        access.request(run) { ApiClient.translateSubtitle(source, target, language, it) }
    internal fun show(track: ExternalTrack, run: SubtitlePlaybackAccess.Run) = access.use(run) { controller.showExternal(track) }
    internal fun update(expected: ExternalTrack, cues: List<VttCue>, run: SubtitlePlaybackAccess.Run): ExternalTrack? =
        access.updateExpected(run, expected, { controller.external.track.value }) {
            controller.external.updateCues(cues); controller.external.track.value
        }
    internal fun select(track: PlayerTrack, run: SubtitlePlaybackAccess.Run) = access.use(run) { controller.select(track, byUser = false) }

    fun translatableTracks(tracks: Tracks) = textTracks(tracks).filter { it.supported && !it.format.isBitmapSubtitle && it.format.subtitleCandidate().codec != "dvd_subtitle" }
    fun translate(track: PlayerTrack, target: String) {
        if (!available || textTracks(controller.tracks.value).none { it.group === track.group && it.index == track.index }) return
        val run = access.begin() ?: return
        stopJobs()
        val streams = session.subtitleStreams
        val id = track.format.id?.toIntOrNull()
        val text = textTracks(controller.tracks.value)
        val index = TranslationSource.extractIndex(id, streams, text.indexOfFirst { it.group == track.group && it.index == track.index })
        val label = listOfNotNull(track.format.menuTitle(""), track.format.label).joinToString(" ")
        val source = TranslationSource.language(TranslationSource.stream(id, streams)?.language?.ifEmpty { null } ?: track.format.knownLanguage, label)
        setStatus("Translating subtitles…", run = run)
        translateJob = scope.launch {
            val shown = try { SubtitleTranslation(this@OnlineSubtitles, controller, TRANSLATE_TAG, "", run).run(index, source, target)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { error.rethrowVodDenial(); false }
            if (!shown) setStatus("Translation failed", 3_000, run)
        }
    }
    fun cancel() { access.close(); stopJobs(); statusJob?.cancel(); status.value = null; serverTranslation.value = null }
    companion object {
        const val TAG = "AutoSub"
        const val SEARCH_TAG = "SubSearch"
        const val TRANSLATE_TAG = "SubTranslate"
        fun textTracks(tracks: Tracks) = tracks.ofType(C.TRACK_TYPE_TEXT).filter { !TrackPreferences.isClosedCaption(it.format) }
    }
}

val Format.isForcedSubtitle: Boolean
    get() = selectionFlags and C.SELECTION_FLAG_FORCED != 0 || label?.contains("forced", ignoreCase = true) == true

val Format.knownLanguage: String?
    get() = language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED && it != "und" }

val Format.isBitmapSubtitle: Boolean
    get() = subtitleMime in setOf("application/pgs", "application/vobsub", "application/dvbsubs")
