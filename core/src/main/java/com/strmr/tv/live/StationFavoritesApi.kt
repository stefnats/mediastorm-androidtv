package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.library.ProfileDataContext
import kotlinx.serialization.json.*

object StationFavoriteIds {
    const val MAX = 10_000
    private val pattern = Regex("station:[0-9a-f]{24}")
    fun valid(id: String) = pattern.matches(id)
    fun from(channel: LiveChannel): String? = channel.stationId?.takeIf(::valid)
    fun parse(value: JsonElement?): Set<String>? {
        val array = value as? JsonArray ?: return null
        if (array.size > MAX) return null
        val ids = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }
        if (ids.any { !valid(it) }) return null
        return ids.toSet()
    }
}

data class LiveChannelPreferences(
    val favorites: Set<String> = emptySet(), val hidden: Set<String> = emptySet(),
    val stationFavoritesSupported: Boolean = false, val favoriteStationIds: Set<String> = emptySet(),
    val stationFavoritesReadable: Boolean = true,
)

data class StationFavoriteSnapshot(val favoriteStationIds: Set<String>)

internal fun liveChannelPreferences(value: JsonObject): LiveChannelPreferences {
    val live = value["liveTV"] as? JsonObject
    fun legacy(key: String) = (live?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }?.toSet().orEmpty()
    val marker = (value["stationFavoritesSupported"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    val ids = if (!marker) emptySet() else if (live == null) null else if (live["favoriteStationIds"] == null) emptySet() else StationFavoriteIds.parse(live["favoriteStationIds"])
    return LiveChannelPreferences(legacy("favoriteChannels"),legacy("hiddenChannels"),marker,ids.orEmpty(),ids != null)
}

internal fun stationFavoriteBody(stationId: String, favorite: Boolean): JsonObject {
    require(StationFavoriteIds.valid(stationId)) { "Invalid station identity" }
    return buildJsonObject { put("stationId",stationId);put("favorite",favorite) }
}

internal fun stationFavoriteSnapshot(value: JsonObject): StationFavoriteSnapshot =
    StationFavoriteSnapshot(StationFavoriteIds.parse(value["favoriteStationIds"]) ?: throw ApiError.NotReady("Station favorites were not confirmed. Reload favorites before trying again."))

suspend fun ApiClient.liveChannelPreferences(expectedContext: Long = ProfileDataContext.current): LiveChannelPreferences {
    val path = inContext(expectedContext) { "$profilePath/settings" }
    val value = send<JsonObject>("GET",path,expectedContext=expectedContext)
    ProfileDataContext.requireCurrent(expectedContext)
    return liveChannelPreferences(value)
}

suspend fun ApiClient.setStationFavorite(stationId: String, favorite: Boolean, expectedContext: Long): StationFavoriteSnapshot {
    val path = inContext(expectedContext) { "$profilePath/live-tv/station-favorites" }
    val value = send<JsonObject>("PUT",path,body=stationFavoriteBody(stationId,favorite),expectedContext=expectedContext)
    ProfileDataContext.requireCurrent(expectedContext)
    return stationFavoriteSnapshot(value)
}
