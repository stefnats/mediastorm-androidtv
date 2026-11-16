package com.strmr.tv.player

import android.content.Intent
import com.strmr.tv.adaptive.AdaptiveThroughput
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.PlaybackSettings
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.SourceResult
import com.strmr.tv.api.TrackSelection
import com.strmr.tv.api.playbackSettings
import com.strmr.tv.api.resolveSource
import com.strmr.tv.api.rethrowVodDenial
import com.strmr.tv.api.VodDenialEvents
import com.strmr.tv.api.vodDenial
import com.strmr.tv.api.streamUrl
import com.strmr.tv.app.App
import com.strmr.tv.app.AppLog
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.EpgProgram
import com.strmr.tv.live.LiveChannel
import com.strmr.tv.live.LiveHlsPreference
import com.strmr.tv.live.LiveStream
import com.strmr.tv.live.LivePlaybackAttempt
import com.strmr.tv.live.RecordingInterruptionConsent
import com.strmr.tv.live.Recording
import com.strmr.tv.live.RecordingRules
import com.strmr.tv.live.RecordingCaptureRules
import com.strmr.tv.live.RecordingPart
import com.strmr.tv.live.recordingStreamUrl
import com.strmr.tv.live.startRecordingHls
import com.strmr.tv.live.startLive
import com.strmr.tv.live.catchupStreamUrl
import com.strmr.tv.live.liveCatchup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves a source through the backend prequeue (or a hand-picked source through resolve),
 * then opens [PlayerActivity] on the stream. Main thread only.
 */
object PlaybackCoordinator {
    /** The request being resolved; drives the stream-start screen until the player is up. */
    private val _resolving = MutableStateFlow<PlaybackRequest?>(null)
    val resolving: StateFlow<PlaybackRequest?> = _resolving
    val stage = MutableStateFlow("Starting…")
    /** The release being tried, when the server names one. */
    val detail = MutableStateFlow<String?>(null)
    val errorMessage = MutableStateFlow<String?>(null)
    val recordingInterruption = RecordingInterruptionConsent()
    /** Bumped whenever a playback session ends, so screens can refresh progress. */
    val sessionsEnded = MutableStateFlow(0)
    /** The player is on screen (log uploads hold off). */
    private val _playerOpen = MutableStateFlow(false)
    val playerVisibility: StateFlow<Boolean> = _playerOpen
    var playerOpen: Boolean
        get() = _playerOpen.value
        set(value) { _playerOpen.value = value }

    /**
     * Offered every resolved title stream before the player opens; true takes it (the phone's
     * Cast to Device… plays the resolve on a TV instead, without opening the player).
     */
    @Volatile var streamClaim: ((PlaybackRequest, ResolvedStream) -> Boolean)? = null

    /** The session the player picks up when it starts. */
    @Volatile var pending: PlayerSession? = null
        private set

    private val scope = MainScope()
    private var resolveJob: Job? = null
    private val prequeue = PrequeueResolver(scope, progress = { s, d -> stage.value = s; detail.value = d })
    private val catalog = CatalogPlaybackResolver(prequeue::resolve)
    /** The details page's prequeue as it resolves, for its Audio & Subtitles button. */
    val warm: StateFlow<WarmPrequeue?> get() = prequeue.warm

    /** Automatic catalog playback shares its selection with Up Next and prefetch. */
    fun play(request: PlaybackRequest, picks: PrequeuePick? = null,expectedContext:Long=ProfileDataContext.current, chooseLegacyLibrary: (() -> Unit)? = null) {
        val expected = expectedContext
        if(expected!=ProfileDataContext.current)return
        begin(request, "Searching…", expectedContext = expected,
            resolveWithSettings = { settings -> catalog.resolve(request, settings, picks, expected, chooseLegacyLibrary) })
    }

    /**
     * Plays a source picked by hand, bypassing the automatic prequeue. [manual] carries the
     * tracks chosen in its track panel; they replace the server's choice for those kinds.
     * [carried] (Switch Source) re-applies the languages that were playing; [resolved] skips the
     * resolve (Auto Switch resolved it already).
     */
    fun play(
        request: PlaybackRequest, source: SourceResult, manual: ManualSourceTracks? = null, carried: CarriedTracks? = null,
        resolved: Pair<String, TrackSelection?>? = null,
        prepared: com.strmr.tv.api.PrequeueStatus? = null,expectedContext:Long=ProfileDataContext.current,
    ) {
        val expected=expectedContext
        if(expected!=ProfileDataContext.current)return
        // A stream picked over a library copy (Switch Source) is no longer that copy.
        begin(request.copy(libraryVersion = null), "Preparing…", carried = carried, expectedContext=expected, resolve = {
            if (prepared != null) {
                return@begin ResolvedStream(requireNotNull(prepared.streamPath), prepared.trackSelection, prepared.displayName,
                    prepared.subtitleTracks.orEmpty(), manualTracks = true, source = prequeueSourceHints(prepared), prequeueId = prepared.prequeueId)
            }
            val (path, tracks) = resolved ?: ApiClient.resolveSource(source, request.title.titleId, request.startPosition,expected)
            val hints = SourceHints(source.serviceType.ifEmpty { null }, indexer = source.indexer.ifEmpty { null },
                                    attributes = source.attributes)
            if (manual == null || manual.choice.isEmpty) {
                ResolvedStream(path, tracks, source.title, source = hints)
            } else {
                val applied = ManualTracks.applied(tracks, manual.choice, manual.audio, manual.subtitles)
                AppLog.i("ManualTracks", "play with ${manual.choice}: audio=${applied.audio?.streamIndex} " +
                    "subtitle=${applied.subtitle?.streamIndex} mode=${applied.subtitleMode} (server audio=${tracks?.audio?.streamIndex} " +
                    "subtitle=${tracks?.subtitle?.streamIndex})")
                ResolvedStream(path, applied, source.title, manualTracks = true, source = hints, explicitAudioLanguage = manual.explicitAudioLanguage)
            }
        })
    }

    /** A trailer full screen, in the same player, without progress. */
    fun playTrailer(url: String, name: String,expectedContext:Long=ProfileDataContext.current) {
        if (_resolving.value != null) return
        val ownership=PlaybackSessionContext.capture(expectedContext)
        pending = PlayerSession(PlaybackRequest(DetailsTarget("trailer", name, isMovie = true)), url, null, reportsProgress = false,ownership=ownership)
        launchPlayer()
    }

    /**
     * A live channel: the server tunes it (a direct stream or an HLS session) and the player plays
     * it. It asks for timeshift, so with the server's window on (30 minutes by default) the channel
     * plays from an HLS session that can be paused, rewound and returned to live.
     */
    fun playLive(channel: LiveChannel, programTitle: String?, expectedContext: Long = ProfileDataContext.current) {
        if (expectedContext != ProfileDataContext.current || _resolving.value != null) return
        val request = PlaybackRequest(DetailsTarget(channel.id, programTitle ?: channel.name, isMovie = true, backdropUrl = null, logoUrl = channel.logoUrl))
        val attempt = LivePlaybackAttempt(requireCurrent = { ProfileDataContext.requireCurrent(expectedContext) })
        // A source remembered as needing HLS asks for it from the first tune (LiveHlsPreference).
        // Stremio failover tunes another of the channel's streams ([LiveRetune]).
        val live: suspend (Boolean, Int?) -> LiveStream = { force, stremioIndex ->
            val tuned = stremioIndex?.let { channel.copy(stremioStreamIndex = it) } ?: channel
            attempt.open(confirm = { recordingInterruption.ask(it, expectedContext) }) { id, confirmation, retry ->
                ApiClient.startLive(tuned, programTitle, forceHls = force || LiveHlsPreference.prefersHls(channel), timeshift = true,
                    expectedContext = expectedContext, playAttemptId = id, recordingConfirmation = confirmation, automaticRetry = retry)
            }
        }
        begin(request, "Tuning…", live = live, liveChannel = channel, expectedContext = expectedContext,
              liveLabel = StreamInfo.live(channel.name))
    }

    /**
     * A Live TV recording, seekable, without progress (as Expo plays it): a finished one's
     * file, or one still recording over the server's growing HLS session from the start
     * ([RecordingHls]).
     */
    fun playRecording(recording: Recording, expectedContext:Long=ProfileDataContext.current) {
        if (expectedContext!=ProfileDataContext.current || _resolving.value != null || !RecordingRules.canPlay(recording)) return
        RecordingCaptureRules.firstClosed(recording)?.let {part ->
            playRecordingPart(recording,part,expectedContext);return
        }
        if(RecordingCaptureRules.known(recording)?.parts?.size?.let {it>1}==true)return
        val title = RecordingRules.title(recording)
        val request = PlaybackRequest(DetailsTarget(recording.id, title, isMovie = true))
        AppLog.i("Recordings", "play ${recording.id} status=${recording.status}")
        if (RecordingRules.isRecording(recording)) {
            begin(request, "Starting…", live = { _, _ -> ApiClient.startRecordingHls(recording, title,expectedContext) }, growing = true, expectedContext=expectedContext,
                  liveLabel = StreamInfo.recording(recording.channelName))
            return
        }
        ApiClient.inContext(expectedContext) {
            pending = PlayerSession(request, ApiClient.recordingStreamUrl(recording, title,expectedContext), null, reportsProgress = false,
                                    sourceLabel = StreamInfo.recording(recording.channelName),ownership=PlaybackSessionContext.capture(expectedContext))
            launchPlayer()
        }
    }

    fun playRecordingPart(recording:Recording,part:RecordingPart,expectedContext:Long) {
        if(expectedContext!=ProfileDataContext.current || _resolving.value!=null || !RecordingCaptureRules.playable(recording,part))return
        ApiClient.inContext(expectedContext) {
            val title="${RecordingRules.title(recording)} · Part ${part.index}"
            pending=recordingPartSession(recording,part,ApiClient.recordingStreamUrl(recording,title,expectedContext,part),PlaybackSessionContext.capture(expectedContext))
            launchPlayer()
        }
    }

    /**
     * A past (or airing) guide programme from the provider's catch-up, seekable, without progress
     * (as recordings play): the server turns it into the provider's archive URL, which plays as a
     * file through `/api/video/stream` (providers serve the archive whole, with byte ranges).
     */
    fun playCatchup(channel: LiveChannel, program: EpgProgram) {
        if (_resolving.value != null) return
        val ownership=PlaybackSessionContext.capture()
        val request = PlaybackRequest(DetailsTarget("catchup:${channel.id}:${program.start}", program.title, isMovie = true))
        AppLog.i("Catchup", "play ${channel.id} ${program.start} ${program.title}")
        _resolving.value = request
        stage.value = "Starting…"
        detail.value = null
        resolveJob = scope.launch {
            try {
                ownership.requireCurrent()
                val archive = ApiClient.liveCatchup(channel, program,expectedContext=ownership.epoch)
                ownership.requireCurrent()
                AppLog.i("Catchup", "archive from ${archive.offsetSeconds}s for ${archive.durationSeconds}s")
                pending = PlayerSession(request, ApiClient.catchupStreamUrl(archive, channel, program, ownership.epoch), null, reportsProgress = false,
                                        sourceLabel = listOf("Catch-up", channel.name).joinToString(" · "),ownership=ownership)
                launchPlayer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _resolving.value = null
                errorMessage.value = e.message ?: "Catch-up failed"
            }
        }
    }

    /**
     * A ready session that needs no resolve (a phone's downloaded file): straight to the player,
     * unless another playback is resolving.
     */
    fun playSession(session: PlayerSession) {
        if (_resolving.value != null || !session.canOpenPlayer()) return
        pending = session
        launchPlayer()
    }

    private fun launchPlayer() {
        AdaptiveThroughput.playbackStarting()
        App.context.startActivity(Intent(App.context, App.platform.playerActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Stops a resolve in flight (Back on the stream-start screen). */
    fun cancelResolve() {
        resolveJob?.cancel()
        resolveJob = null
        _resolving.value = null
        detail.value = null
    }

    internal fun begin(
        request: PlaybackRequest,
        firstStage: String,
        live: (suspend (forceHls: Boolean, stremioStreamIndex: Int?) -> LiveStream)? = null,
        carried: CarriedTracks? = null,
        growing: Boolean = false,
        liveChannel: LiveChannel? = null,
        liveLabel: String? = null,
        expectedContext: Long? = null,
        resolveWithSettings: (suspend (PlaybackSettings?) -> ResolvedStream?)? = null,
        resolve: suspend () -> ResolvedStream = { error("no resolver") },
    ) {
        if (_resolving.value != null) return
        val ownership=PlaybackSessionContext.capture(expectedContext ?: ProfileDataContext.current)
        AdaptiveThroughput.playbackStarting()
        _resolving.value = request
        stage.value = firstStage
        detail.value = null
        val attempt=VodDenialEvents.begin(request.title.titleId,expectedContext ?: ProfileDataContext.current)
        resolveJob = scope.launch {
            try {
                expectedContext?.let(ProfileDataContext::requireCurrent)
                val prepared = if (live != null) {
                    val stream = live(false, null)
                    PlayerSession(request, stream.url, null, reportsProgress = false, liveSessionId = stream.sessionId,
                                  liveStream = stream,
                                  isLive = !growing, growingRestart = if (growing) suspend { live(false, null) } else null,
                                  retuneLive = live.takeIf { !growing }, liveChannel = liveChannel, sourceLabel = liveLabel,ownership=ownership)
                } else {
                    val settings = try {ApiClient.playbackSettings(expectedContext ?: ProfileDataContext.current)} catch(error:Exception) {error.rethrowVodDenial();null}
                    val r = if (resolveWithSettings != null) resolveWithSettings(settings) else resolve()
                    if (r == null) {
                        if (_resolving.value === request) _resolving.value = null
                        return@launch
                    }
                    expectedContext?.let(ProfileDataContext::requireCurrent)
                    val selectedRequest = r.libraryVersion?.let { request.copy(libraryVersion = it) } ?: request
                    val claimed = if (expectedContext == null) streamClaim?.invoke(selectedRequest, r)
                        else ApiClient.inContext(expectedContext) { streamClaim?.invoke(selectedRequest, r) }
                    if (claimed == true) {
                        AppLog.i("Playback", "resolved stream claimed (cast)")
                        playerStarted()
                        return@launch
                    }
                    PlayerSession(selectedRequest, ApiClient.streamUrl(r.path, selectedRequest.streamMetadata(r.path) + StreamInfo.trackerQuery(r.path, r.source)), r.tracks, streamPath = r.path,
                                  releaseName = r.releaseName?.ifEmpty { null } ?: releaseFileName(r.path), subtitleStreams = r.subtitleStreams,
                                  playback = settings, manualTracks = r.manualTracks, carried = carried, prequeueId = r.prequeueId, explicitAudioLanguage = r.explicitAudioLanguage,
                                  pickTracksOnDevice = r.pickTracksOnDevice,
                                  sourceLabel = StreamInfo.source(r.path, r.source, selectedRequest.libraryVersion?.origin),
                                  sourceDolbyVision = r.source?.dolbyVisionProfile, sourceHdr10 = r.source?.hdr10 == true,ownership=ownership)
                }
                fun publish() {
                    ownership.requireCurrent()
                    pending = prepared
                    stage.value = "Starting…"
                    detail.value = null
                    launchPlayer()
                }
                if (expectedContext == null) publish() else ApiClient.inContext(expectedContext) { publish() }
            } catch (e: CancellationException) {
                if (_resolving.value === request) _resolving.value = null
                throw e
            } catch (e: Exception) {
                if (_resolving.value === request) {
                    _resolving.value = null
                    if (expectedContext == null || expectedContext == ProfileDataContext.current) {
                        if(live==null)e.vodDenial?.let {VodDenialEvents.reject(attempt,it)}
                        errorMessage.value = e.vodDenial?.message ?: e.message ?: "Playback failed"
                    }
                }
            }
        }
    }

    /**
     * The release's file name from a stream path ("…/Show.S01E02.1080p.WEB-GRP.mkv"), for
     * subtitle matching when the server names none; null for paths that aren't a video file.
     */
    private fun releaseFileName(path: String): String? {
        val name = runCatching { java.net.URLDecoder.decode(path.substringBefore('?').substringAfterLast('/'), "UTF-8") }.getOrNull()
        return name?.takeIf { Regex("\\.(mkv|mp4|m4v|avi|ts|mov|webm)$", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    /** The player is on screen: the stream-start screen hands over to it. */
    fun playerStarted() {
        pending = null
        resolveJob = null
        _resolving.value = null
        detail.value = null
    }

    /**
     * The player closed. Pages reload watch state on this, so it waits (briefly) for the final
     * progress report to land; otherwise details would show the resume point from before.
     */
    fun playerClosed(saving: Job? = null) {
        scope.launch {
            withTimeoutOrNull(3_000) { saving?.join() }
            sessionsEnded.value += 1
        }
    }

    /** Starts resolving what `target`'s details page would play as it opens ([PrequeueResolver.prewarm]). */
    fun prewarm(target: DetailsTarget) = prequeue.prewarm(target)

    /** Discard old source/track previews after a content source preference changes. */
    fun invalidateWarmSources() = prequeue.invalidateWarm()
    fun invalidateWarmSources(titleId:String,expected:Long) = prequeue.invalidateWarmFor(titleId,expected)

    /** Starts resolving the next episode near the end of this one ([PrequeueResolver.prefetch]); [play] adopts it. */
    fun prefetch(request: PlaybackRequest) = prequeue.prefetch(request)
}
