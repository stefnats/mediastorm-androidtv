package com.strmr.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.strmr.tv.app.App
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Lip-sync: how much later (positive) or earlier (negative) audio is heard than the picture,
 * for a soundbar / AVR whose decode (Atmos, DTS) adds latency the platform doesn't report.
 *
 * Media3 has no audio-delay API, so the offset is applied at the audio clock. ExoPlayer drives
 * video from the audio sink's position: the frame with timestamp `t` is shown when the sink
 * says `t`. [AudioOffsetSink] reports `real + offset`, so when the audio sample at `a` is
 * heard the clock reads `a + offset` and video is already showing frame `a + offset`: audio
 * reaches the viewer `offset` late relative to the picture. Positive = audio delayed. It acts
 * on the clock only, so it works the same for PCM and bitstream passthrough.
 */
object AudioOffset {
    const val MAX_MS = 2000
    const val STEP_MS = 50

    fun clamp(ms: Int): Int = ms.coerceIn(-MAX_MS, MAX_MS)

    /**
     * The position the sink reports for the real `positionUs` with `offsetMs`. Not-set
     * ([AudioSink.CURRENT_POSITION_NOT_SET]) passes through; the result never goes below 0.
     * (The audio renderer itself keeps the clock from going backwards outside a discontinuity,
     * so lowering the offset live holds the picture briefly rather than rewinding it.)
     */
    fun adjust(positionUs: Long, offsetMs: Int): Long {
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || offsetMs == 0) return positionUs
        return (positionUs + clamp(offsetMs) * 1000L).coerceAtLeast(0L)
    }

    /** "0 ms", "+150 ms", "−200 ms". */
    fun label(ms: Int): String = when {
        ms > 0 -> "+$ms ms"
        ms < 0 -> "−${-ms} ms"
        else -> "0 ms"
    }
}

/**
 * Which stream an offset belongs to: a channel's (`live:<channelId>`, catch-up of it too), a
 * recording's (`rec:<recordingId>`), else the movie's or episode's progress identity
 * (`vod:<progressItemId>`, or `vod:<titleId>:SxxEyy` for an episode without one) so another
 * source of the same title keeps it. Null (trailers) is never saved.
 */
object AudioOffsetKeys {
    fun live(channelId: String) = "live:$channelId"
    fun recording(recordingId: String) = "rec:$recordingId"

    fun vod(request: PlaybackRequest): String? {
        if (request.title.titleId == "trailer") return null
        val id = request.progressItemId ?: request.episode?.let { "${request.title.titleId}:${it.code}" } ?: return null
        return "vod:$id"
    }

    /** The session's key: one the coordinator set (live, catch-up, recording), else the title's. */
    fun of(session: PlayerSession): String? =
        session.audioOffsetKey ?: session.liveChannel?.let { live(it.id) } ?: if (session.reportsProgress) vod(session.request) else null
}

/**
 * Saved offsets by stream key, most recently set last; at most [cap] (the oldest go first).
 * 0 is never stored: Reset removes the key.
 */
class AudioOffsetMemory(initial: Map<String, Int> = emptyMap(), private val cap: Int = CAP) {
    private val map = LinkedHashMap<String, Int>(initial)

    val entries: Map<String, Int> get() = LinkedHashMap(map)

    operator fun get(key: String?): Int = key?.let { map[it] }?.let(AudioOffset::clamp) ?: 0

    fun put(key: String, ms: Int) {
        map.remove(key)
        val v = AudioOffset.clamp(ms)
        if (v != 0) map[key] = v
        while (map.size > cap) map.remove(map.keys.first())
    }

    companion object { const val CAP = 500 }
}

/**
 * The playing stream's audio offset, read live by the player's sink. [activate] at playback
 * start (and when a live retune lands on another channel) loads that stream's saved value;
 * menu changes save under it, in the `strmr` prefs as one JSON map (`audioOffsets`).
 */
object AudioOffsetStore {
    private const val PREFS_KEY = "audioOffsets"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Int.serializer())
    private val prefs by lazy { App.context.getSharedPreferences("strmr", Context.MODE_PRIVATE) }
    /** Tests replace persistence; null = the app's prefs. */
    internal var persistence: Persistence? = null

    interface Persistence { fun read(): String?; fun write(value: String) }

    private var memory: AudioOffsetMemory? = null
    var key: String? = null
        private set

    /** What the audio sink reads (from the playback thread). */
    @Volatile var currentMs: Int = 0
        private set

    /** Compose-observable copy for the player menus. */
    var offsetMs by mutableIntStateOf(0)
        private set

    /** Plays `key`'s stream: its saved offset, 0 when none (or no key). */
    @Synchronized fun activate(key: String?) {
        this.key = key
        show(memory()[key])
    }

    /** Sets (clamped) for the playing stream and saves it; playing audio follows at once. */
    @Synchronized fun set(ms: Int) {
        val v = AudioOffset.clamp(ms)
        show(v)
        val k = key ?: return
        val m = memory()
        m.put(k, v)
        val text = json.encodeToString(serializer, m.entries)
        runCatching { persistence?.write(text) ?: prefs.edit().putString(PREFS_KEY, text).apply() }
    }

    /** A standby sink reads its own stream without replacing the active menu key. */
    @Synchronized fun value(streamKey: String?): Int = if (streamKey == key) currentMs else memory()[streamKey]

    fun step(deltaMs: Int) = set(currentMs + deltaMs)

    /** Reset: back in sync, the stream's entry removed. */
    fun reset() = set(0)

    /** Forgets the loaded map (tests). */
    @Synchronized internal fun clearCache() { memory = null; key = null; show(0) }

    private fun show(v: Int) { currentMs = v; offsetMs = v }

    private fun memory(): AudioOffsetMemory = memory ?: run {
        val text = runCatching { persistence?.read() ?: prefs.getString(PREFS_KEY, null) }.getOrNull()
        val map = text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()
        AudioOffsetMemory(map).also { memory = it }
    }
}

/** Reports the wrapped sink's position shifted by [offsetMs] (see [AudioOffset]). */
@OptIn(UnstableApi::class)
class AudioOffsetSink(sink: AudioSink, private val offsetMs: () -> Int) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        AudioOffset.adjust(super.getCurrentPositionUs(sourceEnded), offsetMs())
}
