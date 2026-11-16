package com.strmr.tv.sources

import com.strmr.tv.api.*
import java.time.Instant
import kotlinx.serialization.json.*

internal val filterAt:Instant=Instant.parse("2026-11-16T02:00:00Z")
internal val filterTitle=DetailsTarget("tmdb:movie:41","Synthetic movie",true)
internal val filterTarget=SourceSearchTarget.from(filterTitle,null)
internal fun filterRow(index:Int,filters:SourceFilters=SourceFilters(),snapshot:String="snapshot-a",unknown:Boolean=false):SourceSearchRow {
    val raw=buildJsonObject {put("title","Synthetic release $index");put("guid","release-$index");put("serviceType","usenet")}
    return SourceSearchRow(index,"candidate_v1_"+index.toString(16).padStart(64,'0'),SourceResult.from(raw,index,snapshot)!!,
        if(!filters.active)SourceSearchVerdict.UNFILTERED else if(unknown)SourceSearchVerdict.UNKNOWN else SourceSearchVerdict.MATCH)
}
internal fun filterPage(filters:SourceFilters=SourceFilters(),indices:List<Int> = listOf(0),total:Int=indices.size,cursor:String?=null,
    snapshot:String="snapshot-a",view:String="view-a",expiry:Instant=filterAt.plusSeconds(600),target:SourceSearchTarget=filterTarget,
    retained:Int=100,unknown:Int=0):SourceSearchPage = SourceSearchPage(snapshot,view,expiry,target,filters,indices.map {filterRow(it,filters,snapshot)},total,
        if(filters.active)total-(if(filters.unknown==SourceFilterUnknown.INCLUDE)unknown else 0) else 0,unknown,SourceSearchCoverage(retained,false),cursor)
internal fun filterPageJson(page:SourceSearchPage):JsonObject=buildJsonObject {
    put("sourceFiltersVersion",1);put("snapshotId",page.snapshotId);put("viewId",page.viewId);put("expiresAt",page.expiresAt.toString())
    put("target",ApiJson.encodeToJsonElement(page.target));put("filters",page.filters.json())
    put("coverage",ApiJson.encodeToJsonElement(page.coverage));put("total",page.total);put("matchedTotal",page.matchedTotal);put("unknownTotal",page.unknownTotal)
    page.nextCursor?.let {put("nextCursor",it)};page.sourceHealthToken?.let {put("sourceHealthToken",it)}
    putJsonArray("rows") {page.rows.forEach {row ->add(buildJsonObject {put("sourceIndex",row.sourceIndex);put("candidateVersion",row.candidateVersion!!);put("result",row.result.raw);put("verdict",row.verdict.name.lowercase());row.mediaEvidence?.let {put("mediaEvidence",ApiJson.encodeToJsonElement(it))}})}}
}
