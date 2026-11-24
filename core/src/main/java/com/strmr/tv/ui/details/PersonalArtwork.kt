package com.strmr.tv.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ArtworkKind
import com.strmr.tv.api.ArtworkLearningState
import com.strmr.tv.api.ContentArtworkPreference
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Title
import com.strmr.tv.api.contentArtworkPreference
import com.strmr.tv.api.contentArtworkPreferences
import com.strmr.tv.api.saveContentArtwork
import com.strmr.tv.app.AppSession
import com.strmr.tv.home.HomeCard
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pure display overrides; provider metadata and episode artwork remain intact. */
class PersonalArtworkStore(private val current: () -> Long) {
    private data class StandardDisplay(val poster: String? = null, val backdrop: String? = null)
    private var context: Long? = null
    var revision: Long = 0
        private set
    private var choices by mutableStateOf<Map<String, ContentArtworkPreference>>(emptyMap())
    private var standards by mutableStateOf<Map<String, StandardDisplay>>(emptyMap())
    fun activate(expected: Long) { if (context != expected) { context = expected; choices = emptyMap(); standards = emptyMap(); revision++ } }
    private fun retainStandards(values: Map<String, ContentArtworkPreference>) {
        standards = standards.filterKeys { it in values }.mapValues { (id, display) ->
            display.copy(poster = display.poster.takeIf { values[id]?.posterUrl.isNullOrBlank() },
                backdrop = display.backdrop.takeIf { values[id]?.backdropUrl.isNullOrBlank() })
        }.filterValues { it.poster != null || it.backdrop != null }
    }
    fun replace(expected: Long, values: List<ContentArtworkPreference>, readRevision: Long = revision) {
        if (current() != expected || context != expected || readRevision != revision) return
        choices = values.filter { it.contentId.isNotBlank() }.associateBy { it.contentId.lowercase() }
        retainStandards(choices)
    }
    fun saved(expected: Long, id: String, value: ContentArtworkPreference) {
        if (current() == expected && context == expected) {
            choices = choices + (id.lowercase() to value); retainStandards(choices); revision++
        }
    }
    /** Display evidence after a confirmed reset; the stored server preference stays null. */
    fun restoredStandard(expected: Long, id: String, kind: ArtworkKind, knownURL: String?) {
        if (current() != expected || context != expected || knownURL.isNullOrBlank()) return
        val key = id.lowercase()
        val preference = choices[key] ?: return
        if (!(if (kind == ArtworkKind.POSTER) preference.posterUrl else preference.backdropUrl).isNullOrBlank()) return
        val value = standards[key] ?: StandardDisplay()
        standards = standards + (key to if (kind == ArtworkKind.POSTER) value.copy(poster = knownURL) else value.copy(backdrop = knownURL))
        revision++
    }
    private fun preference(id: String) = choices[id.lowercase()].takeIf { context == current() }
    private fun standard(id: String) = standards[id.lowercase()].takeIf { context == current() }
    fun poster(id: String, original: String?) = preference(id)?.posterUrl?.takeIf(String::isNotBlank) ?: standard(id)?.poster ?: original
    fun backdrop(id: String, original: String?) = preference(id)?.backdropUrl?.takeIf(String::isNotBlank) ?: standard(id)?.backdrop ?: original
    fun card(card: HomeCard): HomeCard {
        val id = card.target?.titleId ?: return card
        if (preference(id) == null && standard(id) == null) return card
        val background = backdrop(id, card.backdropUrl)
        return card.copy(posterUrl = poster(id, card.posterUrl), backdropUrl = background,
            artworkUrl = if (card.episode != null) card.artworkUrl else backdrop(id, card.artworkUrl))
    }
}

object PersonalArtwork {
    val store = PersonalArtworkStore { ProfileDataContext.current }
}

/** Read existing stored preferences once per profile/wake; this never calls a metadata provider. */
@Composable
fun rememberPersonalArtwork() {
    val context by ProfileDataContext.changes.collectAsState()
    val wakes by AppSession.wakes.collectAsState()
    remember(context) { PersonalArtwork.store.activate(context) }
    LaunchedEffect(context, wakes) {
        val revision = PersonalArtwork.store.revision
        try { PersonalArtwork.store.replace(context, ApiClient.contentArtworkPreferences(context), revision) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Keep this profile's last confirmed display choice while offline. */ }
    }
}

/** The specific GET advertises support; a legacy full-document PUT is never used as fallback. */
@Composable
fun rememberArtworkSupported(target: DetailsTarget, expected: Long): Boolean {
    var supported by remember(target, expected) { mutableStateOf(false) }
    LaunchedEffect(target, expected) {
        supported = try { ApiClient.contentArtworkPreference(target.titleId, expected).artworkPreferenceSupported }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
    }
    return supported
}

data class ArtworkOption(val url: String?, val previewUrl: String?, val label: String)

/** Bounded to the already delivered variants; selecting a picture causes no new metadata lookup. */
fun artworkOptions(title: Title, kind: ArtworkKind): List<ArtworkOption> {
    val standard = if (kind == ArtworkKind.POSTER) title.standardPoster?.url ?: title.textPoster?.url ?: title.poster?.url
        else title.standardBackdrop?.url ?: title.backdrop?.url
    val variants = if (kind == ArtworkKind.POSTER) listOfNotNull(title.poster, title.textPoster) + title.posters.orEmpty()
        else listOfNotNull(title.backdrop, title.textBackdrop) + title.backdrops.orEmpty()
    return listOf(ArtworkOption(null, standard, "Use standard ${kind.label.lowercase()}")) +
        variants.map { it.url }.filter(String::isNotBlank).distinct().take(40).mapIndexed { index, url -> ArtworkOption(url, url, "${kind.label} ${index + 1}") }
}

interface ContentArtworkGateway {
    fun isCurrent(): Boolean
    suspend fun read(): ContentArtworkPreference
    suspend fun write(kind: ArtworkKind, value: String?): ContentArtworkPreference
    suspend fun readLearning(): ArtworkLearningState? = null
    suspend fun writeLearning(enabled: Boolean?, reset: Boolean): ArtworkLearningState = error("Artwork learning is unavailable")
}

class ServerContentArtworkGateway(private val target: DetailsTarget, private val expected: Long) : ContentArtworkGateway {
    private val learning by lazy { ApiClient.artworkLearningGateway(expected) }
    override fun isCurrent() = ProfileDataContext.current == expected
    override suspend fun read() = ApiClient.contentArtworkPreference(target.titleId, expected)
    override suspend fun write(kind: ArtworkKind, value: String?) =
        ApiClient.saveContentArtwork(target.titleId, if (target.isMovie) "movie" else "series", kind, value, expected)
    override suspend fun readLearning() = learning.read()
    override suspend fun writeLearning(enabled: Boolean?, reset: Boolean) = learning.write(enabled, reset)
}

data class ContentArtworkState(val preference: ContentArtworkPreference? = null, val kind: ArtworkKind = ArtworkKind.POSTER,
    val loading: Boolean = true, val saving: Boolean = false, val message: String? = null, val error: String? = null,
    val learning: ArtworkLearningState? = null, val learningSaving: Boolean = false, val learningError: String? = null) {
    val supported get() = preference?.artworkPreferenceSupported == true
    val selected get() = if (kind == ArtworkKind.POSTER) preference?.posterUrl else preference?.backdropUrl
}

class ContentArtworkEditor(val title: Title, private val gateway: ContentArtworkGateway,
    private val onSaved: (ContentArtworkPreference) -> Unit, private val onStandardRestored: (ArtworkKind, String) -> Unit) {
    constructor(title: Title, gateway: ContentArtworkGateway, onSaved: (ContentArtworkPreference) -> Unit) :
        this(title, gateway, onSaved, { _, _ -> })
    private val mutable = MutableStateFlow(ContentArtworkState())
    val state: StateFlow<ContentArtworkState> = mutable
    private var closed = false
    private var busy = false
    fun close() { closed = true }
    private fun checkCurrent() { if (closed || !gateway.isCurrent()) throw CancellationException("Artwork profile changed") }
    val options get() = artworkOptions(title, mutable.value.kind)
    fun kind(value: ArtworkKind) { if (!busy && !closed) mutable.value = mutable.value.copy(kind = value, message = null) }
    suspend fun load() {
        if (busy) return
        checkCurrent(); busy = true
        mutable.value = mutable.value.copy(loading = true, error = null, learningError = null)
        try {
            val value = gateway.read(); checkCurrent()
            mutable.value = mutable.value.copy(preference = value,
                error = if (value.artworkPreferenceSupported) null else "Personal artwork is unavailable on this server.")
            if (value.artworkPreferenceSupported) loadLearning()
            mutable.value = mutable.value.copy(loading = false)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            checkCurrent(); mutable.value = mutable.value.copy(loading = false, error = "Could not load personal artwork. Select to retry.")
        } finally { busy = false }
    }
    private suspend fun loadLearning() {
        try {
            val value = gateway.readLearning(); checkCurrent()
            mutable.value = mutable.value.copy(learning = value, learningError = null)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            checkCurrent()
            mutable.value = mutable.value.copy(learningError = "Could not load picture learning. Reload to check its status.")
        }
    }
    suspend fun setLearningEnabled(enabled: Boolean) = saveLearning(enabled, false)
    suspend fun forgetLearning() = saveLearning(null, true)
    private suspend fun saveLearning(enabled: Boolean?, reset: Boolean) {
        if (busy || !mutable.value.supported || mutable.value.learning == null || mutable.value.learningError != null) return
        checkCurrent(); busy = true
        mutable.value = mutable.value.copy(learningSaving = true, message = null)
        try {
            val original = requireNotNull(mutable.value.learning)
            val value = gateway.writeLearning(enabled, reset); checkCurrent()
            check(value.profileId == original.profileId && value.supported && value.minimumSamples == 3 && value.maxSamples == 128)
            check(value.posterSamples in 0..128 && value.backdropSamples in 0..128)
            check(value.posterSamples + value.backdropSamples <= value.maxSamples)
            if (reset) check(!value.enabled && value.posterSamples == 0 && value.backdropSamples == 0)
            else check(value.enabled == enabled)
            mutable.value = mutable.value.copy(learning = value, learningSaving = false,
                message = if (reset) "Learned picture style forgotten. Learning is off; manual choices are kept."
                else if (enabled == true) "Picture learning enabled for this profile. Only future picture choices are used."
                else "Picture learning disabled for this profile.")
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            checkCurrent()
            mutable.value = mutable.value.copy(learningSaving = false,
                learningError = "Could not confirm picture learning. Reload before trying again.")
        } finally { busy = false }
    }
    suspend fun save(url: String?) {
        if (busy || !mutable.value.supported || mutable.value.error != null || options.none { it.url == url }) return
        checkCurrent(); busy = true
        val kind = mutable.value.kind
        mutable.value = mutable.value.copy(saving = true, message = null)
        try {
            val value = gateway.write(kind, url); checkCurrent()
            check(value.artworkPreferenceSupported && (if (kind == ArtworkKind.POSTER) value.posterUrl else value.backdropUrl) == url)
            onSaved(value)
            if (url == null) {
                val standard = if (kind == ArtworkKind.POSTER) title.standardPoster?.url else title.standardBackdrop?.url
                standard?.takeIf(String::isNotBlank)?.let { checkCurrent(); onStandardRestored(kind, it) }
            }
            mutable.value = mutable.value.copy(preference = value, saving = false,
                message = if (url == null) "Standard ${kind.label.lowercase()} restored for this profile." else "${kind.label} saved for this profile.")
            if (mutable.value.learning?.enabled == true) loadLearning()
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            checkCurrent(); mutable.value = mutable.value.copy(saving = false, error = "Could not confirm artwork. Reload before retrying.")
        } finally { busy = false }
    }
}

@Composable
fun rememberContentArtworkEditor(target: DetailsTarget, title: Title, expected: Long): ContentArtworkEditor {
    val editor = remember(target, title, expected) { ContentArtworkEditor(title, ServerContentArtworkGateway(target, expected),
        onSaved = { PersonalArtwork.store.saved(expected, target.titleId, it) },
        onStandardRestored = { kind, url -> PersonalArtwork.store.restoredStandard(expected, target.titleId, kind, url) }) }
    LaunchedEffect(editor) { editor.load() }
    DisposableEffect(editor) { onDispose { editor.close() } }
    return editor
}
