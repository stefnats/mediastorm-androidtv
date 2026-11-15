package com.strmr.tv.home

import com.strmr.tv.api.*
import java.time.Instant
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeCoverageTest {
    private val now=Instant.parse("2026-11-15T12:00:00Z")
    private fun coverage(found: Int=1, missing: Int=1, unknown: Int=0, season: Int=2): EpisodeSourceCoverage {
        val state=if(found>0 && missing>0) "partial" else "unknown"
        return EpisodeSourceCoverage(state,found,missing,unknown,false,listOf(SeasonSourceCoverage(season,state,found,missing,unknown)))
    }
    private fun source(c: EpisodeSourceCoverage?=coverage())=SourceAvailability("found",now.minusSeconds(30).toString(),now.plusSeconds(90).toString(),complete=false,reason="partial_episode_candidates",episodeCoverage=c)
    private fun json(c: EpisodeSourceCoverage=coverage())=EpisodeSourceCoverageSerializer.toJson(c).toString()

    @Test fun `legacy source and episode payloads retain their existing meaning`() {
        val source=ApiJson.decodeFromString<SourceAvailability>("""{"state":"found","complete":false,"reason":"matching_candidates"}""")
        assertNull(source.episodeCoverage)
        val response=ApiJson.decodeFromString<EpisodeSourceResponse>("""{"enabled":true,"version":3,"items":[]}""")
        assertNull(response.episodeCoverage)
        assertEquals(source,ApiJson.decodeFromString<SourceAvailability>(ApiJson.encodeToString(source)))
    }
    @Test fun `additive real series title payload displays partial candidate evidence without dimming`() {
        for(id in listOf("tmdb:series:123","tvdb:series:74852")) {
            val encoded=ApiJson.encodeToString(source())
            val title=ApiJson.decodeFromString<Title>("""{"id":"$id","name":"Synthetic Series","mediaType":"series","sourceAvailability":$encoded}""")
            val card=HomeCard.from(title)
            assertEquals(id,card.target!!.titleId)
            val hint=SourceHint.from(card.sourceAvailability,now)!!
            assertEquals(SourceHint.Kind.PARTIAL,hint.kind)
            assertFalse(hint.dimArtwork)
            assertEquals("Partial episode coverage",hint.label)
            assertTrue(hint.description().contains("1 with candidates, 1 without candidates, 0 unknown"))
            assertTrue(hint.description().contains("Full series coverage is unknown"))
            assertTrue(hint.description().contains("not proof of playback"))
            assertEquals(source(),ApiJson.decodeFromString<SourceAvailability>(encoded))
        }
    }
    @Test fun `found only and found plus unknown never claim complete or partial coverage`() {
        for(c in listOf(coverage(1,0,0),coverage(1,0,2))) {
            assertTrue(c.valid);assertFalse(c.partial)
            val hint=SourceHint.from(source(c).copy(reason="episode_candidates"),now)!!
            assertEquals(SourceHint.Kind.CANDIDATE,hint.kind)
            assertFalse(hint.dimArtwork)
            assertTrue(hint.description().contains("Episode coverage unknown"))
            assertFalse(hint.description().contains("Partial episode coverage"))
        }
    }
    @Test fun `missing only and unknown episode observations cannot grey the whole series`() {
        for(c in listOf(coverage(0,2,0),coverage(0,0,3))) {
            val hint=SourceHint.from(source(c).copy(state="not_found",complete=true,reason="no_matching_candidates"),now)!!
            assertEquals(SourceHint.Kind.UNKNOWN,hint.kind);assertFalse(hint.dimArtwork)
            assertTrue(hint.description().contains("Full series coverage is unknown"))
        }
    }
    @Test fun `general series candidate without observations never invents coverage`() {
        val hint=SourceHint.from(source(null).copy(reason="matching_candidates",complete=true),now)!!
        assertEquals(SourceHint.Kind.CANDIDATE,hint.kind)
        assertNull(hint.episodeCoverageDescription)
        assertFalse(hint.description().contains("episodes"))
    }
    @Test fun `malformed coverage does not discard otherwise usable title or episode rows`() {
        val original=EpisodeSourceCoverageSerializer.toJson(coverage())
        val invalid=listOf("[]","42","null","{}",
            json().replace("\"partial\"","\"all_available\""),
            JsonObject(original+("catalogueComplete" to JsonPrimitive(true))).toString(),
            JsonObject(original+("foundEpisodes" to JsonPrimitive(-1))).toString(),
            JsonObject(original+("foundEpisodes" to JsonPrimitive(1013))).toString(),
            JsonObject(original+("foundEpisodes" to JsonPrimitive("1"))).toString(),
            JsonObject(original+("notFoundEpisodes" to JsonPrimitive(0))).toString())
        for(raw in invalid) {
            val title=ApiJson.decodeFromString<Title>("""{"id":"tmdb:series:123","name":"Synthetic","sourceAvailability":{"state":"found","episodeCoverage":$raw}}""")
            assertEquals("Synthetic",title.name);assertNull(title.sourceAvailability!!.episodeCoverage)
            val response=ApiJson.decodeFromString<EpisodeSourceResponse>("""{"enabled":true,"version":2,"episodeCoverage":$raw,"items":[{"episodeId":"ep","seasonNumber":2,"episodeNumber":3,"sourceAvailability":{"state":"found"}}]}""")
            assertEquals("ep",response.items.single().episodeId)
            assertFalse(response.episodeCoverage?.valid==true)
        }
    }
    @Test fun `malformed additive coverage cannot revive a whole-series missing badge`() {
        for(raw in listOf("[]","{}",json().replace("false","true"),json().replace("\"foundEpisodes\":1","\"foundEpisodes\":-1"))) {
            val value=ApiJson.decodeFromString<SourceAvailability>("""{"state":"not_found","complete":true,"reason":"no_matching_candidates","checkedAt":"2026-11-15T11:59:30Z","expiresAt":"2026-11-15T12:01:30Z","episodeCoverage":$raw}""")
            val hint=SourceHint.from(value,now)!!
            assertEquals(SourceHint.Kind.UNKNOWN,hint.kind);assertFalse(hint.dimArtwork)
            assertFalse(value.complete)
        }
        val manual=source(coverage().copy(catalogueComplete=true)).copy(state="not_found",complete=true,reason="no_matching_candidates")
        assertFalse(SourceHint.from(manual,now)!!.dimArtwork)
    }
    @Test fun `duplicate or inconsistent seasons cannot establish partial coverage`() {
        assertFalse(coverage().copy(seasons=List(2){coverage().seasons.single()}).valid)
        assertFalse(coverage().copy(seasons=emptyList()).valid)
        assertFalse(coverage().copy(seasons=listOf(coverage().seasons.single().copy(seasonNumber=-1))).valid)
        assertFalse(coverage().copy(state="unknown").valid)
        assertFalse(coverage().copy(catalogueComplete=true).valid)
    }
    @Test fun `season summaries use exact season zero and never a catalogue denominator`() {
        val c=EpisodeSourceCoverage("partial",2,1,1,false,listOf(SeasonSourceCoverage(0,"unknown",1,0,1),SeasonSourceCoverage(2,"partial",1,1,0)))
        assertTrue(c.valid)
        val specials=episodeCoverageText(c,0)!!
        assertTrue(specials.startsWith("Episode coverage unknown"));assertTrue(specials.contains("1 with candidates, 0 without candidates, 1 unknown"))
        assertTrue(episodeCoverageText(c,2)!!.startsWith("Partial episode coverage"))
        assertNull(episodeCoverageText(c,1))
        for(text in listOf(specials,episodeCoverageText(c,2)!!)) {
            assertFalse(text.contains("/"));assertFalse(text.contains("all available",ignoreCase=true))
            assertTrue(text.contains("Observed checks"));assertTrue(text.contains("Full series coverage is unknown"))
        }
    }
    @Test fun `expired future and manual recheck title evidence cannot keep a partial claim`() {
        for(value in listOf(source().copy(expiresAt=now.toString()),source().copy(checkedAt=now.plusSeconds(1).toString()),source().copy(reason="recheck_queued"))) {
            val hint=SourceHint.from(value,now)!!
            assertEquals(SourceHint.Kind.UNKNOWN,hint.kind);assertFalse(hint.dimArtwork);assertNull(hint.episodeCoverageDescription)
        }
    }
    @Test fun `local aggregate remains a candidate with bounded local freshness and no single file guarantee`() {
        val hint=SourceHint.from(source().copy(sourceType="local"),now)!!
        assertEquals(SourceHint.Kind.PARTIAL,hint.kind)
        assertFalse(hint.description().contains("The local file and requested audio were checked"))
        val tooOld=source().copy(sourceType="local",expiresAt=now.plusSeconds(91).toString())
        assertEquals(SourceHint.Kind.UNKNOWN,SourceHint.from(tooOld,now)!!.kind)
        val foundOnly=source(coverage(1,0,0)).copy(sourceType="local",reason="episode_candidates")
        assertEquals(SourceHint.Kind.CANDIDATE,SourceHint.from(foundOnly,now)!!.kind)
    }
    @Test fun `episode view aggregate summary ages out independently of episode proof timers`() {
        val evidence=EpisodeSourceEvidence(emptyMap(),coverage(),now)
        assertNotNull(episodeCoverageText(evidence,2,now.plusSeconds(29)))
        assertNull(episodeCoverageText(evidence,2,now.plusSeconds(30)))
        assertNull(episodeCoverageText(evidence,2,now.minusSeconds(1)))
        assertNull(episodeCoverageText(EpisodeSourceEvidence(),2,now))
    }
    @Test fun `native contract fixtures round trip through title and episode serializers`() {
        for ((provider,id) in listOf("tmdb" to "tmdb:series:123","tvdb" to "tvdb:series:74852")) {
            for (mode in listOf("partial","unknown","legacy")) {
                val c=when(mode) { "partial" -> coverage(1,1,1); "unknown" -> coverage(1,0,2); else -> null }
                val scalar=source(c).copy(reason=if(mode=="partial")"partial_episode_candidates" else "matching_candidates")
                val title=Title(id,"Synthetic Series",mediaType="series",sourceAvailability=scalar)
                val titleJson=ApiJson.encodeToString(title)
                assertEquals(title,ApiJson.decodeFromString<Title>(titleJson))
                val items=(1..3).map { number ->
                    val value=when {
                        number==1 -> source(null).copy(reason="matching_candidates")
                        mode=="partial" && number==2 -> source(null).copy(state="not_found",complete=true,reason="no_matching_candidates")
                        else -> SourceAvailability(reason="not_checked")
                    }
                    EpisodeSourceItem("synthetic-$provider-s2-e$number",2,number,value)
                }
                val response=EpisodeSourceResponse(true,7,items,c)
                val responseJson=ApiJson.encodeToString(response)
                assertEquals(response,ApiJson.decodeFromString<EpisodeSourceResponse>(responseJson))
                System.getenv("MEDIASTORM_NATIVE_COVERAGE_CONTRACT_DIR")?.let { directory ->
                    val folder=File(directory);folder.mkdirs()
                    File(folder,"native-title-$provider-$mode.json").writeText(titleJson)
                    File(folder,"native-episodes-$provider-$mode.json").writeText(responseJson)
                }
            }
        }
    }
    private class Gateway(private val coverage: EpisodeSourceCoverage,private val stale: Boolean=false,private val switchContext: Boolean=false): EpisodeSourceGateway {
        val requests=mutableListOf<EpisodeSourceDemand>()
        var current=true
        override fun isCurrent()=current
        override suspend fun update(view: String,body: EpisodeSourceDemand): EpisodeSourceResponse {
            requests+=body
            if(switchContext) current=false
            return EpisodeSourceResponse(true,body.version-if(stale)1 else 0,body.episodes.map {EpisodeSourceItem(it.episodeId,it.seasonNumber,it.episodeNumber,SourceAvailability("found"))},coverage)
        }
    }
    @Test fun `existing observer carries aggregate evidence in the same canonical request and lease`()=runTest {
        for(id in listOf("tmdb:series:123","tvdb:series:74852")) {
            val target=DetailsTarget(id,"Synthetic",false)
            val ep=Episode("ep",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering(id,"official"))
            val gateway=Gateway(coverage())
            val result=EpisodeSourceObserver(gateway,target,"synthetic").snapshots(listOf(ep)).take(2).toList()
            assertNull(result.first().coverage)
            assertEquals(coverage(),result.last().coverage)
            assertNotNull(result.last().receivedAt)
            assertEquals(1,result.last().hints.size)
            assertEquals(2,gateway.requests.size)
            assertEquals(id,gateway.requests.first().titleId)
            assertTrue(gateway.requests.last().episodes.isEmpty())
        }
    }
    @Test fun `a valid snapshot clears immediately when the next reply is disabled stale or another profile`()=runTest {
        val target=DetailsTarget("tvdb:series:74852","Synthetic",false)
        val ep=Episode("ep",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering(target.titleId,"official"))
        for(mode in listOf("disabled","stale","context")) {
            var current=true
            var calls=0
            val gateway=object: EpisodeSourceGateway {
                override fun isCurrent()=current
                override suspend fun update(view: String,body: EpisodeSourceDemand): EpisodeSourceResponse {
                    if(body.episodes.isEmpty()) return EpisodeSourceResponse(true,body.version)
                    calls++
                    val second=calls==2
                    if(second && mode=="context") current=false
                    return EpisodeSourceResponse(!(second && mode=="disabled"),body.version-if(second && mode=="stale")1 else 0,
                        listOf(EpisodeSourceItem(ep.id,2,3,SourceAvailability("found"))),coverage())
                }
            }
            val states=EpisodeSourceObserver(gateway,target,"synthetic").snapshots(listOf(ep)).take(3).toList()
            assertNull(states[0].coverage)
            assertEquals(coverage(),states[1].coverage)
            assertEquals(1,states[1].hints.size)
            assertEquals(EpisodeSourceEvidence(),states[2])
            assertEquals(2,calls)
        }
    }
    @Test fun `a current view clears when its profile changes between polls`()=runTest {
        val target=DetailsTarget("tmdb:series:123","Synthetic",false)
        val ep=Episode("ep",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering(target.titleId,"official"))
        val gateway=Gateway(coverage())
        val states=EpisodeSourceObserver(gateway,target,"synthetic").snapshots(listOf(ep)).onEach {
            if(it.coverage!=null) gateway.current=false
        }.take(3).toList()
        assertEquals(coverage(),states[1].coverage)
        assertEquals(EpisodeSourceEvidence(),states[2])
        assertEquals(1,gateway.requests.size)
    }
    @Test fun `stale and replacement profile replies cannot expose aggregate coverage`()=runTest {
        val target=DetailsTarget("tvdb:series:74852","Synthetic",false)
        val ep=Episode("ep",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering(target.titleId,"official"))
        for(gateway in listOf(Gateway(coverage(),stale=true),Gateway(coverage(),switchContext=true))) {
            val states=EpisodeSourceObserver(gateway,target,"synthetic").snapshots(listOf(ep)).toList()
            assertTrue(states.all {it.coverage==null && it.hints.isEmpty()})
        }
    }
}
