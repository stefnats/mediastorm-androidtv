package com.strmr.tv.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.*
import com.strmr.tv.app.AppLog
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.EffectiveTracks
import com.strmr.tv.player.ManualSourceTracks
import com.strmr.tv.player.ManualTrackChoice
import com.strmr.tv.player.TrackOverrides
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Per-visit explicit track probes, bound to the result response and its original identity. */
class SourceTracks(
    private val scope: CoroutineScope,
    private val expected: Long = ProfileDataContext.current,
    private val readProbe: suspend (SourceResult, Long) -> TrackProbe = { result, context -> ApiClient.probeTracks(result,context) },
    private val readDefaults: suspend (Long) -> TrackDefaults = { context ->
        val playback = ApiClient.playbackSettings(context)
        val effective = EffectiveTracks.resolve(null,playback,TrackOverrides.audioLanguage,TrackOverrides.subtitleLanguage,
            TrackOverrides.subtitleMode,TrackOverrides.losslessAudio)
        TrackDefaults(effective.audioLanguage,effective.subtitleLanguage,effective.subtitleMode,effective.allowedLanguages,effective.losslessAudio)
    },
    private val log: (String) -> Unit = { AppLog.i("ManualTracks",it) },
    private val onProbeChanged:(SourceResult)->Unit={},
    private val onProbeError:(SourceResult,Exception)->Unit={_,_->},
) {
    private data class Key(val index: Int, val response: String,val scope:String?)
    private fun key(result: SourceResult) = Key(result.index,result.raw.toString(),result.responseScope)
    private val probes = mutableStateMapOf<Key, ProbeState>()
    private val choices = mutableStateMapOf<Key, ManualTrackChoice>()
    private val tasks = mutableMapOf<Key, Job>()
    private val generations = mutableMapOf<Key, Long>()
    private val expectedFiles=mutableMapOf<Key,Pair<String,String>>()
    private var opened by mutableStateOf<SourceResult?>(null)
    private var defaults by mutableStateOf<TrackDefaults?>(null)
    private var disposed = false
    private var defaultsTask: Job? = null
    private var contextTask: Job? = null
    init { contextTask = scope.launch { ProfileDataContext.changes.first { it != expected }; dispose() } }
    private fun current() = !disposed && ProfileDataContext.current == expected

    fun hasChoice(result: SourceResult) = current() && choices[key(result)]?.isEmpty == false

    /** Only this existing explicit user action can start a track probe. */
    fun open(result: SourceResult) {
        if (!current()) return
        opened = result
        if (defaults == null && defaultsTask?.isActive != true) defaultsTask = scope.launch {
            if (!current()) return@launch
            val loaded = try { readDefaults(expected) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            ensureActive()
            if (current()) defaults = loaded
        }
        val known = probes[key(result)]
        if (known == null || known.probe is TrackProbe.Failed) probe(result)
    }

    private fun probe(result: SourceResult) {
        if (!current()) return
        val key = key(result)
        tasks.remove(key)?.cancel()
        val generation = (generations[key] ?: 0) + 1
        generations[key] = generation
        choices.remove(key)
        probes[key] = ProbeState(null)
        tasks[key] = scope.launch {
            if (!current()) return@launch
            val loaded = try { readProbe(result,expected)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if(current() && generations[key]==generation)onProbeError(result,error)
                TrackProbe.Failed("Track metadata could not be loaded. Try again.")
            }
            ensureActive()
            if (!current() || generations[key] != generation) return@launch
            log(if (loaded is TrackProbe.Tracks) "Explicit track metadata received" else "Explicit track metadata unavailable")
            probes[key] = ProbeState(loaded)
            onProbeChanged(result)
        }
    }

    /** A new server-associated input file retires old track numbers without starting another probe. */
    fun reconcile(result:SourceResult,expectedFileVersion:String?,viewId:String="legacy") {
        if(!current() || expectedFileVersion==null)return
        val key=key(result);val expectedFile=viewId to expectedFileVersion
        if(expectedFiles[key]==expectedFile)return
        expectedFiles[key]=expectedFile
        val state=probes[key] ?: return
        val tracks=state.probe as? TrackProbe.Tracks
        if(tracks?.mediaEvidence?.takeIf {it.probed!=null && it.hasValidVersionIdentity()}?.sourceVersion==expectedFileVersion)return
        generations[key]=(generations[key] ?: 0)+1
        tasks.remove(key)?.cancel();probes.remove(key);choices.remove(key)
        if(opened?.let(::key)==key)opened=null
    }

    fun manualFor(result: SourceResult): ManualSourceTracks? {
        if (!current()) return null
        val choice = choices[key(result)]?.takeIf { !it.isEmpty } ?: return null
        val tracks = probes[key(result)]?.probe as? TrackProbe.Tracks ?: return null
        return ManualSourceTracks(choice,tracks.audio,tracks.subtitles)
    }
    val openResult: SourceResult? get() = opened.takeIf { current() }
    fun release(result: SourceResult): String = (result.attributes["raw_title"] ?: result.title).lineSequence().firstOrNull() ?: result.title
    fun probeState(result: SourceResult): ProbeState = if (current()) probes[key(result)] ?: ProbeState(null) else ProbeState(TrackProbe.Failed("Source context changed."))
    val trackDefaults: TrackDefaults? get() = defaults.takeIf { current() }
    fun choice(result: SourceResult): ManualTrackChoice = if (current()) choices[key(result)] ?: ManualTrackChoice() else ManualTrackChoice()
    private fun accepts(result: SourceResult, snapshot: ProbeState): Boolean =
        current() && opened?.let(::key) == key(result) && probes[key(result)] === snapshot
    fun choose(result: SourceResult, choice: ManualTrackChoice, snapshot: ProbeState = probeState(result)) {
        if (accepts(result,snapshot)) choices[key(result)] = choice
    }
    fun retry(result: SourceResult, snapshot: ProbeState = probeState(result)) { if (accepts(result,snapshot)) probe(result) }
    /** A loaded panel acts only on the exact response it displayed, including legacy probes. */
    fun play(result: SourceResult, snapshot: ProbeState, action: (SourceResult, ManualSourceTracks?) -> Unit) {
        if (!accepts(result,snapshot)) return
        val manual = manualFor(result)
        close()
        action(result,manual)
    }
    fun close() {
        opened?.let { result ->
            val key = key(result)
            if (tasks[key]?.isActive == true) {
                generations[key] = (generations[key] ?: 0) + 1
                tasks.remove(key)?.cancel()
                probes.remove(key)
            }
        }
        opened = null
    }
    fun dispose() {
        disposed = true
        opened = null
        defaultsTask?.cancel()
        tasks.values.forEach { it.cancel() }
        tasks.clear(); probes.clear(); choices.clear(); expectedFiles.clear(); defaults = null
        contextTask?.cancel()
    }
}
