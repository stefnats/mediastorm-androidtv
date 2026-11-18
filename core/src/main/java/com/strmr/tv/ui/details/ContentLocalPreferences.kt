package com.strmr.tv.ui.details

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ContentLocalPreference
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Episode
import com.strmr.tv.api.LocalSourceChoice
import com.strmr.tv.api.Season
import com.strmr.tv.api.contentLocalPreference
import com.strmr.tv.api.saveContentLocalPreference
import com.strmr.tv.library.LibraryVersions
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class ContentLocalScope(val id: String, val contentType: String, val label: String)

private val knownSeriesId = Regex("(?:tmdb:(?:series|tv):[0-9]+|tvdb:(?:series:)?[0-9]+|(?:imdb:)?tt[0-9]+)")
private val safeTitleId = Regex("[a-z0-9][a-z0-9._:-]*")

/** Only a known catalog identity and its official coordinates can create episode scopes. */
fun canonicalLocalEpisode(target: DetailsTarget, episode: Episode): Boolean {
    val numbering = episode.numbering ?: return false
    return !target.isMovie && knownSeriesId.matches(target.titleId.trim().lowercase()) &&
        numbering.seriesId.trim().equals(target.titleId.trim(), true) &&
        numbering.ordering?.trim()?.lowercase().orEmpty() in setOf("", "official", "aired", "default") &&
        episode.seasonNumber in 0..10_000 && episode.episodeNumber in 1..100_000
}

fun contentLocalScopes(target: DetailsTarget, season: Season? = null, episode: Episode? = null): List<ContentLocalScope> {
    val id = target.titleId.trim().lowercase()
    val movieId = id.startsWith("tmdb:movie:")
    val seriesId = id.startsWith("tmdb:series:") || id.startsWith("tmdb:tv:") || id.startsWith("tvdb:")
    if (movieId && !target.isMovie || seriesId && target.isMovie) return emptyList()
    // Unknown catalog aliases still support a title preference, without inventing episode coordinates.
    if (id.length !in 1..1024 || !safeTitleId.matches(id) || ":season:" in id || ":episode:" in id) return emptyList()
    if (target.fromLibrary && !target.hasCatalogIds && !movieId && !knownSeriesId.matches(id)) return emptyList()
    val type = if (target.isMovie) "movie" else "series"
    val scopes = mutableListOf(ContentLocalScope(id, type, if (target.isMovie) "This movie" else "This series"))
    if (target.isMovie) return scopes
    val canonicalSeason = season?.takeIf { it.number in 0..10_000 && it.episodes.isNotEmpty() && it.episodes.all { ep -> ep.seasonNumber == it.number && canonicalLocalEpisode(target, ep) } }
    val canonicalEpisode = episode?.takeIf { canonicalLocalEpisode(target, it) }
    val seasonNumber = canonicalEpisode?.seasonNumber ?: canonicalSeason?.number
    if (seasonNumber != null) scopes += ContentLocalScope("$id:season:$seasonNumber", type, if (seasonNumber == 0) "Specials (Season 0)" else "Season $seasonNumber")
    canonicalEpisode?.let { scopes += ContentLocalScope("$id:season:${it.seasonNumber}:episode:${it.episodeNumber}", type, "Episode ${it.code}") }
    return scopes.filter { it.id.length <= 1024 }
}

/** Most-specific stored content value wins; null leaves the profile/server policy inherited. */
fun effectiveContentLocalPreference(scopes: List<ContentLocalScope>, values: Map<String, Boolean?>): Boolean? =
    scopes.asReversed().firstNotNullOfOrNull { values[it.id] }

fun automaticLibraryForPreference(library: LibraryVersions?, deliberateLibrary: Boolean, preference: Boolean?): LibraryVersions? =
    if (!deliberateLibrary && preference == false) null else library

/** Read most specific first, without guessing alternate-order episode coordinates. */
internal suspend fun resolveContentLocalPreference(target: DetailsTarget, episode: Episode?, expected: Long, read: suspend (ContentLocalScope, Long) -> ContentLocalPreference = { scope, context -> ApiClient.contentLocalPreference(scope.id,context) }): Boolean? = withTimeout(5_000) {
    for (scope in contentLocalScopes(target, episode=episode).asReversed()) {
        val document=try { read(scope,expected) } catch (e: ApiError.Http) { if(e.statusCode==404 || e.statusCode==405) return@withTimeout null else throw e }
        if (!document.localSourcePreferenceSupported) return@withTimeout null
        document.preferLocalFiles?.let { return@withTimeout it }
    }
    null
}

/** Legacy selection helper; capable servers own automatic ordering and eligibility. */
internal suspend fun catalogPlayLibrary(
    target: DetailsTarget,
    episode: Episode?,
    library: LibraryVersions?,
    expected: Long,
    read: suspend (ContentLocalScope, Long) -> ContentLocalPreference = { scope, context -> ApiClient.contentLocalPreference(scope.id, context) },
): LibraryVersions? {
    if (target.fromLibrary) return library
    return automaticLibraryForPreference(library, false, resolveContentLocalPreference(target, episode, expected, read))
}

data class ContentLocalEditorState(
    val scopes: List<ContentLocalScope>,
    val selected: ContentLocalScope,
    val values: Map<String, Boolean?> = emptyMap(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val supported: Boolean = false,
    val error: String? = null,
    val closed: Boolean = false,
)

/** One accepted original request; scope signatures are correlation, never playback permission. */
class ContentLocalRequest private constructor(
    val target: DetailsTarget,
    scopes: List<ContentLocalScope>,
    private val owner: com.strmr.tv.player.PlaybackSessionContext,
    internal val parent: Job,
    private val selected: () -> Boolean,
    private val currentRequest: () -> ContentLocalRequest?,
) {
    val scopes = scopes.toList()
    val context get() = owner.epoch
    private var closed = false
    fun isCurrent() = !closed && parent.isActive && owner.isCurrent() && selected() && currentRequest() === this
    fun close() { closed = true }
    internal fun publish(action: () -> Unit): Boolean {
        if (!isCurrent()) return false
        return owner.use { if (!isCurrent()) false else { action(); true } }
    }
    fun publishIfSelected(current: () -> ContentLocalRequest?, action: () -> Unit): Boolean {
        if (current() !== this) return false
        return publish { if (current() === this) action() }
    }
    /** Retired original prompts may clear themselves, never a replacement request. */
    fun dismissIfSelected(current: () -> ContentLocalRequest?, action: () -> Unit): Boolean {
        if (current() !== this) return false
        close()
        action()
        return true
    }
    companion object {
        fun capture(
            target: DetailsTarget,
            scopes: List<ContentLocalScope>,
            operation: com.strmr.tv.library.CustomListOperation,
            origin: com.strmr.tv.library.TitleListOrigin,
            currentRequest: () -> ContentLocalRequest?,
            selected: () -> Boolean,
        ): ContentLocalRequest? {
            operation.requireTarget(target)
            if (scopes.isEmpty() || currentRequest() != null || origin.target !== target ||
                origin.context != operation.owner.epoch || !origin.selectionCurrent() || !selected()) return null
            return ContentLocalRequest(target, scopes, operation.owner, origin.parent,
                { origin.selectionCurrent() && selected() }, currentRequest)
        }
    }
}

/** Owned by one original dialog request. No successful write can retarget a new page/profile. */
class ContentLocalPreferences internal constructor(
    private val scope: CoroutineScope,
    targets: List<ContentLocalScope>,
    private val contexts: StateFlow<Long>,
    private val read: suspend (ContentLocalScope, Long) -> ContentLocalPreference,
    private val write: suspend (ContentLocalScope, Boolean?, Long) -> ContentLocalPreference,
    private val onSaved: () -> Unit,
    private val expected: Long = contexts.value,
    private val request: ContentLocalRequest? = null,
) {
    constructor(scope: CoroutineScope, request: ContentLocalRequest, onSaved: () -> Unit) : this(
        scope, request.scopes, ProfileDataContext.changes,
        { target, context -> ApiClient.contentLocalPreference(target.id, context) },
        { target, value, context -> ApiClient.saveContentLocalPreference(target.id, target.contentType, value, context) },
        onSaved, request.context, request,
    )
    private val stateValue = MutableStateFlow(ContentLocalEditorState(targets.toList(), targets.first()))
    val state: StateFlow<ContentLocalEditorState> = stateValue
    private var task: Job? = null
    private var changed: Job? = null
    init { changed = scope.launch { contexts.first { it != expected }; close() } }
    private fun current() = !stateValue.value.closed && contexts.value == expected &&
        scope.coroutineContext[Job]?.isActive != false && request?.isCurrent() != false
    private fun checkCurrent() {
        if (!current()) throw CancellationException("Source preference selection changed")
    }
    private fun publish(action: () -> Unit) {
        if (!current()) return
        if (request == null) action() else request.publish(action)
    }
    private fun launch(action: suspend () -> Unit): Job =
        scope.launch(request?.parent ?: requireNotNull(scope.coroutineContext[Job])) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            checkCurrent()
            action()
        }

    fun load() {
        if (!current() || task?.isActive == true) return
        publish { stateValue.value = stateValue.value.copy(loading = true, error = null) }
        task = launch {
            try {
                val values = mutableMapOf<String, Boolean?>()
                for (target in stateValue.value.scopes) {
                    checkCurrent()
                    val preference = read(target, expected)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    checkCurrent()
                    if (!preference.localSourcePreferenceSupported) {
                        publish { stateValue.value = stateValue.value.copy(loading = false, supported = false, error = "Update the server to use title source preferences.") }
                        return@launch
                    }
                    values[target.id] = preference.preferLocalFiles
                }
                publish { stateValue.value = stateValue.value.copy(loading = false, supported = true, values = values) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                publish { stateValue.value = stateValue.value.copy(loading = false, supported = false, error = "Source preferences could not be loaded. Try again.") }
            }
        }
    }

    fun select(target: ContentLocalScope) {
        if (current() && !stateValue.value.saving && stateValue.value.scopes.any { it === target }) {
            publish { stateValue.value = stateValue.value.copy(selected = target, error = null) }
        }
    }

    fun choose(choice: LocalSourceChoice) {
        val before = stateValue.value
        if (!current() || before.loading || before.saving || !before.supported || before.selected.id !in before.values) return
        val selected = before.selected
        publish { stateValue.value = before.copy(saving = true, error = null) }
        task = launch {
            try {
                checkCurrent()
                val saved = write(selected, choice.preference, expected)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                checkCurrent()
                if (!saved.localSourcePreferenceSupported || saved.preferLocalFiles != choice.preference) {
                    publish { stateValue.value = stateValue.value.copy(saving = false, supported = false, error = "The server did not confirm this source preference.") }
                    return@launch
                }
                publish {
                    stateValue.value = stateValue.value.copy(saving = false, values = stateValue.value.values + (selected.id to saved.preferLocalFiles))
                    onSaved()
                }
                close()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                publish { stateValue.value = stateValue.value.copy(saving = false, supported = false, error = "The source preference could not be saved. Reload before trying again.") }
            }
        }
    }

    fun close() {
        stateValue.value = stateValue.value.copy(closed = true, saving = false)
        request?.close()
        task?.cancel()
        changed?.cancel()
    }
}
