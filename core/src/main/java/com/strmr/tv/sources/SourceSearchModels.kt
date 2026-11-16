package com.strmr.tv.sources

import com.strmr.tv.api.*
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Echoed request identity is correlation, never a verified title or episode grant. */
@Serializable
data class SourceSearchTarget(val titleId:String,val mediaType:String,val episode:SourceSearchEpisode?=null) {
    fun valid()=titleId.length in 1..1024 && titleId.none(Char::isISOControl) && mediaType in setOf("movie","series") && (episode==null || mediaType=="series" && episode.valid())
    companion object {
        fun from(title:DetailsTarget,episode:Episode?)=SourceSearchTarget(title.titleId,if(title.isMovie)"movie" else "series",
            episode?.takeUnless {title.isMovie}?.let {SourceSearchEpisode(it.id,it.seasonNumber,it.episodeNumber,it.numbering?.let {n->n.copy(ordering=n.ordering?.takeIf {order->order.isNotEmpty()})})})
    }
}
@Serializable
data class SourceSearchEpisode(val episodeId:String,val seasonNumber:Int,val episodeNumber:Int,val numbering:EpisodeNumbering?=null) {
    fun valid()=episodeId.length in 1..1024 && episodeId.none(Char::isISOControl) && seasonNumber in 0..10_000 && episodeNumber in 1..100_000 &&
        (numbering==null || numbering.seriesId.length in 1..1024 && numbering.seriesId.none(Char::isISOControl) && (numbering.ordering==null || numbering.ordering.length<=128 && numbering.ordering.none(Char::isISOControl)))
}
@Serializable
data class SourceSearchCoverage(val retainedCandidates:Int,val truncated:Boolean)

enum class SourceSearchVerdict { UNFILTERED,MATCH,UNKNOWN }

data class SourceSearchRow(val sourceIndex:Int,val candidateVersion:String?,val result:SourceResult,val verdict:SourceSearchVerdict,
    val mediaEvidence:MediaEvidence?=null) {
    val key:String get()="${result.responseScope.orEmpty()}:$sourceIndex"
    val expectedFileVersion:String? get()=mediaEvidence?.takeIf {it.probed!=null && it.hasValidVersionIdentity() && it.candidateVersion==candidateVersion}?.sourceVersion
}

data class SourceSearchPage(
    val snapshotId:String,val viewId:String,val expiresAt:Instant,val target:SourceSearchTarget,val filters:SourceFilters,
    val rows:List<SourceSearchRow>,val total:Int,val matchedTotal:Int,val unknownTotal:Int,
    val coverage:SourceSearchCoverage,val nextCursor:String?=null,val sourceHealthToken:String?=null,
)
sealed interface SourceSearchReply {
    data class Supported(val page:SourceSearchPage):SourceSearchReply
    data class Legacy(val rows:List<SourceSearchRow>):SourceSearchReply
}

/** Required pagination/identity fields are strict; optional media facts stay defensive independently. */
object SourceSearchCodec {
    const val MAX_RETAINED=512
    const val MAX_PAGE=100
    private val candidatePattern=Regex("candidate_v1_[0-9a-f]{64}")
    private fun invalid():Nothing=throw ApiError.NotReady("Source results could not be read. Reload the source list.")
    private fun JsonObject.number(key:String)= (get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull ?: invalid()
    private fun JsonObject.text(key:String,optional:Boolean=false):String? {
        if(optional && (get(key)==null || get(key)==JsonNull))return null
        return (get(key) as? JsonPrimitive)?.takeIf {it.isString}?.contentOrNull?.takeIf {it.length in 1..1024 && it.none(Char::isISOControl)} ?: invalid()
    }
    fun initial(element:JsonElement):SourceSearchReply {
        if(element is JsonArray) {
            return SourceSearchReply.Legacy(element.mapIndexedNotNull {i,raw -> (raw as? JsonObject)?.let {SourceResult.from(it,i)}?.let {SourceSearchRow(i,null,it,SourceSearchVerdict.UNFILTERED,it.mediaEvidence)}})
        }
        return SourceSearchReply.Supported(page(element))
    }
    fun page(element:JsonElement):SourceSearchPage {
        val obj=element as? JsonObject ?: invalid()
        if(obj.number("sourceFiltersVersion")!=1)invalid()
        val snapshot=obj.text("snapshotId")!!;val view=obj.text("viewId")!!
        val expiry=runCatching {Instant.parse(obj.text("expiresAt"))}.getOrNull() ?: invalid()
        val target=runCatching {ApiJson.decodeFromJsonElement<SourceSearchTarget>(obj["target"] ?: invalid())}.getOrNull()?.takeIf {it.valid()} ?: invalid()
        val rawFilters=obj["filters"] as? JsonObject ?: invalid()
        // Global API decoding coerces unknown enum values; pagination must retain exact accepted criteria.
        if(rawFilters.text("basis") !in setOf("candidate","probed") || rawFilters.text("unknown") !in setOf("include","exclude"))invalid()
        for(key in listOf("audioLanguage","subtitleLanguage","claimedResolutionAtLeast","videoCodec","dynamicRange","audioCodec")) {
            if(rawFilters[key]!=null && rawFilters[key]!=JsonNull && (rawFilters[key] as? JsonPrimitive)?.isString!=true)invalid()
        }
        if(rawFilters["heightAtLeast"]!=null && rawFilters["heightAtLeast"]!=JsonNull)rawFilters.number("heightAtLeast")
        if(rawFilters["audioDescription"]!=null && rawFilters["audioDescription"]!=JsonNull && (rawFilters["audioDescription"] as? JsonPrimitive)?.let {!it.isString && it.booleanOrNull==true}!=true)invalid()
        val filters=runCatching {ApiJson.decodeFromJsonElement<SourceFilters>(rawFilters)}.getOrNull()?.takeIf {it.valid()} ?: invalid()
        val rawCoverage=obj["coverage"] as? JsonObject ?: invalid()
        if((rawCoverage["truncated"] as? JsonPrimitive)?.let {!it.isString && it.booleanOrNull!=null}!=true)invalid()
        val retained=rawCoverage.number("retainedCandidates").takeIf {it in 0..MAX_RETAINED} ?: invalid()
        val coverage=SourceSearchCoverage(retained,rawCoverage["truncated"]!!.jsonPrimitive.boolean)
        val total=obj.number("total");val matched=obj.number("matchedTotal");val unknown=obj.number("unknownTotal")
        if(listOf(total,matched,unknown).any {it !in 0..coverage.retainedCandidates} || matched+unknown>coverage.retainedCandidates)invalid()
        if(filters.active && total!=matched+if(filters.unknown==SourceFilterUnknown.INCLUDE)unknown else 0)invalid()
        val array=obj["rows"] as? JsonArray ?: invalid();if(array.size>MAX_PAGE || array.size>total || array.isEmpty() && total>0)invalid()
        val rows=array.map {raw ->
            val row=raw as? JsonObject ?: invalid();val index=row.number("sourceIndex")
            if(index !in 0 until coverage.retainedCandidates)invalid()
            val version=row.text("candidateVersion")!!.takeIf {candidatePattern.matches(it)} ?: invalid()
            val result=SourceResult.from(row["result"] as? JsonObject ?: invalid(),index,snapshot) ?: invalid()
            val verdict=when(row.text("verdict")) {"unfiltered"->SourceSearchVerdict.UNFILTERED;"match"->SourceSearchVerdict.MATCH;"unknown"->SourceSearchVerdict.UNKNOWN;else->invalid()}
            if(filters.active && verdict==SourceSearchVerdict.UNFILTERED || !filters.active && verdict!=SourceSearchVerdict.UNFILTERED || filters.active && filters.unknown==SourceFilterUnknown.EXCLUDE && verdict==SourceSearchVerdict.UNKNOWN)invalid()
            SourceSearchRow(index,version,result,verdict,row["mediaEvidence"]?.let(MediaEvidenceSerializer::parse))
        }
        if(rows.map {it.sourceIndex}.distinct().size!=rows.size)invalid()
        return SourceSearchPage(snapshot,view,expiry,target,filters,rows,total,matched,unknown,coverage,obj.text("nextCursor",true),obj.text("sourceHealthToken",true))
    }
}
