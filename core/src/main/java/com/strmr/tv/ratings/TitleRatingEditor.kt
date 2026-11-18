package com.strmr.tv.ratings

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit preferences do not change watched, hidden or watchlist state. */
enum class PersonalRating(val wire: String, val label: String) {
    LIKE("like", "Like"), LOVE("love", "Love"), DISLIKE("dislike", "Not for me"), NONE("none", "No rating");
    companion object { fun from(value: String): PersonalRating = entries.firstOrNull { it.wire == value } ?: error("Unsupported personal rating") }
}

@Serializable
data class TitleRatingReply(val rating: String)

interface TitleRatingGateway {
    fun isCurrent(): Boolean
    suspend fun read(target: DetailsTarget): TitleRatingReply
    suspend fun write(target: DetailsTarget, rating: PersonalRating): TitleRatingReply
}

class ServerTitleRatingGateway(private val promptOnLock: Boolean = true) : TitleRatingGateway {
    private val epoch = ProfileDataContext.current
    private val profile = ApiClient.profileId
    private val server = ApiClient.baseUrl
    private val account = ApiClient.session?.accountId
    private val remote = ApiClient.remoteCredential
    private val path = ApiClient.profilePath
    override fun isCurrent() = epoch == ProfileDataContext.current && profile == ApiClient.profileId && server == ApiClient.baseUrl && account == ApiClient.session?.accountId && remote == ApiClient.remoteCredential
    private fun route(target: DetailsTarget) = "$path/ratings/${if (target.isMovie) "movie" else "series"}/${ApiClient.segment(target.titleId)}"
    override suspend fun read(target: DetailsTarget): TitleRatingReply {
        if (!isCurrent()) throw CancellationException("Rating context changed")
        return ApiClient.send("GET", route(target), promptOnLock = promptOnLock, timeoutMs = 5_000, expectedContext = epoch)
    }
    override suspend fun write(target: DetailsTarget, rating: PersonalRating): TitleRatingReply {
        if (!isCurrent()) throw CancellationException("Rating context changed")
        return ApiClient.send("PUT", route(target), body = buildJsonObject { put("rating", rating.wire) }, promptOnLock = promptOnLock, timeoutMs = 5_000, expectedContext = epoch)
    }
}

object TitleRatingChanges {
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()
    fun saved() { _generation.update { it + 1 } }
}

data class TitleRatingState(
    val rating: PersonalRating? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val requiresReopen: Boolean = false,
)

/** One editor per prompt, captured before the originating menu is dismissed.
 * Late replies and a closed editor cannot update another profile's UI. */
class TitleRatingEditor(
    val target: DetailsTarget,
    private val gateway: TitleRatingGateway,
    initialRating: TitleRatingReply? = null,
    private val receiptReloadHint: String? = null,
    private val parent: Job? = null,
    private val selectionCurrent: () -> Boolean = { true },
    private val onSaved: () -> Unit = TitleRatingChanges::saved,
) {
    private val _state = MutableStateFlow(TitleRatingState())
    val state: StateFlow<TitleRatingState> = _state.asStateFlow()
    private var deliveredInitial = initialRating
    private var closed = false
    private var busy = false
    private val jobs = mutableSetOf<Job>()
    fun isCurrent() = !closed && parent?.isActive != false && selectionCurrent() && gateway.isCurrent()
    fun close() {
        closed = true
        jobs.toList().forEach { it.cancel() }
    }
    /** Register real producer children, including under a NonCancellable caller. */
    private suspend fun <T> owned(action: suspend () -> T): T = coroutineScope {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        val child = async(context = parent ?: requireNotNull(currentCoroutineContext()[Job]), start = CoroutineStart.LAZY) {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            action().also { currentCoroutineContext().ensureActive(); checkCurrent() }
        }
        jobs += child
        try { child.start(); child.await() } finally { jobs -= child; child.cancel() }
    }
    private fun checkCurrent() { if (!isCurrent()) throw CancellationException("Rating context changed") }

    suspend fun load() {
        if (busy || _state.value.requiresReopen) return
        require(target.titleId.isNotBlank() && !target.fromLibrary) { "A catalogue title is required" }
        checkCurrent()
        busy = true
        _state.value = TitleRatingState()
        try {
            val reply = deliveredInitial?.also { deliveredInitial = null } ?: owned { gateway.read(target) }
            checkCurrent()
            _state.value = TitleRatingState(rating = PersonalRating.from(reply.rating), loading = false)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            checkCurrent()
            val reopen = receiptReloadHint != null && (error as? ApiError)?.statusCode == 409
            _state.value = TitleRatingState(loading = false, error = if (reopen) receiptReloadHint else ratingFailure(error), requiresReopen = reopen)
        } finally { busy = false }
    }

    suspend fun save(value: PersonalRating) {
        if (busy || _state.value.rating == null || _state.value.requiresReopen) return
        checkCurrent()
        if (value == _state.value.rating && _state.value.error == null) return
        busy = true
        val previous = _state.value.rating
        _state.value = TitleRatingState(rating = previous, loading = false, saving = true)
        try {
            val reply = owned { gateway.write(target, value) }
            checkCurrent()
            check(PersonalRating.from(reply.rating) == value) { "Unexpected personal rating" }
            _state.value = TitleRatingState(rating = value, loading = false)
            onSaved()
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            checkCurrent()
            val reopen = receiptReloadHint != null && (error as? ApiError)?.statusCode == 409
            _state.value = TitleRatingState(rating = previous, loading = false, error = if (reopen) receiptReloadHint else ratingFailure(error), requiresReopen = reopen)
        } finally { busy = false }
    }
}

private fun ratingFailure(error: Exception): String = when ((error as? ApiError)?.statusCode) {
    404, 405, 501 -> "Personal ratings are unavailable for this title on this server."
    409 -> "Reload this title and try again."
    403 -> "Unlock this profile before rating this title."
    else -> "Could not update your personal rating. Try again later."
}
