package com.strmr.tv.player

import android.content.Intent
import com.strmr.tv.adaptive.AdaptiveThroughput
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.PlaybackSettings
import com.strmr.tv.api.PrequeueStatus
import com.strmr.tv.api.SourceResult
import com.strmr.tv.api.playbackSettings
import com.strmr.tv.api.resolveSourceWithQuality
import com.strmr.tv.api.ResolvedManualSource
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
import com.strmr.tv.live.startKidsLive
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
    val combinedLibraryFile = CombinedLibraryFileConsent()
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
        private set(value) {
            if (field !== value) field?.recordingDerived?.retirePending()
            field = value
        }

    private val scope = MainScope()
    private var resolveJob: Job? = null
    private var startupAttempt: PlaybackStartupAttempt? = null
    private val prequeue = PrequeueResolver(scope, progress = { s, d -> stage.value = s; detail.value = d })
    private val catalog = CatalogPlaybackResolver(prequeue::resolve)
    /** The details page's prequeue as it resolves, for its Audio & Subtitles button. */
    val warm: StateFlow<WarmPrequeue?> get() = prequeue.warm

    /** Automatic catalog playback shares its selection with Up Next and prefetch. */
    fun play(request: PlaybackRequest, picks: PrequeuePick? = null,expectedContext:Long=ProfileDataContext.current, startup: PlaybackStartupIntent? = null, chooseLegacyLibrary: (() -> Unit)? = null) {
        val startedNanos = PlaybackStartupTiming.now()
        val expected = expectedContext
        if(expected!=ProfileDataContext.current) { startup?.finish(StartupOutcome.OWNER_LOST); return }
        begin(request, "Searching…", expectedContext = expected,
            resolveWithSettings = { settings -> catalog.resolve(request, settings, picks, expected, chooseLegacyLibrary) }, startedNanos = startedNanos, startup = startup)
    }

    /**
     * Plays a source picked by hand, bypassing the automatic prequeue. [manual] carries the
     * tracks chosen in its track panel; they replace the server's choice for those kinds.
     * [carried] (Switch Source) re-applies the languages that were playing; [resolved] skips the
     * resolve (Auto Switch resolved it already).
     */
    fun play(
        request: PlaybackRequest, source: SourceResult, manual: ManualSourceTracks? = null, carried: CarriedTracks? = null,
        resolved: ResolvedManualSource? = null,
        prepared: com.strmr.tv.api.PrequeueStatus? = null,expectedContext:Long=ProfileDataContext.current,
        startup: PlaybackStartupIntent? = null,
    ) {
        val startedNanos = PlaybackStartupTiming.now()
        val expected=expectedContext
        if(expected!=ProfileDataContext.current) { startup?.finish(StartupOutcome.OWNER_LOST); return }
        // A stream picked over a library copy (Switch Source) is no longer that copy.
        begin(request.copy(libraryVersion = null), "Preparing…", carried = carried, expectedContext=expected, startedNanos=startedNanos,
            input=startupInput(prepared, resolved != null), startup = startup, resolve = {
            if (prepared != null) {
                return@begin ResolvedStream(requireNotNull(prepared.streamPath), prepared.trackSelection, prepared.displayName,
                    prepared.subtitleTracks.orEmpty(), manualTracks = true, source = prequeueSourceHints(prepared), prequeueId = prepared.prequeueId)
            }
            val resolution = resolved ?: ApiClient.resolveSourceWithQuality(source, request.title.titleId, request.startPosition,expected)
            val (path, tracks) = resolution
            val hints = SourceHints(source.serviceType.ifEmpty { null }, indexer = source.indexer.ifEmpty { null },
                                    attributes = source.attributes + resolution.qualityAttributes, frameRate = FrameRates.parse(resolution.frameRate))
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

    /** Build from the already-prepared release without changing the active player. */
    internal fun prepareReplacement(original: PlayerSession, status: com.strmr.tv.api.PrequeueStatus,
        targetMs: Long, carried: CarriedTracks, speed: Float, recovery: Boolean): PlayerSession {
        val owner = requireNotNull(original.ownership)
        return owner.use {
            check(pending === original && original.canOpenPlayer() && original.reportsProgress &&
                !original.isLive && !original.localFile && original.recordingDerived == null)
            require(status.status == "ready" && status.prequeueId.isNotBlank() && !status.streamPath.isNullOrBlank() && targetMs >= 0)
            val path = requireNotNull(status.streamPath)
            val hints = prequeueSourceHints(status)
            val request = SourceSwitchRules.resumeAt(original.request.copy(libraryVersion = null), targetMs).copy(speed = speed,
                audioRecoveryCount = original.request.audioRecoveryCount + if (recovery) 1 else 0)
            PlayerSession(request, ApiClient.streamUrl(path, request.streamMetadata(path) + StreamInfo.trackerQuery(path, hints)),
                status.trackSelection, streamPath = path, releaseName = status.releaseTitle,
                subtitleStreams = status.subtitleTracks.orEmpty(), playback = original.playback,
                manualTracks = true, carried = carried, prequeueId = status.prequeueId,
                sourceLabel = StreamInfo.source(path, hints, null), sourceDolbyVision = hints.dolbyVisionProfile,
                sourceHdr10 = hints.hdr10 == true, sourceAttributes = hints.attributes, sourceFrameRate = hints.frameRate, ownership = owner)
        }
    }
    /** Called only at the current activity's ready, explicit controller swap. */
    internal fun commitReplacement(original: PlayerSession, replacement: PlayerSession) {
        val owner = requireNotNull(original.ownership)
        owner.use {
            check(pending === original && original.canOpenPlayer() && replacement.ownership === owner && replacement.canOpenPlayer())
            pending = replacement
        }
    }

    /** A trailer full screen, in the same player, without progress. */
    fun playTrailer(url: String, name: String,expectedContext:Long=ProfileDataContext.current) {
        val startedNanos = PlaybackStartupTiming.now()
        if (_resolving.value != null) return
        val ownership=PlaybackSessionContext.capture(expectedContext)
        RecordingPartQueues.replace(null)
        val timing = PlaybackStartupTiming.begin(ownership, StartupInput.DIRECT_INPUT_SUPPLIED, startedNanos)
        pending = timing.attach(PlayerSession(PlaybackRequest(DetailsTarget("trailer", name, isMovie = true)), url, null, reportsProgress = false,ownership=ownership))
        launchPlayer()
    }

    /**
     * A live channel: the server tunes it (a direct stream or an HLS session) and the player plays
     * it. It asks for timeshift, so with the server's window on (30 minutes by default) the channel
     * plays from an HLS session that can be paused, rewound and returned to live.
     */
    fun playLive(channel: LiveChannel, programTitle: String?, expectedContext: Long = ProfileDataContext.current) {
        val startedNanos = PlaybackStartupTiming.now()
        if (expectedContext != ProfileDataContext.current || _resolving.value != null) return
        val request = PlaybackRequest(DetailsTarget(channel.id, programTitle ?: channel.name, isMovie = true, backdropUrl = null, logoUrl = channel.logoUrl))
        if (com.strmr.tv.app.AppSession.profile.value?.isKidsProfile == true) {
            begin(request, "Checking this programme…", live = { _, _ -> ApiClient.startKidsLive(channel.id, expectedContext) },
                liveChannel = channel, expectedContext = expectedContext, liveLabel = StreamInfo.live(channel.name), startedNanos = startedNanos)
            return
        }
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
              liveLabel = StreamInfo.live(channel.name), startedNanos = startedNanos)
    }

    /**
     * A Live TV recording, seekable, without progress (as Expo plays it): a finished one's
     * file, or one still recording over the server's growing HLS session from the start
     * ([RecordingHls]).
     */
    fun playRecording(recording: Recording, expectedContext:Long=ProfileDataContext.current) {
        val startedNanos = PlaybackStartupTiming.now()
        if (expectedContext!=ProfileDataContext.current || _resolving.value != null || !RecordingRules.canPlay(recording)) return
        RecordingCaptureRules.firstClosed(recording)?.let {part ->
            playRecordingPartTimed(recording,part,expectedContext,startedNanos);return
        }
        if(RecordingCaptureRules.known(recording)?.parts?.size?.let {it>1}==true)return
        val title = RecordingRules.title(recording)
        val request = PlaybackRequest(DetailsTarget(recording.id, title, isMovie = true))
        AppLog.i("Recordings", "play ${recording.id} status=${recording.status}")
        if (RecordingRules.isRecording(recording)) {
            begin(request, "Starting…", live = { _, _ -> ApiClient.startRecordingHls(recording, title,expectedContext) }, growing = true, expectedContext=expectedContext,
                  liveLabel = StreamInfo.recording(recording.channelName), audioOffsetKey = AudioOffsetKeys.recording(recording.id), startedNanos = startedNanos)
            return
        }
        ApiClient.inContext(expectedContext) {
            val owner = PlaybackSessionContext.capture(expectedContext)
            RecordingPartQueues.replace(null)
            val timing = PlaybackStartupTiming.begin(owner, StartupInput.DIRECT_INPUT_SUPPLIED, startedNanos)
            pending = timing.attach(PlayerSession(request, ApiClient.recordingStreamUrl(recording, title,expectedContext), null, reportsProgress = false,
                                    sourceLabel = StreamInfo.recording(recording.channelName), audioOffsetKey = AudioOffsetKeys.recording(recording.id),ownership=owner))
            launchPlayer()
        }
    }

    /** Explicit accepted joined copy only. Preparation and status reads never call this. */
    internal fun playJoinedRecording(source: RecordingDerivedPlayback): Boolean {
        if (!source.canStart() || _resolving.value != null) { source.retirePending(); return false }
        return source.owner.use {
            RecordingPartQueues.replace(null)
            val value = source.observation.descriptor
            val url = com.strmr.tv.live.recordingDerivedStreamUrl(ApiClient.baseUrl, source.owner.profileId,
                value.recordingId, requireNotNull(value.id)).toString()
            // Honest coordinator-only startup observation, not a synthesized click.
            pending = PlaybackStartupTiming.begin(source.owner, StartupInput.DIRECT_INPUT_SUPPLIED)
                .attach(recordingDerivedSession(source, url))
            launchPlayer()
            true
        }
    }

    fun playRecordingParts(recording:Recording,expectedContext:Long) {
        if(expectedContext!=ProfileDataContext.current || _resolving.value!=null)return
        val snapshot=RecordingPartSnapshot.capture(recording) ?: return
        val owner=PlaybackSessionContext.capture(expectedContext)
        val queue=RecordingPartQueue(owner,snapshot)
        val saved=RecordingPartCursorStore.read(owner,snapshot)
        RecordingPartQueues.replace(queue)
        queue.start(saved)?.let(::launchRecordingPart)
    }
    fun continueRecordingParts(handoff:RecordingPartHandoff) {
        if(_resolving.value!=null)return
        val next=handoff.take() ?: return
        try {launchRecordingPart(next)} catch (_:CancellationException) {next.cancel()}
    }
    private fun launchRecordingPart(run:RecordingPartPlayback) = run.owner.use {
        val snapshot=run.queue.snapshot
        val title="${RecordingRules.title(snapshot.recording)} · Part ${run.part.index}"
        val timing=PlaybackStartupTiming.begin(run.owner,StartupInput.DIRECT_INPUT_SUPPLIED)
        pending=timing.attach(recordingPartSession(snapshot.recording,run.part,
            ApiClient.recordingStreamUrl(snapshot.recording,title,run.owner.epoch,run.part),run.owner,run))
        launchPlayer()
    }

    fun playRecordingPart(recording:Recording,part:RecordingPart,expectedContext:Long) =
        playRecordingPartTimed(recording,part,expectedContext,PlaybackStartupTiming.now())

    private fun playRecordingPartTimed(recording:Recording,part:RecordingPart,expectedContext:Long,startedNanos:Long) {
        if(expectedContext!=ProfileDataContext.current || _resolving.value!=null || !RecordingCaptureRules.playable(recording,part))return
        ApiClient.inContext(expectedContext) {
            val title="${RecordingRules.title(recording)} · Part ${part.index}"
            val owner=PlaybackSessionContext.capture(expectedContext)
            RecordingPartQueues.replace(null)
            val timing=PlaybackStartupTiming.begin(owner,StartupInput.DIRECT_INPUT_SUPPLIED,startedNanos)
            pending=timing.attach(recordingPartSession(recording,part,ApiClient.recordingStreamUrl(recording,title,expectedContext,part),owner))
            launchPlayer()
        }
    }

    /**
     * A past (or airing) guide programme from the provider's catch-up, seekable, without progress
     * (as recordings play): the server turns it into the provider's archive URL, which plays as a
     * file through `/api/video/stream` (providers serve the archive whole, with byte ranges).
     */
    fun playCatchup(channel: LiveChannel, program: EpgProgram) {
        val startedNanos = PlaybackStartupTiming.now()
        if (_resolving.value != null) return
        val ownership=PlaybackSessionContext.capture()
        RecordingPartQueues.replace(null)
        val timing=PlaybackStartupTiming.begin(ownership,startedNanos=startedNanos)
        startupAttempt=timing
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
                pending = timing.attach(PlayerSession(request, ApiClient.catchupStreamUrl(archive, channel, program, ownership.epoch), null, reportsProgress = false,
                                        sourceLabel = listOf("Catch-up", channel.name).joinToString(" · "), audioOffsetKey = AudioOffsetKeys.live(channel.id),ownership=ownership))
                launchPlayer()
            } catch (e: CancellationException) {
                timing.finish(StartupOutcome.CANCELLED)
                throw e
            } catch (e: Exception) {
                timing.finish(StartupOutcome.FAILED)
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
        val startedNanos = PlaybackStartupTiming.now()
        if (_resolving.value != null || session.replacementBuffer != null || !session.canOpenPlayer()) return
        RecordingPartQueues.replace(null)
        pending = PlaybackStartupTiming.begin(requireNotNull(session.ownership),StartupInput.DIRECT_INPUT_SUPPLIED,startedNanos).attach(session)
        launchPlayer()
    }

    private fun launchPlayer() {
        AdaptiveThroughput.playbackStarting()
        try { App.context.startActivity(Intent(App.context, App.platform.playerActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (error: Exception) { pending?.startupTiming?.finish(StartupOutcome.FAILED); throw error }
    }

    /** Stops a resolve in flight (Back on the stream-start screen). */
    fun cancelResolve() {
        startupAttempt?.finish(StartupOutcome.CANCELLED)
        startupAttempt = null
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
        audioOffsetKey: String? = null,
        expectedContext: Long? = null,
        resolveWithSettings: (suspend (PlaybackSettings?) -> ResolvedStream?)? = null,
        startedNanos: Long = PlaybackStartupTiming.now(),
        input: StartupInput = StartupInput.UNKNOWN,
        startup: PlaybackStartupIntent? = null,
        resolve: suspend () -> ResolvedStream = { error("no resolver") },
    ) {
        if (_resolving.value != null) { startup?.finish(StartupOutcome.NOT_STARTED); return }
        val adopted = runCatching { PlaybackStartupTiming.adopt(startup, request.title, expectedContext ?: ProfileDataContext.current, input) }.getOrNull()
        val ownership = adopted?.owner ?: PlaybackSessionContext.capture(expectedContext ?: ProfileDataContext.current)
        RecordingPartQueues.replace(null)
        val timing = adopted ?: PlaybackStartupTiming.begin(ownership, input, startedNanos)
        startupAttempt=timing
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
                    val selectedRequest = stream.programmeLimit?.let { request.copy(title = request.title.copy(name = it.programme.title)) } ?: request
                    PlayerSession(selectedRequest, stream.url, null, reportsProgress = false, liveSessionId = stream.sessionId,
                                  liveStream = stream,
                                  isLive = !growing, growingRestart = if (growing) suspend { live(false, null) } else null,
                                  retuneLive = live.takeIf { !growing }, liveChannel = liveChannel, sourceLabel = liveLabel, audioOffsetKey = audioOffsetKey,ownership=ownership)
                } else {
                    val settings = try {ApiClient.playbackSettings(expectedContext ?: ProfileDataContext.current)} catch(error:Exception) {error.rethrowVodDenial();null}
                    val r = if (resolveWithSettings != null) resolveWithSettings(settings) else resolve()
                    if (r == null) {
                        timing.finish(if (adopted != null) StartupOutcome.CHOICE_REQUIRED else StartupOutcome.CANCELLED)
                        if (_resolving.value === request) _resolving.value = null
                        return@launch
                    }
                    expectedContext?.let(ProfileDataContext::requireCurrent)
                    val selectedRequest = resolvedLibraryRequest(request, r, combinedLibraryFile, expectedContext ?: ProfileDataContext.current)
                    expectedContext?.let(ProfileDataContext::requireCurrent)
                    val claimed = if (expectedContext == null) streamClaim?.invoke(selectedRequest, r)
                        else ApiClient.inContext(expectedContext) { streamClaim?.invoke(selectedRequest, r) }
                    if (claimed == true) {
                        timing.finish(StartupOutcome.EXTERNAL_HANDOFF)
                        AppLog.i("Playback", "resolved stream claimed (cast)")
                        playerStarted()
                        return@launch
                    }
                    PlayerSession(selectedRequest, ApiClient.streamUrl(r.path, selectedRequest.streamMetadata(r.path) + StreamInfo.trackerQuery(r.path, r.source)), r.tracks, streamPath = r.path,
                                  releaseName = r.releaseName?.ifEmpty { null } ?: releaseFileName(r.path), subtitleStreams = r.subtitleStreams,
                                  playback = settings, manualTracks = r.manualTracks, carried = carried, prequeueId = r.prequeueId, explicitAudioLanguage = r.explicitAudioLanguage,
                                  pickTracksOnDevice = r.pickTracksOnDevice,
                                  sourceLabel = StreamInfo.source(r.path, r.source, selectedRequest.libraryVersion?.origin),
                                  sourceDolbyVision = r.source?.dolbyVisionProfile, sourceHdr10 = r.source?.hdr10 == true,
                                  sourceAttributes = r.source?.attributes.orEmpty(), sourceFrameRate = r.source?.frameRate, ownership=ownership)
                }
                fun publish() {
                    ownership.requireCurrent()
                    pending = timing.attach(prepared)
                    stage.value = "Starting…"
                    detail.value = null
                    launchPlayer()
                }
                if (expectedContext == null) publish() else ApiClient.inContext(expectedContext) { publish() }
            } catch (e: CancellationException) {
                timing.finish(StartupOutcome.CANCELLED)
                if (_resolving.value === request) _resolving.value = null
                throw e
            } catch (e: Exception) {
                timing.finish(StartupOutcome.FAILED)
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
        startupAttempt = null
        pending = null
        resolveJob = null
        _resolving.value = null
        detail.value = null
    }

    /**
     * The player closed. Pages reload watch state on this, so it waits (briefly) for the final
     * progress report to land; otherwise details would show the resume point from before.
     */
    private val recordingProgressClosings = mutableSetOf<Job>()
    internal fun retainRecordingProgressClose(job: Job) {
        synchronized(recordingProgressClosings) { recordingProgressClosings.add(job) }
        job.invokeOnCompletion { synchronized(recordingProgressClosings) { recordingProgressClosings.remove(job) } }
    }
    fun playerClosed(saving: Job? = null) {
        val recording = synchronized(recordingProgressClosings) { saving in recordingProgressClosings }
        scope.launch {
            if (recording) saving?.join() else withTimeoutOrNull(3_000) { saving?.join() }
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
