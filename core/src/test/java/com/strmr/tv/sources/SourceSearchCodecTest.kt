package com.strmr.tv.sources

import com.strmr.tv.api.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceSearchCodecTest {
    private fun reject(value:JsonElement) {try {SourceSearchCodec.page(value);fail("Malformed supported page accepted")}catch(_:ApiError.NotReady){}}
    @Test fun `legacy array is parsed once without claiming filter support`() {
        val raw=JsonArray(listOf(filterRow(0).result.raw));val reply=SourceSearchCodec.initial(raw) as SourceSearchReply.Legacy
        assertEquals(1,reply.rows.size);assertNull(reply.rows.single().result.responseScope);assertEquals(SourceSearchVerdict.UNFILTERED,reply.rows.single().verdict)
    }
    @Test fun `supported page preserves server row index snapshot scope and raw resolve payload`() {
        val page=filterPage(SourceFilters(audioLanguage="fra"),listOf(75),1)
        val decoded=SourceSearchCodec.page(filterPageJson(page));assertEquals(75,decoded.rows.single().sourceIndex);assertEquals(75,decoded.rows.single().result.index);assertEquals("snapshot-a",decoded.rows.single().result.responseScope);assertEquals(page.rows.single().result.raw,decoded.rows.single().result.raw)
    }
    @Test fun `malformed required marker identity counters and duplicate rows reject whole view`() {
        val base=filterPageJson(filterPage())
        for(key in listOf("sourceFiltersVersion","snapshotId","viewId","expiresAt","target","filters","rows","coverage","total","matchedTotal","unknownTotal"))reject(JsonObject(base-key))
        reject(JsonObject(base+("sourceFiltersVersion" to JsonPrimitive("1"))))
        reject(JsonObject(base+("total" to JsonPrimitive(-1))))
        reject(JsonObject(base+("expiresAt" to JsonPrimitive("not a date"))))
        reject(JsonObject(base+("rows" to JsonArray(listOf(base["rows"]!!.jsonArray.single(),base["rows"]!!.jsonArray.single())))))
    }
    @Test fun `unknown optional media evidence does not drop its source row`() {
        val base=filterPageJson(filterPage());val row=base["rows"]!!.jsonArray.single().jsonObject
        val decoded=SourceSearchCodec.page(JsonObject(base+("rows" to JsonArray(listOf(JsonObject(row+("mediaEvidence" to JsonPrimitive("broken"))))))))
        assertEquals(1,decoded.rows.size);assertEquals(0,decoded.rows.single().mediaEvidence!!.version);assertEquals("Synthetic release 0",decoded.rows.single().result.title)
    }
    @Test fun `same coordinates with different metadata leaf are distinct target correlations`() {
        val target=SourceSearchTarget("tmdb:series:41","series",SourceSearchEpisode("tvdb:episode:123",2,3,EpisodeNumbering("tvdb:series:74852","official")))
        val page=filterPage(target=target);assertEquals(target,SourceSearchCodec.page(filterPageJson(page)).target)
        assertNotEquals(target,target.copy(episode=target.episode!!.copy(episodeId="tvdb:episode:456")))
    }
    @Test fun `untrusted row indices hash domains and known-only unknown rows fail safely`() {
        val base=filterPageJson(filterPage());val row=base["rows"]!!.jsonArray.single().jsonObject
        for(changed in listOf(row+("sourceIndex" to JsonPrimitive(100)),row+("candidateVersion" to JsonPrimitive("file_v1_"+"a".repeat(64))),row+("verdict" to JsonPrimitive("guaranteed_playable"))))reject(JsonObject(base+("rows" to JsonArray(listOf(JsonObject(changed))))))
        val known=filterPageJson(filterPage(SourceFilters(unknown=SourceFilterUnknown.EXCLUDE,audioLanguage="fra")))
        val unknownRow=known["rows"]!!.jsonArray.single().jsonObject+("verdict" to JsonPrimitive("unknown"));reject(JsonObject(known+("rows" to JsonArray(listOf(JsonObject(unknownRow))))))
    }
    @Test fun `empty fake first page with positive total is not reported as zero matches`() {
        val base=filterPageJson(filterPage());reject(JsonObject(base+("rows" to JsonArray(emptyList()))))
    }
    @Test fun `old context API calls fail before replacement profile request or provider search`() = runTest {
        val old=com.strmr.tv.library.ProfileDataContext.current;com.strmr.tv.library.ProfileDataContext.invalidate()
        suspend fun reject(call:suspend ()->Unit) {try {call();fail("Stale source request accepted")}catch(_:CancellationException){}}
        reject {ApiClient.sourceSearch(filterTitle,null,old)};reject {ApiClient.sourceSearchPage("snapshot-a",SourceFilters(),null,old)}
    }
    @Test fun `native request and prototype page exports use production shapes and are labelled prototypes`() {
        val path=System.getProperty("sourceFilterContractsDir") ?: return
        val root=File(path).apply {mkdirs()}
        val episode=Episode("tvdb:episode:123",seasonNumber=2,episodeNumber=3,itemId="history:s02e03",numbering=EpisodeNumbering("tvdb:series:74852","official"))
        val target=DetailsTarget("tmdb:series:41","Synthetic series",false)
        val query=buildJsonObject {sourceSearchQuery(target,episode,"adult").forEach {(key,value)->put(key,value)}}
        File(root,"native-initial-query.json").writeText(query.toString())
        File(root,"native-claims-filter.json").writeText(sourceSearchPageBody(SourceFilters(audioLanguage="fra",claimedResolutionAtLeast="1080p",dynamicRange="hdr10"),null).toString())
        File(root,"native-file-audio-conjunction.json").writeText(sourceSearchPageBody(SourceFilters(SourceFilterBasis.PROBED,SourceFilterUnknown.EXCLUDE,audioLanguage="fra",audioCodec="aac",audioDescription=true),null).toString())
        File(root,"native-clear-filter.json").writeText(sourceSearchPageBody(SourceFilters(),null).toString())
        File(root,"native-scoped-usenet-probe.json").writeText(trackProbeRequestBody(filterRow(7).result,"adult",false).toString())
        File(root,"native-scoped-debrid-probe.json").writeText(trackProbeRequestBody(filterRow(7).result,"adult",true).toString())
        File(root,"native-next-page.json").writeText(sourceSearchPageBody(SourceFilters(),"cursor-a").toString())
        File(root,"prototype-supported-page.json").writeText(filterPageJson(filterPage()).toString())
        File(root,"prototype-legacy-array.json").writeText(JsonArray(listOf(filterRow(0).result.raw)).toString())
        File(root,"provenance.json").writeText("""{"origin":"Native production request helpers and synthetic native page builder; not actual Go responses","syntheticOnly":true}""")
    }
    @Test fun `wire bounds allow last retained index but reject oversized pools and pages`() {
        val largest=filterPage(indices=listOf(511),retained=512)
        assertEquals(511,SourceSearchCodec.page(filterPageJson(largest)).rows.single().sourceIndex)
        reject(filterPageJson(largest.copy(coverage=SourceSearchCoverage(513,true))))
        reject(filterPageJson(filterPage(indices=(0..100).toList(),retained=512)))
    }

    @Test fun `global API enum coercion cannot silently change accepted filter semantics`() {
        val base=filterPageJson(filterPage());val filters=base["filters"]!!.jsonObject
        for(changed in listOf(filters-"basis",filters-"unknown",filters+("basis" to JsonPrimitive("future_basis")),filters+("unknown" to JsonPrimitive("future_policy")),filters+("audioLanguage" to JsonPrimitive(123)),filters+("heightAtLeast" to JsonPrimitive("1080")),filters+("audioDescription" to JsonPrimitive("true")))) {
            reject(JsonObject(base+("filters" to JsonObject(changed))))
        }
        val coverage=base["coverage"]!!.jsonObject
        reject(JsonObject(base+("coverage" to JsonObject(coverage+("truncated" to JsonPrimitive("false"))))))
        reject(JsonObject(base+("coverage" to JsonObject(coverage+("retainedCandidates" to JsonPrimitive("100"))))))
    }

}
