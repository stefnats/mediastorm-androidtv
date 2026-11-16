package com.strmr.tv.ui.settings

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.PlaybackSettings
import com.strmr.tv.api.SubtitleServerSettings
import com.strmr.tv.api.vodDenial
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import com.strmr.tv.api.playbackSettings
import com.strmr.tv.api.subtitleServerSettings
import com.strmr.tv.player.RegionalAudio
import com.strmr.tv.player.SubtitleLanguages
import com.strmr.tv.player.TrackOverrides

/** Reads the server's values the track rows show as "Default (…)". */
suspend fun loadTrackDefaults() {
    val expected = ProfileDataContext.current
    loadOwnedTrackDefaults(expected, { ApiClient.playbackSettings(expected) }, { ApiClient.subtitleServerSettings(expected) }) { values ->
        TrackOverrides.serverSubtitleMode = values.playback?.preferredSubtitleMode
        TrackOverrides.serverSubtitleLanguage = values.playback?.preferredSubtitleLanguage
        TrackOverrides.serverAudioLanguage = values.playback?.preferredAudioLanguage
        TrackOverrides.serverTranslation = values.translation
    }
}

internal data class ServerTrackDefaults(val playback: PlaybackSettings?, val translation: Boolean)

/** Settings pages have no player denial handler; clear denied defaults without a second request. */
internal suspend fun loadOwnedTrackDefaults(
    expected: Long,
    readPlayback: suspend () -> PlaybackSettings,
    readSubtitles: suspend () -> SubtitleServerSettings,
    publish: (ServerTrackDefaults) -> Unit,
) {
    suspend fun apply(value: ServerTrackDefaults) { currentCoroutineContext().ensureActive(); ApiClient.inContext(expected) { publish(value) } }
    currentCoroutineContext().ensureActive()
    ProfileDataContext.requireCurrent(expected)
    val playback = try { readPlayback().also { currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected) }
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expected)
        if (error.vodDenial != null) { apply(ServerTrackDefaults(null, false)); return }
        null
    }
    val translation = try { readSubtitles().also { currentCoroutineContext().ensureActive(); ProfileDataContext.requireCurrent(expected) }.translationEnabled
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        ProfileDataContext.requireCurrent(expected)
        if (error.vodDenial != null) { apply(ServerTrackDefaults(null, false)); return }
        false
    }
    apply(ServerTrackDefaults(playback, translation))
}

/** The language pickers' choices, shared by the TV and phone Settings. */
object LanguageChoices {
    /** The languages; with [regional], each regional dub ("French (Canada)") after its language. */
    fun options(regional: Boolean): List<SubtitleLanguages.Option> = SubtitleLanguages.options.flatMap { option ->
        val variants = if (!regional) emptyList() else RegionalAudio.options
            .filter { SubtitleLanguages.canonical(it.first) == option.settingCode }
            .map { (code, name) -> SubtitleLanguages.Option(option.code, code, name) }
        listOf(option) + variants
    }

    /** [value]'s place in [options]; -1 for a code outside them, null for none chosen. */
    fun indexOf(options: List<SubtitleLanguages.Option>, value: String?): Int? = value?.let { v ->
        options.indexOfFirst {
            SubtitleLanguages.canonical(it.settingCode) == SubtitleLanguages.canonical(v) && RegionalAudio.sameVariant(it.settingCode, v)
        }
    }
}
