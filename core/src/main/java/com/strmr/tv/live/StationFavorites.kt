package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StationFavoriteFailure(val stationId: String, val desired: Boolean, val message: String)
data class StationFavoriteState(
    val supported: Boolean = false, val loaded: Boolean = false, val ids: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(), val failures: Map<String,StationFavoriteFailure> = emptyMap(),
    val loadError: String? = null,
)

data class StationFavoriteAction(val stationId: String, val favorite: Boolean, val pending: Boolean) {
    val desired get() = !favorite
    val label get() = if (pending) "Saving station favorite…" else if (favorite) "Remove station from Favorites" else "Add station to Favorites"
}

/** One profile epoch owns the queue and authoritative snapshot across all of its Live views. */
class StationFavorites(
    parent: CoroutineScope, val context: Long,
    private val write: suspend (String,Boolean,Long) -> StationFavoriteSnapshot = { id, favorite, expected -> ApiClient.setStationFavorite(id,favorite,expected) },
) {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val value = MutableStateFlow(StationFavoriteState())
    val state: StateFlow<StationFavoriteState> = value.asStateFlow()
    private val mutex = Mutex()
    private var revision = 0L
    private var readSequence = 0L
    private var observer: Job? = null
    init { observer = scope.launch { ProfileDataContext.changes.first { it != context }; close() } }
    private fun current() = owner.isActive && context == ProfileDataContext.current
    private suspend fun check() { currentCoroutineContext().ensureActive();ProfileDataContext.requireCurrent(context);if(!owner.isActive)throw CancellationException("Station favorites closed") }
    class ReadTicket internal constructor(internal val revision: Long,internal val sequence: Long)
    fun beginRead() = ReadTicket(revision,++readSequence)
    fun acceptRead(ticket: ReadTicket, settings: LiveChannelPreferences) {
        if (!current() || ticket.revision != revision || ticket.sequence != readSequence || value.value.pending.isNotEmpty()) return
        val before = value.value
        val valid = settings.stationFavoritesReadable
        val enabled = settings.stationFavoritesSupported && valid
        val ids = if (!settings.stationFavoritesSupported) emptySet() else if (valid) settings.favoriteStationIds.toSet() else before.ids
        value.value = before.copy(supported=enabled,loaded=true,ids=ids,
            loadError=if(valid)null else "Station favorites could not be loaded. Retry favorite settings.",
            failures=if(!enabled)emptyMap() else before.failures.filterValues { it.desired != (it.stationId in ids) })
    }
    fun failRead(ticket: ReadTicket) {
        if(current() && ticket.revision==revision && ticket.sequence==readSequence)
            value.value=value.value.copy(loadError="Favorite settings could not be loaded. Retry.")
    }
    fun action(stationId: String): StationFavoriteAction? = if(current() && value.value.supported && StationFavoriteIds.valid(stationId))
        StationFavoriteAction(stationId,stationId in value.value.ids,stationId in value.value.pending) else null
    fun set(stationId: String, desired: Boolean) {
        if (!current() || !value.value.supported || !StationFavoriteIds.valid(stationId) || stationId in value.value.pending) return
        if ((stationId in value.value.ids)==desired && stationId !in value.value.failures) return
        revision++
        value.value=value.value.copy(pending=value.value.pending+stationId,failures=value.value.failures-stationId)
        scope.launch {
            try {
                mutex.withLock {
                    check()
                    val reply=write(stationId,desired,context)
                    check()
                    if (reply.favoriteStationIds.size>StationFavoriteIds.MAX || reply.favoriteStationIds.any { !StationFavoriteIds.valid(it) } ||
                        (stationId in reply.favoriteStationIds)!=desired) error("Unconfirmed station favorite")
                    revision++
                    value.value=value.value.copy(ids=reply.favoriteStationIds.toSet(),failures=value.value.failures.filterValues { it.desired != (it.stationId in reply.favoriteStationIds) })
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if(current()) {
                    revision++
                    val failure=StationFavoriteFailure(stationId,desired,"Station favorite could not be saved. Retry.")
                    value.value=value.value.copy(failures=value.value.failures+(stationId to failure))
                }
            } finally {
                if(current()) value.value=value.value.copy(pending=value.value.pending-stationId)
            }
        }
    }
    fun retry(failure: StationFavoriteFailure) {
        if(value.value.failures[failure.stationId]==failure)set(failure.stationId,failure.desired)
    }
    fun close() {
        value.value=StationFavoriteState()
        owner.cancel()
        observer?.cancel()
    }
}

internal object ProfileStationFavorites {
    private var active: StationFavorites? = null
    fun get(context: Long): StationFavorites {
        ProfileDataContext.requireCurrent(context)
        active?.takeIf { it.context==context }?.let { return it }
        active?.close()
        return StationFavorites(MainScope(),context).also { active=it }
    }
}
