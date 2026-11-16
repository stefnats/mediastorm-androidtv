package com.strmr.tv.sources

import com.strmr.tv.adaptive.ThroughputStore
import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.SourceSwitchRules
import kotlinx.serialization.json.*

/** All post-search requests read the retained server snapshot; none resolve a source or probe media. */
interface SourceSearchTransport {
    suspend fun search(title:DetailsTarget,episode:Episode?,context:Long):SourceSearchReply
    suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long):SourceSearchPage
    suspend fun health(token:String,context:Long):SourceHealthSnapshot
}
object ApiSourceSearchTransport:SourceSearchTransport {
    override suspend fun search(title:DetailsTarget,episode:Episode?,context:Long)=ApiClient.sourceSearch(title,episode,context)
    override suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long)=ApiClient.sourceSearchPage(snapshotId,filters,cursor,context)
    override suspend fun health(token:String,context:Long)=ApiClient.sourceHealth(token,context)
}

internal fun sourceSearchQuery(title:DetailsTarget,episode:Episode?,profile:String):Map<String,String> = buildMap {
    put("q",SourceSwitchRules.query(title,episode?.code));put("titleId",title.titleId)
    put("mediaType",if(title.isMovie)"movie" else "series");put("userId",profile)
    put("includeSourceHealth","true");put("limit","50");put("sourceFiltersVersion","1")
    title.imdbId?.let {put("imdbId",it)};title.year?.let {put("year",it.toString())}
    episode?.takeUnless {title.isMovie}?.let {ep ->
        put("episodeId",ep.id);put("seasonNumber",ep.seasonNumber.toString());put("episodeNumber",ep.episodeNumber.toString())
        ep.numbering?.let {n ->put("episodeNumberingId",n.seriesId);n.ordering?.takeIf {it.isNotEmpty()}?.let {put("episodeOrdering",it)}}
    }
}

fun SourceFilters.json():JsonObject {
    require(valid()) {"Unsupported source filters"}
    return buildJsonObject {
        put("basis",if(basis==SourceFilterBasis.CANDIDATE)"candidate" else "probed")
        put("unknown",if(unknown==SourceFilterUnknown.INCLUDE)"include" else "exclude")
        audioLanguage?.let {put("audioLanguage",it)};subtitleLanguage?.let {put("subtitleLanguage",it)}
        claimedResolutionAtLeast?.let {put("claimedResolutionAtLeast",it)};heightAtLeast?.let {put("heightAtLeast",it)}
        videoCodec?.let {put("videoCodec",it)};dynamicRange?.let {put("dynamicRange",it)}
        audioCodec?.let {put("audioCodec",it)};audioDescription?.let {put("audioDescription",it)}
    }
}
internal fun sourceSearchPageBody(filters:SourceFilters,cursor:String?,limit:Int=50):JsonObject {
    require(limit in 1..SourceSearchCodec.MAX_PAGE)
    require(cursor==null || cursor.length in 1..1024 && cursor.none(Char::isISOControl))
    return buildJsonObject {put("version",1);put("filters",filters.json());cursor?.let {put("cursor",it)};put("limit",limit)}
}

suspend fun ApiClient.sourceSearch(title:DetailsTarget,episode:Episode?,expected:Long=ProfileDataContext.current):SourceSearchReply {
    val profile=inContext(expected){profileId}
    val params=sourceSearchQuery(title,episode,profile)
    val raw=sendRaw("GET","/api/indexers/search",params,headers=ThroughputStore.headers(),expectedContext=expected)
    ProfileDataContext.requireCurrent(expected)
    return SourceSearchCodec.initial(json.parseToJsonElement(raw))
}
suspend fun ApiClient.sourceSearchPage(snapshotId:String,filters:SourceFilters,cursor:String?,expected:Long):SourceSearchPage {
    val path=inContext(expected){"$profilePath/source-searches/${segment(snapshotId)}/page"}
    val raw=send<JsonObject>("POST",path,body=sourceSearchPageBody(filters,cursor),expectedContext=expected)
    ProfileDataContext.requireCurrent(expected)
    return SourceSearchCodec.page(raw)
}
