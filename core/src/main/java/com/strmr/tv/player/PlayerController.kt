package com.strmr.tv.player

import com.strmr.tv.app.AppBuild
import android.content.Context
import android.os.SystemClock
import com.strmr.tv.ui.settings.AutoSwitchSettings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.SourceResult
import com.strmr.tv.api.withoutAutomaticReplay
import com.strmr.tv.api.StartPoint
import com.strmr.tv.api.mediaHeaders
import com.strmr.tv.app.AppLog
import com.strmr.tv.live.LiveCatchups
import com.strmr.tv.live.KidsProgrammeClock
import com.strmr.tv.player.dv7.Dv7Playback
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One playback: ExoPlayer on the raw stream (platform decoders first, the FFmpeg extension
 * for audio the box can't decode, passthrough where the sink supports it), the server's track
 * choice applied once the tracks are known, the resume point, and progress every 10 s.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context, val session: PlayerSession,
    startGate: ((start: () -> Unit) -> Unit)? = null,
    private val onVideoFormat: (Format) -> Unit,
) {
    private val applicationContext = context.applicationContext
    internal var standby = session.replacementBuffer != null; private set
    val request = session.request
    val nextRecordingPart get() = session.recordingPart?.next
    fun cancelRecordingParts() {session.recordingPart?.cancel()}
    private val ownership=requireNotNull(session.ownership) {"Playback context is unavailable"}.also {it.requireCurrent();session.recordingPart?.claim();session.recordingDerived?.claim()}
    val dataContext = ownership.epoch
    private var released=false
    private var audioOffsetKey = AudioOffsetKeys.of(session)
    private val programmeClock = session.liveStream?.programmeLimit?.let { KidsProgrammeClock(it, android.os.SystemClock::elapsedRealtime, ::stopChildProgramme) }
    private var programmeTimer: Job? = null
    val profileId = ownership.profileId
    val player: ExoPlayer
    val isPlaying = MutableStateFlow(false)
    val buffering = MutableStateFlow(true)
    val tracks = MutableStateFlow(Tracks.EMPTY)
    val error = MutableStateFlow<String?>(null)
    val ended = MutableStateFlow(false)
    /** Position and duration (0 until known), ticked by the clock for the chrome. */
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val speed = MutableStateFlow(1f)
    /**
     * A live channel whose server session keeps a timeshift window: it pauses, seeks inside the
     * window and returns to live ([goLive]). False for a direct stream or with timeshift off.
     */
    val timeshift = MutableStateFlow(session.isLive && session.liveStream?.timeshift == true)
    /** How far behind the live edge a timeshift channel plays, in ms (0 at the edge), ticked by the clock. */
    val behindLive = MutableStateFlow(0L)
    /** A switch between the channel and its catch-up archive is loading. */
    val switching = MutableStateFlow(false)
    /** A short message over the player (a catch-up that couldn't open), cleared by the chrome. */
    val notice = MutableStateFlow<String?>(null)
    /** Set when playback moves on to the next episode; the activity closes and starts it. */
    val advanceTo = MutableStateFlow<PlaybackRequest?>(null)
    /** Set by Switch Source; the activity closes and resumes on the chosen source. */
    val switchTo = MutableStateFlow<SourceSwitchRequest?>(null)
    var releaseReportOpen = false
        private set
    private var releaseReports: ReleaseReports? = null
    internal var audioPreferenceSave: Job? = null
    internal var audioPreferenceFailed = false
    val vodDenied=MutableStateFlow<com.strmr.tv.api.VodAccessDenial?>(null)
    val canReportRelease get() = !standby && vodDenied.value==null && !session.isLive && !session.localFile && session.reportsProgress && session.prequeueId != null

    private val recoveryContext = ServerReleaseReportGateway(dataContext)
    private val audioRecoveryPolicy = AudioRecoveryPolicy()
    private val decodedAudio = session.replacementBuffer?.parts?.decodedAudio ?: DecodedAudioObservation()
    private val decodedAudioPolicy = DecodedAudioSilencePolicy()
    val audioRecovery by lazy { AudioRecoveryAssistant(scope, { createReleaseReports(true) }, { recoveryContext.isCurrent() }) }
    val audioRecoveryActive get() = audioRecovery.active

    fun openReleaseReports(): ReleaseReports {
        releaseReportOpen = true
        return (audioRecovery.flow ?: createReleaseReports(false)).also { releaseReports = it }
    }
    private fun createReleaseReports(recovery: Boolean): ReleaseReports =
        ReleaseReports(requireNotNull(session.prequeueId), recoveryContext, scope,
            { player.currentPosition }, { status, offset, wrong ->
                autoSwitch.cancel()
                if (wrong) progress.discardFuture()
                val carried = CarriedTracks.capture(player.currentTracks, player.trackSelectionParameters, external.selected.value).copy(audioLanguage = null)
                val source = SourceResult.from(kotlinx.serialization.json.buildJsonObject {
                    put("title", kotlinx.serialization.json.JsonPrimitive(status.selectedResult?.title ?: status.displayName ?: "Prepared alternative"))
                    put("serviceType", kotlinx.serialization.json.JsonPrimitive(status.serviceType ?: ""))
                }, 0)!!
                val next = SourceSwitchRules.resumeAt(request, offset).copy(speed = speed.value,
                    audioRecoveryCount = request.audioRecoveryCount + if (recovery) 1 else 0)
                switchTo.value = SourceSwitchRequest(next, source, carried, prepared = status)
            }, prepareLocal = { status, target, deadline ->
                ReplacementBuffer.prepare(applicationContext, this, status, target, deadline, recovery).also { buffer ->
                    buffer.onAbandoned { recoveryContext.cancel(status.prequeueId) }
                }
            }, adoptLocal = { status, offset, wrong, handle ->
                val buffer = handle as? ReplacementBuffer ?: error("Actual replacement source missing")
                check(buffer.isReady() && buffer.targetMs == offset)
                requireReplacementOwner()
                val source = SourceResult.from(kotlinx.serialization.json.buildJsonObject {
                    put("title", kotlinx.serialization.json.JsonPrimitive(status.selectedResult?.title ?: status.displayName ?: "Prepared alternative"))
                    put("serviceType", kotlinx.serialization.json.JsonPrimitive(status.serviceType ?: ""))
                }, 0)!!
                val next = buffer.candidateSession.request
                switchTo.value = SourceSwitchRequest(next, source, replacementCarriedTracks(), prepared = status,
                    buffered = buffer, discardPreviousProgress = wrong)
            }, beforeHistoryReset = { progress.stopAndDrainForHistoryReset() }, progressAlreadyDiscarded = !progress.acceptsCompletion,
            currentAudioTrack = { options ->
                if (!canUsePlayerAux() || switchTo.value != null || advanceTo.value != null || ended.value) null
                else tracks.value.ofType(C.TRACK_TYPE_AUDIO).singleOrNull { it.selected }?.let {
                    spokenAudioContainerIndex(it.format, it.typeIndex, options)
                }
            },
            denialTitleId=request.title.titleId,denialContext=dataContext,beforeOptions = { vodDenied.value?.let {throw com.strmr.tv.api.ApiError.AccessDenied(it)}; audioPreferenceSave?.join(); check(!audioPreferenceFailed) { "Audio preference could not be saved" } })

    internal fun cancelAudioLanguageCheck() { releaseReports?.cancelAudioLanguageCheck() }

    fun closeReleaseReports() {
        releaseReports?.close()
        releaseReports = null
        releaseReportOpen = false
        audioRecovery.dismiss()
    }
    private fun canPrepareAudioAlternative() = vodDenied.value==null && audioRecoveryEligible(session, AutoSwitchSettings.enabled, recoveryContext.isCurrent()) &&
        !releaseReportOpen && !audioRecovery.active && !autoSwitch.preparing && switchTo.value == null && advanceTo.value == null && !ended.value

    private fun observeAudioAvailability() {
        if (!audioRecoveryEligible(session, AutoSwitchSettings.enabled, recoveryContext.isCurrent())) audioRecovery.dismiss()
        val next = tracks.value
        audioRecoveryPolicy.observe(AudioAvailability(SystemClock.elapsedRealtime(), player.currentPosition, player.isPlaying,
            next.ofType(C.TRACK_TYPE_VIDEO).any { it.selected }, session.tracks?.audio != null,
            next.ofType(C.TRACK_TYPE_AUDIO).isNotEmpty(), C.TRACK_TYPE_AUDIO !in player.trackSelectionParameters.disabledTrackTypes,
            player.volume), canPrepareAudioAlternative())?.let { audioRecovery.suggest(it) }
        val selectedAudio = next.ofType(C.TRACK_TYPE_AUDIO).singleOrNull { it.selected }
        if (decodedAudioPolicy.observe(decodedAudio.snapshot(), DecodedAudioPlayback(
                nowMs = SystemClock.elapsedRealtime(), positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                eligible = canUsePlayerAux() && canPrepareAudioAlternative(), playing = player.isPlaying,
                buffering = player.playbackState != Player.STATE_READY,
                videoSelected = next.ofType(C.TRACK_TYPE_VIDEO).any { it.selected },
                audioGroup = selectedAudio?.group?.mediaTrackGroup, audioIndex = selectedAudio?.index ?: -1,
                audioEnabled = C.TRACK_TYPE_AUDIO !in player.trackSelectionParameters.disabledTrackTypes,
                volume = player.volume, speed = player.playbackParameters.speed,
            ))) audioRecovery.suggest(AudioRecoveryHint.SILENCE)
    }
    /** The cues on screen, drawn by [SubtitleOverlay] in the device's style. */
    val cues = MutableStateFlow<List<Cue>>(emptyList())
    /** The video's display aspect (0 until known), so subtitles sit on the picture. */
    val videoAspect = MutableStateFlow(0f)
    /** The selected video and audio tracks' formats (null until known), for the Info panel's stream details. */
    val videoFormat = MutableStateFlow<Format?>(null)
    val audioFormat = MutableStateFlow<Format?>(null)

    /**
     * The track preferences after this device's overrides (Settings › Playback). A library file
     * has no server choice to map: the profile's languages pick both kinds here.
     */
    val effectiveTracks = when {
        session.manualTracks -> EffectiveTracks.resolve(session.tracks, session.playback, null, null, null)
        session.pickTracksOnDevice -> EffectiveTracks.current(null, session.playback).copy(clientAudio = true, clientSubtitles = true)
        else -> EffectiveTracks.current(session.tracks, session.playback)
    }
    private val prefs = TrackPreferences(session.tracks, effectiveTracks)
    private var prefsApplied = false
    private var startApplied = false
    private var audioRetried = false
    /** The playback's main-thread scope, cancelled shortly after [release]. */
    val scope = MainScope().also { session.replacementBuffer?.retainActualScope(it) }
    private val progress: ProgressReporter
    /** A downloaded or translated subtitle, drawn in place of ExoPlayer's cues while selected. */
    val external = ExternalSubtitles(scope, { player.currentPosition }) { cues.value = it }
    val online = OnlineSubtitles(this, scope)
    private val autoSwitch = AutoSwitch(this)
    private val startBarrier = PlaybackStartBarrier(
        { canUsePlayerAux() && session.canOpenPlayer() && recordingStart?.started != false &&
            error.value == null && !ended.value && enforceProgrammeLimit() },
        { player.play() },
    )

    /** Display resync is not evidence of a source stall. */
    fun displayModeSwitched() = autoSwitch.resettle()

    fun pauseForSwitch(): () -> Unit {
        val wasPlaying = player.playWhenReady
        player.pause()
        return { if (wasPlaying && canUsePlayerAux() && session.canOpenPlayer() &&
            error.value == null && !ended.value && enforceProgrammeLimit()) player.play() }
    }

    /** Seek previews, for a resolved stream (not trailers, live or a downloaded file). */
    private fun createThumbnails(): PlaybackThumbnails? =
        session.streamPath?.takeIf { !session.isLive && session.reportsProgress && !session.localFile }?.let { PlaybackThumbnails(it, request, ownership, applicationContext) }
    var thumbnails: PlaybackThumbnails? = if (standby) null else createThumbnails(); private set
    /** The video surface, set by the player screen's PlayerView: credits detection reads frames from it. */
    var videoSurface: android.view.SurfaceView? = null
    private var attachedView: androidx.media3.ui.PlayerView? = null
    /** The activity supplies its actual retained view; a standby cannot steal it. */
    fun attachVideoView(view: androidx.media3.ui.PlayerView) {
        check(!standby && !released && PlaybackCoordinator.pending === session)
        if (view.player !== player) view.player = player
        attachedView = view
        videoSurface = view.videoSurfaceView as? android.view.SurfaceView
    }

    /** Consume the actual loaded source once, on the current activity's main looper. */
    fun adoptBuffered(request: SourceSwitchRequest): PlayerController? {
        val buffer = request.buffered ?: return null
        var changedView: androidx.media3.ui.PlayerView? = null
        try {
            check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
            return ownership.use {
                requireReplacementOwner()
                check(switchTo.value === request && request.prepared?.prequeueId == buffer.candidateSession.prequeueId)
                val view = requireNotNull(attachedView) { "Player surface is unavailable" }
                check(view.player === player && videoSurface?.holder?.surface?.isValid == true)
                val candidate = buffer.transfer(this)
                // Same view, same loaded source, same frozen position: no new seek or resolution.
                check(candidate.player.currentPosition == buffer.targetMs)
                view.player = candidate.player
                changedView = view
                candidate.prepareReplacementActivation()
                buffer.promote(this)
                PlaybackCoordinator.commitReplacement(session, candidate.session)
                candidate.attachedView = view
                candidate.videoSurface = videoSurface
                candidate.activateReplacement()
                if (request.discardPreviousProgress) progress.discardFuture()
                autoSwitch.cancel()
                switchTo.value = null
                attachedView = null; videoSurface = null
                runCatching { release() }.onFailure {
                    candidate.notice.value = "The source switched. Previous player cleanup is still pending."
                }
                candidate
            }
        } catch (error: Exception) {
            if (PlaybackCoordinator.pending === session) {
                changedView?.let { view ->
                    view.player = player
                    runCatching { player.setAudioAttributes(player.audioAttributes, true) }
                }
                switchTo.value = null
                notice.value = "Could not switch to the buffered alternative. Playback continues."
                buffer.close()
                return null
            }
            throw error
        }
    }
    /** A recording still recording, over its growing HLS session (set as the player is built). */
    private var recordingHls: RecordingHls? = null
    /** A live channel: its server session, and tuning it again when the source drops (set as the player is built). */
    var liveRetune: LiveRetune? = null
        private set
    /** A live channel with provider catch-up: rewinding past what the player holds plays the archive. */
    var catchup: CatchupJoin? = null
        private set
    val postPlay = MutableStateFlow<PlaybackRequest?>(null)
    private val postPlayOffer = PostPlayOffer(dataContext)

    private fun createUpNext(): UpNext? = if (session.reportsProgress && !session.isLive) {
        UpNext(request, session.streamPath?.takeIf { !session.localFile }, scope, { videoSurface }, onSkip = { seekTo(it) },onDenied={denyVod(it)},expectedContext=dataContext) { next ->
            report(ended = true)
            advanceTo.value = next.copy(speed = speed.value)
        }
    } else null

    var upNext: UpNext? = if (standby) null else createUpNext(); private set

    private val stallLog by lazy { StallLog(player) }

    private val derivedSource = session.recordingDerived
    private val recordingStart = derivedSource?.takeIf { it.progressReceipt != null }?.let(::RecordingProgressStart)
    private var recordingProgress: RecordingProgressReporter? = null
    private var recordingStartTimeout: Job? = null
    private fun failRecordingProgressStart() {
        derivedSource?.deny(); recordingProgress?.discard(); player.stop()
        startup.end(StartupOutcome.FAILED)
        error.value = "Joined recording cannot open at the confirmed position. Return and read progress again."
    }
    private val backendMediaHeaders=ApiClient.inContext(dataContext) {ApiClient.mediaHeaders}
    private val mediaAccess=session.replacementBuffer?.parts?.access ?: VodMediaAccess(ApiClient.inContext(dataContext) {ApiClient.baseUrl},
        enabled=session.reportsProgress && !session.isLive && !session.localFile,backendHeaders=backendMediaHeaders,denialTitleId=request.title.titleId,context=dataContext,requireCurrent=ownership::requireCurrent,profileId=profileId)

    private val derivedMediaAccess = derivedSource?.let { RecordingDerivedMediaAccess(ApiClient.inContext(dataContext) {ApiClient.baseUrl}, it, backendMediaHeaders) }
    internal fun canUsePlayerAux(): Boolean = !standby && !released && ownership.isCurrent() && vodDenied.value == null

    internal fun denyVod(denial:com.strmr.tv.api.VodAccessDenial,attempt:com.strmr.tv.api.VodAttempt?=null) {
        if(released || dataContext!=ProfileDataContext.current || vodDenied.value!=null)return
        attempt?.let {com.strmr.tv.api.VodDenialEvents.reject(it,denial)}
        startup.end(StartupOutcome.FAILED)
        cancelRecordingParts()
        vodDenied.value=denial;autoSwitch.cancel();upNext?.cancel();closeReleaseReports();progress.discardFuture()
        decodedAudio.close(); decodedAudioPolicy.reset()
        recordingHls?.release();catchup?.release();liveRetune?.release()
        thumbnails?.cancel();online.cancel();external.cancel();switchTo.value=null;advanceTo.value=null
        player.stop();error.value=denial.message
    }

    private val startup = PlaybackStartupPlayer(session) { !released && session.canOpenPlayer() && vodDenied.value == null }
    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() { if (!standby) startup.firstFrame() }

        override fun onIsPlayingChanged(playing: Boolean) {
            if (playing && !enforceProgrammeLimit()) return
            isPlaying.value = playing
            if (standby) return
            if (playing) {
                liveRetune?.playing()
                progress.start()
            } else if (player.playbackState == Player.STATE_READY) report(paused = true)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                AppLog.i("Player", "paused by the system (reason=$reason)")
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!enforceProgrammeLimit()) return
            buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (standby) return
            if (state == Player.STATE_READY) {
                val start = recordingStart
                if (start != null && !start.started) {
                    try {
                        if (start.ready(player.isCurrentMediaItemSeekable, player.duration, player.currentPosition)) {
                            recordingStartTimeout?.cancel(); recordingStartTimeout = null
                            recordingProgress?.start(); startBarrier.startIfAllowed()
                        }
                    } catch (_: Exception) { failRecordingProgressStart() }
                } else applyPercentStart()
            }
            if (state == Player.STATE_BUFFERING) liveRetune?.buffering()
            if (state == Player.STATE_ENDED && advanceTo.value == null) {
                if (recordingStart != null) {
                    if (recordingStart.started && player.playerError == null) report(ended = true)
                    ended.value = true
                    return
                }
                if (postPlay.value != null) return
                if (catchup?.ended() == true) return
                recordingHls?.let { hls ->
                    hls.ended { ended.value = true }
                    return
                }
                liveRetune?.let { live ->
                    live.ended()
                    return
                }
                session.recordingPart?.let {part -> part.ended(player.currentPosition,player.playerError!=null || error.value!=null);ended.value=true;return }
                val genuine = EpisodeAdvancePolicy.genuineEnd(player.currentPosition, player.duration, player.playerError != null)
                if (genuine) report(ended = true)
                if (upNext?.ended(player.currentPosition, player.duration, player.playerError != null) == true) return
                if (postPlayOffer.offer(request, session.reportsProgress, session.isLive, !progress.acceptsCompletion,
                        player.currentPosition, player.duration, player.playerError != null || switchTo.value != null || audioRecovery.active || autoSwitch.preparing || releaseReportOpen)) {
                    player.pause()
                    upNext?.cancel()
                    progress.stop()
                    postPlay.value = request
                    return
                }
                ended.value = true
            }
        }

        override fun onTracksChanged(next: Tracks) {
            val previousAudio = tracks.value.ofType(C.TRACK_TYPE_AUDIO).singleOrNull { it.selected }?.format
            val nextAudio = next.ofType(C.TRACK_TYPE_AUDIO).singleOrNull { it.selected }?.format
            if (previousAudio != nextAudio) cancelAudioLanguageCheck()
            tracks.value = next
            if (!prefsApplied && !next.isEmpty) {
                prefsApplied = true
                player.trackSelectionParameters = prefs.apply(next, player.trackSelectionParameters)
                if (session.manualTracks) {
                    val audio = player.trackSelectionParameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                    AppLog.i("ManualTracks", "player applied: audio=${audio?.mediaTrackGroup?.getFormat(audio.trackIndices.firstOrNull() ?: 0)?.id} " +
                        "subtitle=${prefs.subtitlePick?.format?.id} (${prefs.subtitlePick?.format?.language})")
                }
                if (effectiveTracks.clientAudio || effectiveTracks.clientSubtitles) {
                    AppLog.i("Player", "device track overrides: $effectiveTracks pick=${prefs.subtitlePick?.format?.language}")
                }
                session.carried?.let { carried ->
                    player.trackSelectionParameters = carried.apply(next, player.trackSelectionParameters)
                    AppLog.i("SourceSwitch", "carried tracks applied: $carried")
                }
                if (!standby) online.tracksReady(next, prefs.subtitlePick)
            }
            next.ofType(C.TRACK_TYPE_VIDEO).firstOrNull { it.selected }?.let {
                videoFormat.value = it.format
                if (!standby) onVideoFormat(it.format)
            }
            audioFormat.value = next.ofType(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.format
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            decodedAudio.restart()
            decodedAudioPolicy.reset()
            if (reason == Player.DISCONTINUITY_REASON_SEEK && recordingStart?.started == true) report()
        }

        override fun onCues(cueGroup: CueGroup) {
            if (!external.selected.value) cues.value = cueGroup.cues
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && size.height > 0) videoAspect.value = size.width * size.pixelWidthHeightRatio / size.height
        }

        override fun onPlayerError(e: PlaybackException) {
            if (!enforceProgrammeLimit()) return
            if (standby) { error.value = "Prepared replacement could not decode on this device."; return }
            if (derivedSource != null) {
                startup.end(StartupOutcome.FAILED)
                derivedSource.deny(); recordingProgress?.discard(); progress.discardFuture(); player.stop()
                error.value = "Joined recording is unavailable. Return and read status or prepare explicitly."
                return
            }
            cancelRecordingParts()
            if(!session.canOpenPlayer()) {cancelRecordingParts();startup.end(StartupOutcome.OWNER_LOST);progress.discardFuture();player.stop();ended.value=true;return}
            if(released)return
            mediaAccess.failure(e)?.let {denyVod(it.reason,it.attempt);return}
            // A passthrough track the output refused: PassthroughGuard now offers PCM, so prepare again once.
            if (e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && !audioRetried) {
                audioRetried = true
                AppLog.w("Player", "audio output failed (${e.message}); retrying with decoded audio")
                player.prepare()
                return
            }
            // A platform audio decoder that rejects the stream (Atmos on some phones): FFmpeg takes over.
            AudioDecoderFallback.exclude(e)?.let { decoder ->
                AppLog.w("Player", "audio decoder $decoder failed (${e.message}); retrying with FFmpeg")
                player.prepare()
                return
            }
            if (catchup?.failed(e.message ?: e.errorCodeName) == true) return
            // A live stream that fell behind its window rejoins at the live edge.
            if (session.isLive && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                AppLog.w("Player", "behind the live window; rejoining")
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            if (liveRetune?.failed(e) == true) {
                AppLog.w("Player", "live source error ${e.errorCodeName}: ${e.message}")
                return
            }
            audioRecoveryPolicy.terminalFailure(e, canPrepareAudioAlternative())?.let { audioRecovery.suggest(it) }
            AppLog.e("Player", "playback error ${e.errorCodeName}: ${e.message}", e)
            if (!session.isLive && AutoSwitch.switchesOnError(e.errorCode) &&
                autoSwitch.trigger("player error ${e.errorCodeName}") { showTerminalError(e) }) return
            showTerminalError(e)
        }
    }

    private fun showTerminalError(e: PlaybackException) {
        if (!canUsePlayerAux() || !session.canOpenPlayer()) return
        thumbnails?.cancel()
        startup.end(StartupOutcome.FAILED)
        error.value = e.message ?: e.errorCodeName
    }

    init {
        if (!session.canOpenPlayer()) {
            if (derivedSource != null) throw RecordingDerivedRetired()
            throw IllegalArgumentException("This local file requires its original verified context.")
        }
        session.replacementBuffer?.let { buffer ->
            check(!buffer.closing && !buffer.transferred && buffer.controller == null &&
                session.ownership === buffer.candidateSession.ownership && session.url == buffer.candidateSession.url)
        }
        if (!standby) AudioOffsetStore.activate(audioOffsetKey)
        // Passthrough only where the output takes it (not Bluetooth); PCM otherwise.
        val renderers = session.replacementBuffer?.parts?.renderers ?: PassthroughGuard.renderersFactory(context, decodedAudio.takeIf { audioRecoveryEligible(session, true, true) }) { AudioOffsetStore.value(audioOffsetKey) }
            // Platform decoders first; the FFmpeg extension (TrueHD, DTS…) when none can.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(AudioDecoderFallback.selector)
        val mediaHttp = if (derivedMediaAccess != null) ApiClient.http.withoutAutomaticReplay()
            .newBuilder().addInterceptor(derivedMediaAccess.interceptor).addNetworkInterceptor(derivedMediaAccess.requestInterceptor).build()
            else ApiClient.http.newBuilder().addInterceptor(mediaAccess.interceptor).addNetworkInterceptor(mediaAccess.requestInterceptor).build()
        val http = OkHttpDataSource.Factory(mediaHttp).setDefaultRequestProperties(backendMediaHeaders)
        // HTTP(S) through OkHttp as before; `file://` (a phone's downloaded copy) from disk.
        val dataSource = derivedMediaAccess?.dataSourceFactory(http) ?: DefaultDataSource.Factory(context, http)
        val playerBuilder = ExoPlayer.Builder(context, renderers)
            // Dolby Vision Profile 7 rewritten for this box's decoders (player/dv7).
            .setMediaSourceFactory(session.replacementBuffer?.parts?.sources ?: DefaultMediaSourceFactory(dataSource, Dv7Playback.extractorsFactory()).setLoadErrorHandlingPolicy(derivedMediaAccess?.loadPolicy ?: mediaAccess.loadPolicy))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !standby,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
        player = session.replacementBuffer?.buildActualPlayer(playerBuilder) ?: playerBuilder.build()
        session.replacementBuffer?.retainActualPlayer(player)
        if (standby) player.volume = 0f
        player.addListener(listener)
        player.addListener(stallLog)
        progress = ProgressReporter(player, session, scope, context = dataContext) { autoSwitch.trigger(it) }
        if (recordingStart != null) {
            val source = requireNotNull(derivedSource)
            recordingProgress = RecordingProgressReporter(requireNotNull(source.progressResource), requireNotNull(source.progressReceipt),
                com.strmr.tv.live.ApiRecordingProgressGateway, source::allowed,
                { player.currentPosition }, { player.isPlaying }, failed = {
                    notice.value = "Progress saving stopped. Return and read current progress before trying again."
                })
        }
        recordingHls = session.growingRestart?.let { RecordingHls(player, scope, session.liveSessionId, it, dataContext, ownership, ::canUsePlayerAux,
            request.title.titleId, { reason -> denyVod(reason) }) }
        liveRetune = session.retuneLive?.let {
            LiveRetune(player, scope, session.liveSessionId, session.liveChannel, it, ::liveGaveUp, session.liveStream,tuned = { next ->
                timeshift.value = next.timeshift
                liveRetune?.channel?.id?.let { id ->
                    val key = AudioOffsetKeys.live(id)
                    if (key != audioOffsetKey) { audioOffsetKey = key; AudioOffsetStore.activate(key) }
                }
            },expectedContext=dataContext,owner=ownership,playerCurrent=::canUsePlayerAux,onDenied={ reason -> denyVod(reason) },denialTitleId=request.title.titleId)
        }
        catchup = session.liveChannel?.takeIf { session.isLive && session.liveStream?.programmeLimit == null && LiveCatchups.window(it) > 0 }?.let { CatchupJoin(this, it) }
        // Debug builds log every player event under the EventLogger tag.
        if (AppBuild.DEBUG) player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
        // A growing recording's playlist plays from its start (0), not its live edge.
        session.replacementBuffer?.let { player.setMediaSource(it.loadedSource, it.targetMs) }
            ?: recordingHls?.let { player.setMediaItem(it.mediaItem(session.url), 0) }
            ?: if (derivedSource != null) {
                val item = androidx.media3.common.MediaItem.Builder().setUri(session.url)
                    .setMimeType(androidx.media3.common.MimeTypes.VIDEO_MATROSKA).build()
                if (recordingStart != null) {
                    player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                    recordingStart.install { positionMs -> player.setMediaItem(item, positionMs) }
                } else player.setMediaItem(item, 0)
            }
            else player.setMediaItem(Timeshift.mediaItem(session.url, timeshift.value))
        if (!standby) request.startPosition?.let { player.seekTo((it * 1000).toLong()) }
        // Up Next / Switch Source: the previous playback's speed (live always plays at 1x).
        if (!standby) session.explicitAudioLanguage?.let { rememberAudioLanguage(it) }
        if (request.speed != 1f && !session.isLive) setSpeed(request.speed)
        if (enforceProgrammeLimit()) {
            player.playWhenReady = false
            player.prepare()
            if (startGate == null) startBarrier.releaseDisplay()
            else startGate.invoke(startBarrier::releaseDisplay)
        }
        programmeTimer = programmeClock?.let { clock -> scope.launch { clock.watch() } }
        if (recordingStart != null) recordingStartTimeout = scope.launch {
            delay(15_000)
            if (!recordingStart.started && !released) failRecordingProgressStart()
        }
        if (!standby) startActiveHelpers()

    }

    private fun startActiveHelpers() {
        autoSwitch.start()
        thumbnails?.start(scope) {
            val video = tracks.value.ofType(C.TRACK_TYPE_VIDEO).firstOrNull { it.selected }?.format
            PlaybackThumbnails.Context(duration.value, player.currentPosition, buffering.value,
                hdr = video?.isHdr == true, dolbyVision = video?.isDolbyVision == true)
        }
    }
    internal fun prepareReplacementActivation() {
        check(standby && !released && player.playbackState == Player.STATE_READY && player.playerError == null)
        ownership.requireCurrent()
        player.setAudioAttributes(player.audioAttributes, true)
        player.volume = 1f
    }
    internal fun activateReplacement() {
        val buffer = requireNotNull(session.replacementBuffer)
        check(standby && buffer.transferred && PlaybackCoordinator.pending === session && !released)
        ownership.requireCurrent()
        decodedAudio.restart(); decodedAudioPolicy.reset()
        standby = false
        AudioOffsetStore.activate(audioOffsetKey)
        runCatching {
            upNext = createUpNext(); thumbnails = createThumbnails()
            videoFormat.value?.let(onVideoFormat)
            online.tracksReady(player.currentTracks, prefs.subtitlePick)
            startActiveHelpers()
        }.onFailure { notice.value = "The source switched. Some playback helpers are unavailable." }
        runCatching { startBarrier.startIfAllowed() }.onFailure {
            error.value = "The buffered source could not start. Choose another source."
        }
    }
    internal fun requireReplacementOwner() {
        check(canUsePlayerAux() && PlaybackCoordinator.pending === session && session.reportsProgress &&
            !session.isLive && !session.localFile && session.recordingDerived == null)
        ownership.requireCurrent()
    }
    internal fun replacementCarriedTracks() = CarriedTracks.capture(player.currentTracks, player.trackSelectionParameters,
        external.selected.value).copy(audioLanguage = null)
    internal fun replacementController(context: Context, replacement: PlayerSession) = PlayerController(context, replacement, onVideoFormat = onVideoFormat)
    internal fun replacementMediaParts(context: Context, replacement: PlayerSession,
        bounded: (androidx.media3.datasource.DataSource.Factory) -> androidx.media3.datasource.DataSource.Factory,
        callEvents: okhttp3.EventListener.Factory): ReplacementMediaParts {
        requireReplacementOwner(); check(replacement.ownership === ownership)
        val headers = ownership.use { ApiClient.mediaHeaders }
        val access = VodMediaAccess(ownership.use { ApiClient.baseUrl }, true, headers,
            replacement.request.title.titleId, dataContext, ownership::requireCurrent, profileId)
        val client = ApiClient.http.withoutAutomaticReplay().newBuilder().followRedirects(true).followSslRedirects(false)
            .addInterceptor(access.interceptor).addNetworkInterceptor(access.requestInterceptor).eventListenerFactory(callEvents).build()
        val source = bounded(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers)))
        val observation = DecodedAudioObservation()
        val renderers = PassthroughGuard.renderersFactory(context, observation) { AudioOffsetStore.value(AudioOffsetKeys.of(replacement)) }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true).setMediaCodecSelector(AudioDecoderFallback.selector)
        val capabilityRenderers = PassthroughGuard.renderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true).setMediaCodecSelector(AudioDecoderFallback.selector)
        return ReplacementMediaParts(DefaultMediaSourceFactory(source, Dv7Playback.extractorsFactory()).setLoadErrorHandlingPolicy(access.loadPolicy), renderers, capabilityRenderers, access, observation)
    }

    /** Retire the entire old scope before queued helpers can read a replacement identity. */
    private val contextWatch=scope.launch {
        ProfileDataContext.changes.collect {
            if(!session.canOpenPlayer()) {decodedAudio.close();decodedAudioPolicy.reset();cancelRecordingParts();startup.end(StartupOutcome.OWNER_LOST);progress.discardFuture();player.stop();ended.value=true;scope.cancel()}
        }
    }

    /** Position for the chrome, skip segments and up next. */
    private val clock: Job = scope.launch {
        while (isActive) {
            if (standby) { delay(250); continue }
            if(!session.playbackAllowed() || session.recordingPart?.wasReplaced()==true) {cancelRecordingParts();startup.end(StartupOutcome.OWNER_LOST);progress.discardFuture();player.stop();ended.value=true;break}
            if (postPlay.value != null) { delay(250); continue }
            position.value = player.currentPosition
            session.recordingPart?.tick(position.value)
            duration.value = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            if (timeshift.value && catchup?.inArchive?.value != true) behindLive.value = Timeshift.behindLiveMs(player)
            catchup?.tick()
            observeAudioAvailability()
            upNext?.tick(position.value, duration.value, player.playWhenReady && (player.isPlaying || player.playbackState == Player.STATE_ENDED))
            delay(250)
        }
    }

    /** Seeking is possible: anything but live, or live with a timeshift window or catch-up. */
    val seekable: Boolean get() = recordingStart?.started != false && (!session.isLive || timeshift.value || catchup?.timeline?.value != null)

    /** The bar's programme on a catch-up channel; scrubbing then works in ms from its start. */
    private val programme: ProgrammeTimeline? get() = catchup?.timeline?.value

    /** Where a scrub starts, and how far it can go, in the bar's terms. */
    fun scrubPosition(): Long = programme?.positionMs ?: player.currentPosition
    fun scrubEnd(): Long = programme?.availableMs ?: duration.value.let { if (it > 0) it - 1_000 else Long.MAX_VALUE }

    /** Remote rewind / fast forward. */
    fun skip(deltaMs: Long) = seekTo(scrubPosition() + deltaMs)

    /** Back to the live edge: a timeshift channel's, or the channel again from its catch-up archive. */
    fun goLive() {
        catchup?.let { return it.goLive() }
        if (!timeshift.value) return
        AppLog.i("Player", "timeshift: back to live from ${behindLive.value / 1000}s behind")
        player.seekToDefaultPosition()
        player.play()
        behindLive.value = 0
    }

    fun seekTo(ms: Long) {
        if (!seekable) return
        programme?.let { return catchup!!.seek(ms.coerceIn(0, it.availableMs)) }
        val end = duration.value.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0, end))
        position.value = player.currentPosition
    }

    fun setSpeed(value: Float) {
        player.setPlaybackSpeed(value)
        speed.value = value
    }

    /** A percentage resume (Trakt import) once the duration is known. */
    private fun applyPercentStart() {
        if (startApplied) return
        startApplied = true
        val start = request.start as? StartPoint.Percent ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        player.seekTo((duration * start.value / 100).toLong())
    }

    /** A live channel that couldn't be kept on air closes, its message over the page beneath (as on tvOS). */
    private fun liveGaveUp(message: String) {
        PlaybackCoordinator.errorMessage.value = message
        ended.value = true
    }

    /** Also called when a background/PiP Activity resumes; the original deadline never moves. */
    fun enforceProgrammeLimit(): Boolean = programmeClock?.active() ?: true

    private fun stopChildProgramme() {
        if (released) return
        liveRetune?.release()
        progress.discardFuture()
        player.pause()
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()
        PlaybackCoordinator.errorMessage.value = "This programme has ended. Choose a programme again for a new guide decision."
        ended.value = true
    }

    fun report(paused: Boolean = false, ended: Boolean = false, closing: Boolean = false) {
        if (standby) return
        val recording = recordingProgress
        if (recording != null) {
            val positionMs = if (ended) requireNotNull(derivedSource?.observation?.descriptor?.durationMs) else player.currentPosition
            recording.offer(positionMs)
        } else progress.report(paused, ended, closing)
    }

    /** Wait for the final report, including its canonical server item identity. */
    val storedCompletedItemId: String? get() = progress.lastStoredItemId ?: request.progressItemId
    suspend fun completedItemId(): String? {
        withTimeoutOrNull(2_000) { progress.lastReport?.join() }
        return storedCompletedItemId
    }

    /**
     * Switch Source: the position and the playing audio / subtitle languages go with the
     * request; the activity closes this playback (saving the position) and starts the other.
     */
    fun switchSource(source: SourceResult) {
        cancelAudioLanguageCheck()
        if(vodDenied.value!=null || dataContext!=ProfileDataContext.current)return
        audioRecovery.dismiss()
        val position = player.currentPosition
        val carried = CarriedTracks.capture(player.currentTracks, player.trackSelectionParameters, external.selected.value)
        AppLog.i("SourceSwitch", "switch from \"${session.releaseName}\" to \"${source.title}\" at ${position / 1000}s carried=$carried")
        switchTo.value = SourceSwitchRequest(SourceSwitchRules.resumeAt(request, position).copy(speed = speed.value), source, carried)
    }

    /** Saves the position and lets go of the player; returns the last report, still finishing. */
    fun release(): Job? {
        if (released) return progress.lastReport
        startup.end(StartupOutcome.RELEASED)
        released=true
        programmeTimer?.cancel()
        decodedAudio.close(); decodedAudioPolicy.reset()
        val partPosition=player.currentPosition
        recordingStartTimeout?.cancel(); recordingStartTimeout = null
        val recordingClosing = recordingProgress?.close(
            if (ended.value && recordingStart?.started == true) derivedSource?.observation?.descriptor?.durationMs else partPosition,
            afterCleanup = { derivedSource?.release() })
        recordingClosing?.let(PlaybackCoordinator::retainRecordingProgressClose)
        if (!ended.value && advanceTo.value == null && postPlay.value == null) report(paused = true, closing = true)
        closeReleaseReports()
        clock.cancel()
        contextWatch.cancel()
        online.cancel()
        external.cancel()
        thumbnails?.cancel()
        upNext?.cancel()
        autoSwitch.cancel()
        progress.stop()
        // Each stops its server session.
        recordingHls?.release()
        catchup?.release()
        liveRetune?.release()
        stallLog.summary()
        player.removeListener(listener)
        player.removeListener(stallLog)
        player.release()
        session.replacementBuffer?.takeUnless { it.closing }?.close()
        session.recordingPart?.release(partPosition)
        if (recordingClosing == null) derivedSource?.release()
        // The dedicated recording actor owns its actual completion independently of this scope.
        // Let the last catalogue report finish on its own.
        MainScope().launch { delay(5_000); scope.cancel() }
        return recordingClosing ?: progress.lastReport
    }
}

private val Format.isDolbyVision: Boolean
    get() = sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION ||
        codecs?.let { it.startsWith("dvh") || it.startsWith("dva") } == true

private val Format.isHdr: Boolean
    get() = colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }
