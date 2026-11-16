package com.strmr.tv.live

import android.content.Context
import com.strmr.tv.app.App
import com.strmr.tv.app.AppSession
import com.strmr.tv.app.NetworkRoutes
import com.strmr.tv.api.ApiClient
import com.strmr.tv.player.PlaybackSessionContext
import java.security.MessageDigest
import okhttp3.HttpUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last multiscreen, for Live › More › Resume multiscreen: its channels in screen order and
 * which one was audible. Device-local, per profile (the server's settings have no field for it,
 * so the Expo app's save never stuck).
 */
@Serializable
data class SavedMultiscreen(val channels: List<LiveChannel>, val audioIndex: Int = 0)

/** The pure rules behind [MultiscreenPrefs]: what is saved, its JSON, and when the warning shows. */
object MultiscreenMemory {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * What to save for `state`: the screens with a channel, in slot order, and the audible one
     * among them; null under two channels (an earlier selection then stays).
     */
    fun selection(state: MultiscreenSession.State): SavedMultiscreen? {
        val indices = state.slots.indices.filter { state.slots[it].channel != null }
        if (indices.size < 2) return null
        return SavedMultiscreen(indices.map { state.slots[it].channel!! }, maxOf(0, indices.indexOf(state.audioIndex)))
    }

    fun encode(saved: SavedMultiscreen): String = json.encodeToString(saved)

    /** A saved selection, or null if it doesn't parse or has under two channels. */
    fun decode(text: String?): SavedMultiscreen? {
        val saved = text?.let { runCatching { json.decodeFromString<SavedMultiscreen>(it) }.getOrNull() } ?: return null
        if (saved.channels.size < 2) return null
        return saved.copy(audioIndex = saved.audioIndex.coerceIn(0, saved.channels.lastIndex))
    }

    /** The stability warning before a third screen: once per device, whatever the answer was. */
    fun needsWarning(occupied: Int, shownBefore: Boolean) = occupied >= 2 && !shownBefore
}

/** [MultiscreenMemory] kept on this device. */
object MultiscreenPrefs {
    private val prefs by lazy { App.context.getSharedPreferences("strmr.multiscreen", Context.MODE_PRIVATE) }
    private const val KEY_WARNING = "warningShown"
    private val layouts by lazy { MultiscreenLayoutStorage(
        read = { prefs.getString(it, null) }, write = { key, value -> prefs.edit().putString(key, value).apply() },
    ) }

    /** The "More screens, higher load" warning has been shown on this device. */
    val warningShown: Boolean get() = prefs.getBoolean(KEY_WARNING, false)

    fun markWarningShown() = prefs.edit().putBoolean(KEY_WARNING, true).apply()

    /** Legacy profile-only entries are retained but never adopted into the private namespace. */
    fun saved(owner: PlaybackSessionContext): SavedMultiscreen? = owner.use {
        layouts.saved(owner, currentKey(owner))
    }

    fun save(owner: PlaybackSessionContext, selection: SavedMultiscreen) = owner.use {
        layouts.save(owner, currentKey(owner), selection)
        Unit
    }

    private fun currentKey(owner: PlaybackSessionContext): String? = multiscreenNamespace(
        NetworkRoutes.savedServerUrl, ApiClient.session?.accountId, owner.profileId,
        paired = AppSession.isRemote.value || NetworkRoutes.isHomeDirect.value || ApiClient.remoteCredential != null,
    )
}

/** No transient route, credential or unknown account may become a saved-layout identity. */
internal fun multiscreenNamespace(server: HttpUrl?, accountId: String?, profileId: String, paired: Boolean): String? {
    if (paired || server == null || accountId.isNullOrBlank() || profileId.isBlank()) return null
    if (listOf(accountId, profileId).any { it.length > 1024 || it.any(Char::isISOControl) }) return null
    val configured = server.newBuilder().username("").password("").query(null).fragment(null).build().toString().trimEnd('/')
    val fields = listOf(configured, accountId, profileId).joinToString("") { "${it.length}:$it" }
    val hash = MessageDigest.getInstance("SHA-256").digest(fields.toByteArray()).joinToString("") { "%02x".format(it) }
    return "last.v2.$hash"
}

/** The actual saved-layout reducer never reads or migrates a profile-only legacy key. */
internal class MultiscreenLayoutStorage(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    private fun valid(key: String?) = key?.takeIf { Regex("last\\.v2\\.[0-9a-f]{64}").matches(it) }
    fun saved(owner: PlaybackSessionContext, key: String?): SavedMultiscreen? = owner.use {
        valid(key)?.let { MultiscreenMemory.decode(read(it)) }
    }
    fun save(owner: PlaybackSessionContext, key: String?, selection: SavedMultiscreen) = owner.use {
        valid(key)?.let { write(it, MultiscreenMemory.encode(selection)) }
        Unit
    }
}
