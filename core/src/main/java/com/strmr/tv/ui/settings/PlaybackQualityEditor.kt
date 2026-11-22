package com.strmr.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class QualityScope(val label: String) { PROFILE("profile"), DEVICE("this device") }
enum class QualityField(val key: String, val label: String, val description: String) {
    MINIMUM("minResolution", "Minimum playback quality", "Require this quality in the actual file before playback."),
    MAXIMUM("maxResolution", "Maximum resolution", "Upper resolution limit for source selection.");
    val path get() = "filtering.$key"
    fun label(value: String) = when (value) { "" -> if (this == MINIMUM) "No minimum" else "No maximum"; "2160p" -> "4K (2160p)"; else -> value }
}

data class QualitySettingChoice(val value: String?, val label: String)
data class QualitySettingRow(val scope: QualityScope, val field: QualityField, val overrideValue: String?, val inherited: String, val editable: Boolean,
    val effectiveValue: String = inherited) {
    val title get() = "${this.field.label} · ${scope.label}"
    val choices get(): List<QualitySettingChoice> {
        val standard = listOf("", "480p", "720p", "1080p", "2160p")
        val current = overrideValue?.takeIf { it !in standard }
        return listOf(QualitySettingChoice(null, "Follow ${if (scope == QualityScope.PROFILE) "server" else "profile"} (${this.field.label(inherited)})")) +
            standard.map { QualitySettingChoice(it, this.field.label(it)) } +
            listOfNotNull(current?.let { QualitySettingChoice(it, "${this.field.label(it)} (current)") })
    }
    val selected get() = choices.indexOfFirst { it.value == overrideValue }.coerceAtLeast(0)
    val valueLabel get() = if (scope == QualityScope.PROFILE && !editable) this.field.label(effectiveValue)
        else overrideValue?.let(this.field::label) ?: choices.first().label
    val description get() = this.field.description + if (editable) "" else " Managed by the server."
}

data class PlaybackQualitySnapshot(
    val server: Map<String, String>,
    val profile: Map<String, String>,
    val device: Map<String, String>,
    val profileOverrides: Set<String>,
    val editable: Set<String>,
) {
    fun row(scope: QualityScope, field: QualityField) = QualitySettingRow(scope, field,
        if (scope == QualityScope.DEVICE) device[field.key] else profile[field.key]?.takeIf { field.path in profileOverrides },
        (if (scope == QualityScope.PROFILE) server[field.key] else profile[field.key]).orEmpty(), field.path in editable,
        (if (scope == QualityScope.PROFILE) profile[field.key] else device[field.key] ?: profile[field.key]).orEmpty())
    val rows get() = QualityScope.entries.flatMap { scope -> QualityField.entries.map { row(scope, it) } }
}

interface PlaybackQualityGateway {
    fun isCurrent(): Boolean
    suspend fun read(): PlaybackQualitySnapshot
    suspend fun write(scope: QualityScope, field: QualityField, value: String?): PlaybackQualitySnapshot
}

/** Only the existing single-field endpoint changes; null resets inheritance, empty removes the bound. */
internal fun qualitySettingPatch(field: QualityField, value: String?) = buildJsonObject {
    put("path", field.path)
    if (value == null) put("reset", true) else put("value", value)
}

class ServerPlaybackQualityGateway : PlaybackQualityGateway {
    private val expected = ProfileDataContext.current
    private val profile = ApiClient.profileId
    private val profilePath = ApiClient.profilePath
    private val clientPath = "/api/clients/${ApiClient.segment(Config.clientId)}/settings"
    override fun isCurrent() = expected == ProfileDataContext.current
    private fun checkCurrent() { if (!isCurrent()) throw CancellationException("Playback profile changed") }
    private suspend fun read(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        checkCurrent()
        return ApiClient.send<JsonObject>("GET", path, query, timeoutMs = 5_000, expectedContext = expected).also { checkCurrent() }
    }
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    private fun JsonObject.bounds() = QualityField.entries.mapNotNull { field ->
        (get(field.key) as? JsonPrimitive)?.contentOrNull?.let { field.key to it }
    }.toMap()
    override suspend fun read(): PlaybackQualitySnapshot {
        val server = read("/api/settings")
        val profile = read("$profilePath/settings")
        val overrides = read("$profilePath/settings/frontend")
        val device = read(clientPath, mapOf("userId" to this.profile))
        return PlaybackQualitySnapshot(
            (server["filtering"] as? JsonObject).orEmpty().let { JsonObject(it).bounds() },
            (profile["filtering"] as? JsonObject).orEmpty().let { JsonObject(it).bounds() },
            device.bounds(), overrides.strings("overriddenPaths"), server.strings("userEditableSettings"),
        )
    }
    override suspend fun write(scope: QualityScope, field: QualityField, value: String?): PlaybackQualitySnapshot {
        checkCurrent()
        val path = if (scope == QualityScope.PROFILE) "$profilePath/settings/frontend" else "$clientPath/frontend"
        val query = if (scope == QualityScope.DEVICE) mapOf("userId" to profile) else emptyMap()
        ApiClient.send<JsonObject>("PATCH", path, query, body = qualitySettingPatch(field, value), timeoutMs = 5_000, expectedContext = expected)
        checkCurrent()
        return read()
    }
}

data class PlaybackQualityState(val snapshot: PlaybackQualitySnapshot? = null, val loading: Boolean = true, val saving: Boolean = false, val error: String? = null)

class PlaybackQualityEditor(private val gateway: PlaybackQualityGateway) {
    private val mutable = MutableStateFlow(PlaybackQualityState())
    val state: StateFlow<PlaybackQualityState> = mutable
    private var closed = false
    private var busy = false
    fun close() { closed = true }
    private fun checkCurrent() { if (closed || !gateway.isCurrent()) throw CancellationException("Playback profile changed") }
    suspend fun load() {
        if (busy) return
        checkCurrent(); busy = true
        mutable.value = PlaybackQualityState()
        try {
            val snapshot = gateway.read(); checkCurrent()
            mutable.value = PlaybackQualityState(snapshot, loading = false)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            checkCurrent(); mutable.value = PlaybackQualityState(loading = false, error = "Could not load playback quality. Select to retry.")
        } finally { busy = false }
    }
    suspend fun save(scope: QualityScope, field: QualityField, value: String?) {
        val snapshot = mutable.value.snapshot ?: return
        if (busy || mutable.value.error != null || !snapshot.row(scope, field).editable) return
        if (snapshot.row(scope, field).overrideValue == value) return
        require(value == null || snapshot.row(scope, field).choices.any { it.value == value })
        checkCurrent(); busy = true
        mutable.value = mutable.value.copy(saving = true)
        try {
            val confirmed = gateway.write(scope, field, value); checkCurrent()
            check(confirmed.row(scope, field).overrideValue == value) { "Playback quality was not confirmed" }
            mutable.value = PlaybackQualityState(confirmed, loading = false)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            checkCurrent(); mutable.value = PlaybackQualityState(snapshot, loading = false, error = "Could not confirm playback quality. Select to reload before retrying.")
        } finally { busy = false }
    }
}

@Composable
fun rememberPlaybackQualityEditor(): PlaybackQualityEditor {
    val editor = remember(ProfileDataContext.current) { PlaybackQualityEditor(ServerPlaybackQualityGateway()) }
    LaunchedEffect(editor) { editor.load() }
    DisposableEffect(editor) { onDispose { editor.close() } }
    return editor
}
