package com.strmr.tv.api

import com.strmr.tv.ui.MediaFacts
import com.strmr.tv.player.trackLists
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaEvidenceTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val observed = "2026-11-16T11:59:00Z"
    private val expires = "2026-11-16T12:01:00Z"
    private val fileVersion = "file_v1_" + "a".repeat(64)
    private val releaseVersion = "candidate_v1_" + "b".repeat(64)
    private val movie = Title("tmdb:movie:41","Synthetic movie",mediaType="movie",tmdbId=41)
    private val series = Title("tvdb:series:74852","Synthetic series",mediaType="series",tmdbId=41,tvdbId=74852)
    private val episode = Episode("tvdb:episode:123",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering("tvdb:series:74852","official"))
    private val target = PrequeueStatus.TargetEpisode(2,3,episode.id,episode.numbering)
    private fun original(sourceId: String = "tmdb:movie:41") = OriginalLanguageEvidence("fra","tmdb",sourceId,observed,expires)
    private fun candidate() = CandidateMediaFacts("release_metadata",observed,expires,listOf("deu"),listOf("spa"),"2160p","hevc",listOf("eac3"),"hdr10")
    private fun probed() = ProbedMediaFacts("ffprobe",observed,expires,listOf(MediaAudioStream(4,"fra","aac",true)),true,
        listOf(MediaSubtitleStream(7,"spa","subrip",true)),true,MediaVideoFacts("h264",1920,1080,"sdr"))
    private fun evidence() = MediaEvidence(1,fileVersion,releaseVersion,"tmdb:movie:41",candidate=candidate(),probed=probed())
    private fun show(title: Title = movie, facts: MediaEvidence? = null, at: Instant = now) =
        MediaFacts.present(title,facts,now=at,locale=Locale.US,zone=ZoneId.of("UTC"))
    private fun json(facts: MediaEvidence) = ApiJson.encodeToJsonElement(facts).jsonObject
    private fun parse(obj: JsonElement) = ApiJson.decodeFromJsonElement<MediaEvidence>(obj)

    @Test fun `catalogue original language is separate from file audio and release claims`() {
        val shown = show(movie.copy(originalLanguageEvidence=original()),evidence())
        assertTrue(shown.catalog.contains("French"))
        assertTrue(shown.release.any { it.contains("German") })
        assertTrue(shown.file.any { it.contains("French") })
        assertTrue(shown.file.any { it.contains("1920 × 1080") })
        assertTrue(shown.release.any { it.contains("2160p") })
        assertTrue(shown.file.any { it.contains("do not verify the spoken language") })
    }
    @Test fun `legacy title language is never promoted to original language or dubbed audio`() {
        val legacy = ApiJson.decodeFromString<Title>("""{"id":"tmdb:movie:41","name":"Synthetic","mediaType":"movie","language":"de"}""")
        assertEquals("Original language (catalog): Unknown",show(legacy).catalog)
        assertTrue(show(legacy).file.isEmpty())
    }
    @Test fun `known same-kind TMDB aliases and verified side ID support catalogue language`() {
        for (title in listOf(movie.copy(originalLanguageEvidence=original()),
            series.copy(originalLanguageEvidence=original("tmdb:series:41")),
            series.copy(id="tmdb:tv:41",originalLanguageEvidence=original("tmdb:series:41")))) {
            assertTrue(show(title).catalog.contains("French"))
        }
    }
    @Test fun `cross-kind and explicit provider contradictions cannot be overridden by side IDs`() {
        for (title in listOf(movie.copy(id="tmdb:movie:9",originalLanguageEvidence=original()),
            movie.copy(originalLanguageEvidence=original("tmdb:series:41")),
            movie.copy(id="tmdb:series:41",originalLanguageEvidence=original()),
            movie.copy(mediaType="person",originalLanguageEvidence=original()),
            series.copy(id="tvdb:series:9",originalLanguageEvidence=original("tmdb:series:41")))) {
            assertEquals("Original language (catalog): Unknown",show(title).catalog)
        }
    }
    @Test fun `catalogue fact remains bound to the actual page while verified provider aliases still match`() {
        val loaded=movie.copy(originalLanguageEvidence=original())
        assertTrue(MediaFacts.present(loaded,target=DetailsTarget("tmdb:movie:9","Other",true,tmdbId="9"),now=now).catalog.endsWith("Unknown"))
        assertTrue(MediaFacts.present(loaded,target=DetailsTarget(movie.id,"Contradictory",true,tmdbId="9"),now=now).catalog.endsWith("Unknown"))
        val alias=loaded.copy(id="tvdb:movie:88",tvdbId=88)
        assertTrue(MediaFacts.present(alias,target=DetailsTarget(movie.id,"Same",true,tmdbId="41"),now=now).catalog.contains("French"))
    }
    @Test fun `explicit TVDB movie IDs remain distinct from series and legacy TVDB namespaces`() {
        val tvdbMovie = movie.copy(id="tvdb:movie:88",tvdbId=88,originalLanguageEvidence=original())
        assertTrue(show(tvdbMovie).catalog.contains("French"))
        assertTrue(show(tvdbMovie,evidence().copy(titleId="tvdb:movie:88")).file.isNotEmpty())
        assertTrue(show(tvdbMovie,evidence().copy(titleId="tvdb:series:88")).file.isEmpty())
        assertTrue(show(tvdbMovie,evidence().copy(titleId="tvdb:88")).file.isEmpty())
    }
    @Test fun `old missing future expired and invalid observation times remain unknown`() {
        for (fact in listOf(original().copy(observedAt=null),original().copy(expiresAt=null),original().copy(observedAt="bad"),
            original().copy(observedAt="2026-11-16T12:00:01Z"),original().copy(expiresAt="2026-11-16T12:00:00Z"),
            original().copy(observedAt=expires,expiresAt=observed))) {
            assertTrue(show(movie.copy(originalLanguageEvidence=fact)).catalog.endsWith("Unknown"))
        }
        val expired = show(movie.copy(originalLanguageEvidence=original()),evidence(),Instant.parse(expires))
        assertTrue(expired.catalog.endsWith("Unknown")); assertTrue(expired.release.isEmpty()); assertTrue(expired.file.isEmpty())
        assertEquals("Source metadata: Unknown",expired.notice)
    }
    @Test fun `local expiration boundary schedules no network refresh and does not renew provenance`() {
        val due = now.plusMillis(25)
        val original = original().copy(expiresAt=due.toString())
        assertEquals(25L,MediaFacts.nextUpdateMillis(original,null,now))
        assertTrue(show(movie.copy(originalLanguageEvidence=original),at=now).catalog.contains("French"))
        assertTrue(show(movie.copy(originalLanguageEvidence=original),at=due).catalog.endsWith("Unknown"))
        assertEquals(observed,original.observedAt)
        assertEquals(60_000L,MediaFacts.nextUpdateMillis(null,null,now))
    }
    @Test fun `provenance TTL beyond catalogue 24 hours or source 15 minutes remains unknown`() {
        assertTrue(show(movie.copy(originalLanguageEvidence=original().copy(expiresAt="2026-11-17T12:00:00Z"))).catalog.endsWith("Unknown"))
        val tooLong=evidence().copy(candidate=candidate().copy(expiresAt="2026-11-16T12:15:01Z"),probed=probed().copy(expiresAt="2026-11-16T12:15:01Z"))
        assertTrue(show(movie,tooLong).file.isEmpty()); assertTrue(show(movie,tooLong).release.isEmpty())
        assertEquals(60_000L,MediaFacts.nextUpdateMillis(original().copy(expiresAt="+999999999-01-01T00:00:00Z"),null,now))
    }
    @Test fun `candidate and file domains cannot be substituted even when their hex suffixes match`() {
        assertTrue(show(movie,evidence().copy(sourceVersion=releaseVersion)).file.isEmpty())
        assertTrue(show(movie,evidence().copy(candidateVersion=fileVersion)).file.isEmpty())
        assertTrue(show(movie,evidence().copy(probed=null,sourceVersion=fileVersion)).release.isEmpty())
        assertTrue(show(movie,evidence().copy(probed=null,sourceVersion=releaseVersion,candidateVersion="candidate_v1_"+"c".repeat(64))).release.isEmpty())
    }
    @Test fun `unrecognized source or future schema cannot promote positive facts`() {
        assertTrue(show(movie,evidence().copy(version=2)).file.isEmpty())
        assertTrue(show(movie,evidence().copy(probed=probed().copy(source="client_claim"),candidate=null)).file.isEmpty())
        assertTrue(show(movie.copy(originalLanguageEvidence=original().copy(source="legacy"))).catalog.endsWith("Unknown"))
    }
    @Test fun `release and file versions are independent and never required equal`() {
        val evidence = evidence()
        assertNotEquals(evidence.sourceVersion,evidence.candidateVersion)
        assertTrue(show(movie,evidence).file.isNotEmpty())
        val mismatch = MediaFacts.present(movie,evidence,originCandidateVersion=("candidate_v1_" + "c".repeat(64)),now=now)
        assertTrue(mismatch.file.isEmpty()); assertEquals("Source metadata: Unknown",mismatch.notice)
        assertTrue(MediaFacts.present(movie,evidence,originCandidateVersion=releaseVersion,now=now).file.isNotEmpty())
    }
    @Test fun `missing selected release association stays qualified while known mismatch cannot attach`() {
        val unassociated=evidence().copy(candidateVersion=null)
        val selected=MediaFacts.present(movie,unassociated,originCandidateVersion=releaseVersion,now=now)
        assertTrue(selected.file.isNotEmpty());assertTrue(selected.notice!!.contains("selected release is unconfirmed"))
        assertTrue(selected.compactQualification!!.contains("Selected release association unconfirmed"))
        val direct=MediaFacts.present(movie,unassociated,now=now)
        assertTrue(direct.file.isNotEmpty());assertNull(direct.notice)
        val sourceOnly=MediaFacts.present(movie,unassociated.copy(titleId=null),originCandidateVersion=releaseVersion,now=now)
        assertTrue(sourceOnly.compactQualification!!.contains("Source only"))
        assertTrue(sourceOnly.compactQualification!!.contains("Selected release association unconfirmed"))
    }
    @Test fun `expired candidate claims do not erase an explicit association for a still fresh probed file`() {
        val value=evidence().copy(candidate=candidate().copy(expiresAt="2026-11-16T11:59:59Z"))
        val shown=MediaFacts.present(movie,value,originCandidateVersion=releaseVersion,now=now)
        assertTrue(shown.release.isEmpty());assertTrue(shown.file.isNotEmpty());assertNull(shown.notice)
        val wrong=MediaFacts.present(movie,value,originCandidateVersion="candidate_v1_"+"c".repeat(64),now=now)
        assertTrue(wrong.file.isEmpty())
    }
    @Test fun `one envelope is never combined with another source or another episode`() {
        val first = evidence().copy(titleId=series.id,targetEpisode=target)
        val matching = MediaFacts.present(series,first,episode=episode,now=now)
        assertTrue(matching.file.isNotEmpty()); assertNull(matching.notice)
        for (different in listOf(episode.copy(id="tvdb:episode:456"),episode.copy(episodeNumber=4),
            episode.copy(numbering=EpisodeNumbering("tvdb:series:9","official")),episode.copy(numbering=EpisodeNumbering("tvdb:series:74852","dvd")),
            episode.copy(numbering=EpisodeNumbering("tvdb:series:74852","default")))) {
            val shown=MediaFacts.present(series,first,episode=different,now=now)
            assertTrue(shown.file.isEmpty())
        }
    }
    @Test fun `unbound source facts never become whole-title or whole-series claims`() {
        val shown=MediaFacts.present(series,evidence().copy(titleId=null,targetEpisode=null),episode=episode,now=now)
        assertTrue(shown.file.isNotEmpty()); assertTrue(shown.notice!!.contains("association is unconfirmed"))
        val partial=MediaFacts.present(series,evidence().copy(titleId=series.id,targetEpisode=target.copy(numbering=null)),episode=episode,now=now)
        assertTrue(partial.notice!!.contains("unconfirmed"))
        val noLeaf=MediaFacts.present(series,evidence().copy(titleId=series.id,targetEpisode=target.copy(episodeId=null)),episode=episode,now=now)
        assertTrue(noLeaf.file.isNotEmpty());assertTrue(noLeaf.notice!!.contains("unconfirmed"))
    }
    @Test fun `unknown namespaces or nonofficial matching tuples stay source only rather than confirmed episode associations`() {
        for (numbering in listOf(EpisodeNumbering("tvdb:tv:74852","official"),EpisodeNumbering("tvdb:series:74852","dvd"),EpisodeNumbering("opaque:series","official"))) {
            val ep=episode.copy(numbering=numbering)
            val shown=MediaFacts.present(series,evidence().copy(titleId=series.id,targetEpisode=target.copy(numbering=numbering)),episode=ep,now=now)
            assertTrue(shown.file.isNotEmpty());assertTrue(shown.notice!!.contains("unconfirmed"))
        }
        assertTrue(show(series.copy(id="tvdb:tv:74852",originalLanguageEvidence=original("tmdb:series:41"))).catalog.endsWith("Unknown"))
    }
    @Test fun `missing AD HDR and completeness flags are unknown rather than negative`() {
        val probe = probed().copy(audioStreams=listOf(MediaAudioStream(1,"fra","aac")),audioStreamsComplete=false,
            subtitleStreams=null,subtitleStreamsComplete=false,video=MediaVideoFacts("hevc",1920,1080))
        val shown=show(movie,evidence().copy(candidate=null,probed=probe))
        assertTrue(shown.file.any { it.contains("AD tag unknown") })
        assertTrue(shown.file.any { it=="Dynamic range metadata: Unknown" })
        assertTrue(shown.file.any { it.contains("may be incomplete") })
        assertFalse(shown.file.any { it.contains("SDR") || it.contains("AD tag absent") })
    }
    @Test fun `actual false AD disposition and recognized SDR may be displayed as container metadata`() {
        val shown=show(movie,evidence().copy(probed=probed().copy(audioStreams=listOf(MediaAudioStream(1,"fra","aac",false)))))
        assertTrue(shown.file.any { it.contains("AD tag absent") })
        assertTrue(shown.file.contains("Dynamic range metadata: SDR"))
    }
    @Test fun `malformed optional fields do not discard parent title source status or page`() {
        for (bad in listOf(JsonPrimitive("bad"),JsonPrimitive(1),JsonArray(emptyList()),buildJsonObject { put("version","1") },JsonNull)) {
            val title=ApiJson.decodeFromJsonElement<Title>(buildJsonObject { put("id",movie.id);put("name","Synthetic");put("mediaEvidence",bad);put("originalLanguageEvidence",bad) })
            assertEquals("Synthetic",title.name)
            val source=SourceResult.from(buildJsonObject {put("title","Synthetic release");put("mediaEvidence",bad)},0)!!
            assertEquals("Synthetic release",source.title)
            val status=ApiJson.decodeFromJsonElement<PrequeueStatus>(buildJsonObject {put("prequeueId","synthetic");put("status","ready");put("streamPath","localmedia:synthetic");put("mediaEvidence",bad)})
            assertEquals("localmedia:synthetic",status.streamPath)
        }
    }
    @Test fun `oversized malformed duplicate or clipped arrays never assert complete track knowledge`() {
        val base=json(evidence());val probe=base["probed"]!!.jsonObject
        val invalid=listOf<JsonElement>(JsonArray((0..32).map {buildJsonObject {put("index",it)}}),
            JsonArray(listOf(JsonPrimitive("bad"))),JsonArray(listOf(buildJsonObject {put("index",1)},buildJsonObject {put("index",1)})))
        for (array in invalid) {
            val parsed=parse(JsonObject(base+("probed" to JsonObject(probe+("audioStreams" to array)))))
            assertNull(parsed.probed!!.audioStreams);assertFalse(parsed.probed.audioStreamsComplete)
        }
    }
    @Test fun `strings masquerading as booleans cannot claim AD SDR or complete streams`() {
        val base=json(evidence());val probe=base["probed"]!!.jsonObject
        val bad=JsonObject(probe+mapOf("audioStreamsComplete" to JsonPrimitive("true"),"audioStreams" to buildJsonArray {add(buildJsonObject {put("index",1);put("audioDescription","false")})},
            "video" to buildJsonObject {put("hdr",false)}))
        val parsed=parse(JsonObject(base+("probed" to bad)))
        assertNull(parsed.probed!!.audioStreams!!.single().audioDescription);assertFalse(parsed.probed.audioStreamsComplete);assertNull(parsed.probed.video!!.hdr)
    }
    @Test fun `malformed opaque versions and raw credentiallike values are never displayed`() {
        val base=json(evidence())
        for (field in listOf("sourceVersion","candidateVersion")) {
            val value=parse(JsonObject(base+(field to JsonPrimitive("https://private.invalid/path?token=synthetic"))))
            val shown=show(movie,value)
            assertTrue(shown.file.isEmpty());assertFalse(shown.toString().contains("private.invalid"))
        }
        val altered=parse(JsonObject(base+("probed" to buildJsonObject {put("source","ffprobe");put("observedAt",observed);put("expiresAt",expires);put("video",buildJsonObject {put("codec","private/path");put("hdr","unknown")})})))
        assertFalse(show(movie,altered).toString().contains("private/path"))
    }
    @Test fun `unknown language is not mistaken for a requested language`() {
        val shown=show(movie,evidence().copy(probed=probed().copy(audioStreams=listOf(MediaAudioStream(1,null,"aac")))))
        assertTrue(shown.file.any { it.startsWith("Audio tag 1: Unknown") })
        assertFalse(shown.file.any { it.startsWith("Audio tag 1: French") })
    }
    @Test fun `compact source summary retains scope qualifier metadata caveat incompleteness and observation`() {
        val probe=probed().copy(audioStreams=(0 until 32).map { MediaAudioStream(it,"fra","aac") },audioStreamsComplete=false)
        val shown=show(movie,evidence().copy(titleId=null,targetEpisode=null,probed=probe))
        assertNotNull(shown.compactReleaseClaims)
        assertTrue(shown.compactFileMetadata!!.contains("1920 × 1080"))
        assertTrue(shown.compactQualification!!.contains("Source only"))
        assertTrue(shown.compactQualification!!.contains("do not verify speech"))
        assertTrue(shown.compactQualification!!.contains("may be incomplete"))
        assertTrue(shown.compactQualification!!.contains("Oldest observation"))
        assertFalse(shown.compactQualification!!.contains("file_v1_"))
    }
    @Test fun `one expired evidence block does not refresh or promote the other block`() {
        val expired="2026-11-16T11:59:59Z"
        val onlyFile=show(movie,evidence().copy(candidate=candidate().copy(expiresAt=expired)))
        assertTrue(onlyFile.release.isEmpty());assertTrue(onlyFile.file.isNotEmpty())
        val onlyClaim=show(movie,evidence().copy(probed=probed().copy(expiresAt=expired)))
        assertTrue(onlyClaim.release.isNotEmpty());assertTrue(onlyClaim.file.isEmpty())
        assertNull(onlyClaim.compactFileMetadata)
    }
    @Test fun `existing health responses and prequeue track projection retain optional facts`() {
        val evidence=evidence()
        val usenet=ApiJson.decodeFromJsonElement<UsenetTrackProbeHealth>(buildJsonObject {put("healthy",true);put("tracksProbed",true);put("mediaEvidence",json(evidence))})
        val debrid=ApiJson.decodeFromJsonElement<DebridTrackProbeHealth>(buildJsonObject {put("cached",true);put("mediaEvidence",json(evidence))})
        assertEquals(evidence,usenet.mediaEvidence);assertEquals(evidence,debrid.mediaEvidence)
        val status=PrequeueStatus("synthetic","ready",mediaEvidence=evidence)
        assertEquals(evidence,status.trackLists.mediaEvidence)
    }
    @Test fun `real native serializers export optional catalogue candidate file and probe response contracts`() {
        val out=System.getProperty("mediaEvidenceContractsDir")?.let(::File);out?.mkdirs()
        val file=evidence().copy(titleId=null)
        val values=linkedMapOf<String,JsonElement>(
            "title-original-movie" to ApiJson.encodeToJsonElement(movie.copy(originalLanguageEvidence=original())),
            "title-original-series" to ApiJson.encodeToJsonElement(series.copy(originalLanguageEvidence=original("tmdb:series:41"))),
            "candidate-source" to buildJsonObject {put("title","Synthetic release");put("guid","synthetic-release");put("mediaEvidence",json(file.copy(sourceVersion=releaseVersion,candidateVersion=null,probed=null)))},
            "prequeue-probed-movie" to ApiJson.encodeToJsonElement(PrequeueStatus("synthetic","ready",streamPath="localmedia:synthetic",mediaEvidence=file)),
            "prequeue-probed-episode" to ApiJson.encodeToJsonElement(PrequeueStatus("synthetic-episode","ready",targetEpisode=target,streamPath="plexmedia:synthetic",mediaEvidence=file.copy(titleId=series.id,targetEpisode=target))),
            "usenet-probe" to ApiJson.encodeToJsonElement(UsenetTrackProbeHealth(healthy=true,tracksProbed=true,mediaEvidence=file)),
            "debrid-probe" to ApiJson.encodeToJsonElement(DebridTrackProbeHealth(cached=true,mediaEvidence=file)),
            "probed-unknown-flags" to json(file.copy(candidate=null,probed=probed().copy(audioStreams=listOf(MediaAudioStream(1,"fra","aac")),audioStreamsComplete=false,video=MediaVideoFacts("hevc",1920,1080)))),
        )
        for ((name,value) in values) out?.resolve("$name.json")?.writeText(value.toString()+"\n")
        assertEquals(8,values.size)
    }
}
