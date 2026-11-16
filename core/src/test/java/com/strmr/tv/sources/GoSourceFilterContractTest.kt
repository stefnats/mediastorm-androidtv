package com.strmr.tv.sources

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.MediaFacts
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Actual Go handler bytes, fixed producer time and hashes; no environment skip or provider I/O. */
@RunWith(Parameterized::class)
class GoSourceFilterContractTest(private val filename:String) {
    companion object {
        private fun bytes(name:String)=requireNotNull(GoSourceFilterContractTest::class.java.getResourceAsStream("/source-filters-go/$name")).use {it.readBytes()}
        private fun body(name:String)=bytes(name).decodeToString()
        private val manifest=ApiJson.parseToJsonElement(body("manifest.json")).jsonObject
        private val at=Instant.parse(manifest["evaluatedAt"]!!.jsonPrimitive.content)
        private fun page(name:String)=SourceSearchCodec.page(ApiJson.parseToJsonElement(body(name)))
        @JvmStatic @Parameterized.Parameters(name="{0}") fun fixtures():List<Array<String>> {
            val hashes=ApiJson.parseToJsonElement(body("sha256.json")).jsonObject
            for((name,digest) in hashes) {
                val actual=MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString(""){"%02x".format(it.toInt() and 255)}
                assertEquals("Producer bytes changed: $name",digest.jsonPrimitive.content,actual)
            }
            return manifest["responses"]!!.jsonObject.keys.sorted().map {arrayOf(it)}
        }
    }
    @Test fun `real response preserves server verdicts exact source identities and legacy compatibility`() {
        val value=ApiJson.parseToJsonElement(body(filename))
        when {
            filename=="legacy-array.json"->{
                val reply=SourceSearchCodec.initial(value) as SourceSearchReply.Legacy
                assertTrue(reply.rows.isNotEmpty());assertTrue(reply.rows.all {it.result.responseScope==null && it.verdict==SourceSearchVerdict.UNFILTERED})
            }
            filename.endsWith("error.json")->{
                val expected=when(filename) {
                    "context-changed-error.json"->"source_snapshot_context_changed"
                    "view-changed-error.json"->"source_view_changed"
                    "expired-view-error.json"->"source_view_expired"
                    "expired-snapshot-error.json"->"source_snapshot_expired"
                    else->error("Unknown producer error fixture")
                }
                val error=ApiError.Http(HttpFailure(if(filename=="expired-snapshot-error.json")410 else 409,body(filename)))
                assertEquals(expected,error.response.code)
            }
            filename.endsWith("probe.json")->{
                val probe=ApiJson.decodeFromString<DebridTrackProbeHealth>(body(filename))
                val facts=probe.mediaEvidence!!;assertTrue(facts.sourceVersion!!.startsWith("file_v1_"));assertTrue(facts.candidateVersion!!.startsWith("candidate_v1_"))
                assertNotEquals(facts.sourceVersion,facts.candidateVersion);assertNull(facts.titleId);assertNull(facts.targetEpisode)
                assertNull(facts.probed!!.audioStreams!!.single {it.index==1}.audioDescription)
                assertEquals(false,facts.probed.audioStreams.single {it.index==2}.audioDescription)
                assertEquals(true,facts.probed.audioStreams.single {it.index==3}.audioDescription)
                assertEquals(listOf("fra","eng","cym"),facts.probed.audioStreams.map {it.language})
                val shown=MediaFacts.present(evidence=facts,target=filterTitle,now=at,originCandidateVersion=facts.candidateVersion)
                assertTrue(shown.file.isNotEmpty());assertTrue(shown.notice!!.contains("unconfirmed"))
            }
            else->{
                val result=SourceSearchCodec.page(value)
                assertTrue(at.isBefore(result.expiresAt));assertTrue(result.expiresAt<=at.plusSeconds(900))
                assertTrue(result.rows.all {it.result.index==it.sourceIndex && it.result.responseScope==result.snapshotId})
                for(row in result.rows)assertEquals(value.jsonObject["rows"]!!.jsonArray.first {it.jsonObject["sourceIndex"]!!.jsonPrimitive.int==row.sourceIndex}.jsonObject["result"],row.result.raw)
                when(filename) {
                    "initial-page.json"->{assertEquals(6,result.total);assertEquals(2,result.rows.size);assertNotNull(result.nextCursor);assertEquals(0,result.matchedTotal)}
                    "initial-continuation.json"->{
                        val initial=page("initial-page.json");assertEquals(initial.viewId,result.viewId);assertEquals(initial.snapshotId,result.snapshotId)
                        assertEquals(initial.expiresAt,result.expiresAt);assertEquals(initial.total,result.total);assertEquals(initial.coverage,result.coverage)
                        assertEquals(listOf(2,3,4,5),result.rows.map {it.sourceIndex});assertNull(result.nextCursor)
                    }
                    "claimed-language-page.json"->{assertEquals(listOf(5),result.rows.map {it.sourceIndex});assertEquals(1,result.matchedTotal);assertEquals(5,result.unknownTotal);assertEquals(SourceFilterUnknown.EXCLUDE,result.filters.unknown)}
                    "empty-file-page.json"->{assertTrue(result.rows.isEmpty());assertEquals(0,result.total);assertEquals(6,result.unknownTotal)}
                    "file-movie-page.json"->{
                        assertEquals(1,result.matchedTotal);assertEquals(5,result.unknownTotal)
                        val row=result.rows.single {it.verdict==SourceSearchVerdict.MATCH};assertEquals(5,row.sourceIndex)
                        assertNotNull(row.expectedFileVersion);assertNull(row.result.mediaEvidence!!.probed);assertNotNull(row.mediaEvidence!!.probed)
                        assertNull(row.mediaEvidence.titleId);assertNull(row.mediaEvidence.targetEpisode)
                        assertEquals(page("claimed-language-page.json").rows.single().result.raw,row.result.raw)
                    }
                    "series-initial-page.json"->{assertEquals("tvdb:episode:123",result.target.episode!!.episodeId);assertEquals("tvdb:series:74852",result.target.episode.numbering!!.seriesId)}
                    "series-source-only-page.json"->{
                        assertEquals(0,result.matchedTotal);assertEquals(2,result.unknownTotal);assertTrue(result.rows.all {it.verdict==SourceSearchVerdict.UNKNOWN})
                        val file=result.rows.single {it.mediaEvidence?.probed!=null}
                        assertNotNull(file.expectedFileVersion);assertNull(file.mediaEvidence!!.titleId);assertNull(file.mediaEvidence.targetEpisode)
                        assertNotNull(result.target.episode);assertNotEquals(result.target.episode?.episodeId,file.mediaEvidence.targetEpisode?.episodeId)
                    }
                }
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoSourceFilterStateContractTest {
    private fun body(name:String)=requireNotNull(javaClass.getResourceAsStream("/source-filters-go/$name")).bufferedReader().use {it.readText()}
    private fun page(name:String)=SourceSearchCodec.page(ApiJson.parseToJsonElement(body(name)))
    @Test fun `actual Go initial filtered and invalidated views use only explicit cached requests`() = runTest {
        val manifest=ApiJson.parseToJsonElement(body("manifest.json")).jsonObject;val at=Instant.parse(manifest["evaluatedAt"]!!.jsonPrimitive.content)
        var searches=0;var pages=0;var failure:String?=null
        val transport=object:SourceSearchTransport {
            override suspend fun search(title:DetailsTarget,episode:Episode?,context:Long):SourceSearchReply {searches++;return SourceSearchReply.Supported(page("initial-page.json"))}
            override suspend fun page(snapshotId:String,filters:SourceFilters,cursor:String?,context:Long):SourceSearchPage {
                pages++;failure?.let {throw ApiError.Http(HttpFailure(if(it=="expired-snapshot-error.json")410 else 409,body(it)))}
                if(cursor!=null){assertEquals(page("initial-page.json").nextCursor,cursor);return page("initial-continuation.json")}
                val answer=page("claimed-language-page.json");assertEquals(snapshotId,answer.snapshotId);assertEquals(filters,answer.filters);return answer
            }
            override suspend fun health(token:String,context:Long)=SourceHealthSnapshot(true)
        }
        val state=SourceSearchState(backgroundScope,filterTitle,null,ProfileDataContext.current,transport,{at},{"synthetic-client"})
        state.reload();runCurrent();assertEquals(listOf(0,1),state.rows.map {it.sourceIndex});assertTrue(state.hasMore)
        state.loadMore();runCurrent();assertEquals((0..5).toList(),state.rows.map {it.sourceIndex});assertFalse(state.hasMore)
        state.edit(SourceFilters(unknown=SourceFilterUnknown.EXCLUDE,audioLanguage="fra"));state.apply();runCurrent()
        assertEquals(listOf(5),state.rows.map {it.sourceIndex});assertEquals(1,searches);assertEquals(2,pages)
        failure="view-changed-error.json";state.apply();runCurrent();assertTrue(state.expired);assertFalse(state.requiresReload)
        failure=null;state.retry();runCurrent();assertEquals(1,searches);assertFalse(state.expired)
        failure="expired-view-error.json";state.apply();runCurrent();assertTrue(state.expired);assertFalse(state.requiresReload);assertEquals(1,searches)
        failure="context-changed-error.json";state.apply();runCurrent();assertTrue(state.requiresReload);assertEquals(1,searches)
        state.dispose()
    }
}
