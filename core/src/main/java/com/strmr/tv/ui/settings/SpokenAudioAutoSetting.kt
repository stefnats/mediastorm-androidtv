package com.strmr.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

internal const val SPOKEN_AUDIO_AUTO_PATH = "playback.autoCheckAudioLanguage"

data class SpokenAudioAutoSnapshot(
    val effective: Boolean, val inherited: Boolean, val overrideValue: Boolean?,
    val supported: Boolean, val editable: Boolean,
) {
    val choices get() = listOf("Follow server (${if (inherited) "On" else "Off"})", "Off", "On")
    val selected get() = when (overrideValue) { null -> 0; false -> 1; true -> 2 }
    val label get() = if (!editable) if (effective) "On · Server managed" else "Off · Server managed" else choices[selected]
}

internal fun spokenAudioAutoSnapshot(server: JsonObject, profile: JsonObject, overrides: JsonObject): SpokenAudioAutoSnapshot {
    fun JsonObject.flag() = ((get("playback") as? JsonObject)?.get("autoCheckAudioLanguage") as? JsonPrimitive)?.booleanOrNull
    fun JsonObject.paths(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    val inherited = server.flag()
    val actual = profile.flag()
    val supported = inherited != null && actual != null
    val overridden = SPOKEN_AUDIO_AUTO_PATH in overrides.paths("overriddenPaths")
    return SpokenAudioAutoSnapshot(actual ?: false, inherited ?: false, actual.takeIf { overridden }, supported,
        supported && SPOKEN_AUDIO_AUTO_PATH in server.paths("userEditableSettings"))
}

internal fun spokenAudioAutoPatch(value: Boolean?) = buildJsonObject {
    put("path", SPOKEN_AUDIO_AUTO_PATH)
    if (value == null) put("reset", true) else put("value", value)
}

interface SpokenAudioAutoGateway {
    fun isCurrent(): Boolean
    suspend fun read(): SpokenAudioAutoSnapshot
    suspend fun write(value: Boolean?): SpokenAudioAutoSnapshot
}

class ServerSpokenAudioAutoGateway : SpokenAudioAutoGateway {
    private val expected = ProfileDataContext.current
    private val path = ApiClient.profilePath
    override fun isCurrent() = expected == ProfileDataContext.current
    private fun checkCurrent() { if (!isCurrent()) throw CancellationException("Playback profile changed") }
    private suspend fun get(path: String): JsonObject {
        checkCurrent()
        return ApiClient.send<JsonObject>("GET", path, timeoutMs = 5_000, expectedContext = expected).also { checkCurrent() }
    }
    override suspend fun read(): SpokenAudioAutoSnapshot {
        val server = get("/api/settings")
        val profile = get("$path/settings")
        val overrides = get("$path/settings/frontend")
        return spokenAudioAutoSnapshot(server, profile, overrides)
    }
    override suspend fun write(value: Boolean?): SpokenAudioAutoSnapshot {
        checkCurrent()
        // Recheck the current server permission; never fall back to an entire settings PUT.
        check(read().editable) { "This setting is managed by the server" }
        ApiClient.send<JsonObject>("PATCH", "$path/settings/frontend", body = spokenAudioAutoPatch(value),
            timeoutMs = 5_000, expectedContext = expected)
        checkCurrent()
        return read()
    }
}

data class SpokenAudioAutoState(val snapshot: SpokenAudioAutoSnapshot? = null, val busy: Boolean = true, val error: String? = null)

class SpokenAudioAutoEditor(private val gateway: SpokenAudioAutoGateway) {
    private val mutable = MutableStateFlow(SpokenAudioAutoState())
    val state: StateFlow<SpokenAudioAutoState> = mutable
    private var closed = false
    private var running = false
    fun close() { closed = true }
    private fun checkCurrent() { if (closed || !gateway.isCurrent()) throw CancellationException("Playback profile changed") }
    suspend fun load() {
        if (running) return
        checkCurrent(); running = true
        mutable.value = SpokenAudioAutoState()
        try {
            val snapshot = gateway.read(); checkCurrent()
            mutable.value = SpokenAudioAutoState(snapshot, busy = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            checkCurrent(); mutable.value = SpokenAudioAutoState(busy = false, error = "Could not load spoken language preference. Select to reload.")
        } finally { running = false }
    }
    suspend fun save(value: Boolean?) {
        val original = mutable.value.snapshot ?: return
        if (running || mutable.value.error != null || !original.editable || original.overrideValue == value) return
        checkCurrent(); running = true
        mutable.value = mutable.value.copy(busy = true)
        try {
            val confirmed = gateway.write(value); checkCurrent()
            check(confirmed.supported && confirmed.overrideValue == value) { "Preference was not confirmed" }
            mutable.value = SpokenAudioAutoState(confirmed, busy = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            checkCurrent(); mutable.value = SpokenAudioAutoState(original, busy = false,
                error = "Could not confirm spoken language preference. Reload before trying again.")
        } finally { running = false }
    }
}

@Composable
fun rememberSpokenAudioAutoEditor(): SpokenAudioAutoEditor {
    val editor = remember(ProfileDataContext.current) { SpokenAudioAutoEditor(ServerSpokenAudioAutoGateway()) }
    LaunchedEffect(editor) { editor.load() }
    DisposableEffect(editor) { onDispose { editor.close() } }
    return editor
}
