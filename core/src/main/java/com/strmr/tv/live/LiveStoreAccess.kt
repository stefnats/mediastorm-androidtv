package com.strmr.tv.live

import com.strmr.tv.api.ApiClient

/** Local presentation choices, separate from the profile's saved favorite stations. */
interface LiveViewPreferences {
    fun categories(profileId: String): Set<String>
    fun groupStations(profileId: String): Boolean
    fun saveCategories(profileId: String, names: Set<String>)
    fun saveGroupStations(profileId: String, enabled: Boolean)
}

/** Existing catalog/settings requests, isolated from any stream or playback operation. */
interface LiveCatalogAccess {
    suspend fun preferences(context: Long): LiveChannelPreferences
    suspend fun page(query: LiveStore.Query, offset: Int, limit: Int, context: Long): LiveChannelsPage
    suspend fun epgEnabled(context: Long): Boolean
    suspend fun saveLegacy(favorites: List<String>, hidden: List<String>, context: Long)
}

object ApiLiveCatalogAccess : LiveCatalogAccess {
    override suspend fun preferences(context: Long) = ApiClient.liveChannelPreferences(context)
    override suspend fun page(query: LiveStore.Query, offset: Int, limit: Int, context: Long) = ApiClient.liveChannels(
        favoriteIds=query.favoriteIds.sorted(),favoritesOnly=query.filter==LiveFilter.Favorites,
        offset=offset,limit=limit,sourceId=query.sourceId,categories=query.filter.categories.sorted(),
        search=query.search,groupByStation=query.groupByStation,expectedContext=context,
        favoriteStationIds=query.favoriteStationIds?.sorted(),includeHidden=query.includeHidden,
    )
    override suspend fun epgEnabled(context: Long) = ApiClient.epgEnabled(context)
    override suspend fun saveLegacy(favorites: List<String>, hidden: List<String>, context: Long) = ApiClient.saveLiveChannelLists(favorites,hidden,context)
}
