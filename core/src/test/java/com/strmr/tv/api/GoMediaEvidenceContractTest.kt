package com.strmr.tv.api

import com.strmr.tv.ui.MediaFacts
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Unmodified Go DTO/HTTP exports at the producer's fixed clock; no optional skip or network. */
@RunWith(Parameterized::class)
class GoMediaEvidenceContractTest(private val filename: String, private val kind: String) {
    companion object {
        private fun resource(name: String): ByteArray = requireNotNull(GoMediaEvidenceContractTest::class.java.getResourceAsStream("/media-evidence-go/$name")) { "Missing Go contract resource: $name" }.use { it.readBytes() }
        private val manifest = ApiJson.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
        private val at = Instant.parse(manifest["evaluatedAt"]!!.jsonPrimitive.content)
        @JvmStatic @Parameterized.Parameters(name="{0}: {1}") fun fixtures(): List<Array<String>> {
            assertEquals(1,manifest["version"]!!.jsonPrimitive.int)
            val hashes=ApiJson.parseToJsonElement(resource("sha256.json").decodeToString()).jsonObject
            for ((name,digest) in hashes) {
                val actual=MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("Go response bytes changed: $name",digest.jsonPrimitive.content,actual)
            }
            val files=manifest["files"]!!.jsonObject
            assertEquals(8,files.size)
            return files.entries.sortedBy { it.key }.map { arrayOf(it.key,it.value.jsonPrimitive.content) }
        }
    }
    @Test fun `real Go response decodes and preserves honest scoped metadata presentation`() {
        val body=resource(filename).decodeToString()
        val movie=DetailsTarget("tmdb:movie:41","Synthetic catalogue title",true,tmdbId="41")
        val episode=Episode("episode-3",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering("tvdb:series:74852","official"))
        val series=DetailsTarget("tmdb:series:41","Synthetic series",false,tmdbId="41",tvdbId="74852")
        val evidence: MediaEvidence? = when(kind) {
            "Title" -> {
                val title=ApiJson.decodeFromString<Title>(body)
                assertEquals("is",title.originalLanguageEvidence!!.language)
                assertEquals("tmdb:movie:41",title.originalLanguageEvidence.sourceTitleId)
                val shown=MediaFacts.present(title,now=at,locale=Locale.US,zone=ZoneId.of("UTC"))
                assertFalse(shown.catalog.endsWith("Unknown"));assertTrue(shown.file.isEmpty())
                assertTrue(MediaFacts.present(title,now=at.plus(Duration.ofHours(25))).catalog.endsWith("Unknown"))
                return
            }
            "SourceResult" -> SourceResult.from(ApiJson.parseToJsonElement(body).jsonObject,0)!!.let {
                assertEquals("passed",it.raw["filterStatus"]!!.jsonPrimitive.content)
                it.mediaEvidence
            }
            "PrequeueStatus" -> ApiJson.decodeFromString<PrequeueStatus>(body).let {
                if (filename=="ready-bound-episode.json") {
                    assertEquals(episode.id,it.targetEpisode!!.episodeId)
                    assertEquals(it.targetEpisode,it.mediaEvidence!!.targetEpisode)
                }
                it.mediaEvidence
            }
            "UsenetHealth" -> ApiJson.decodeFromString<UsenetTrackProbeHealth>(body).mediaEvidence
            "DebridHealth" -> ApiJson.decodeFromString<DebridTrackProbeHealth>(body).mediaEvidence
            else -> error("Unknown Go response kind: $kind")
        }
        val bound=filename=="ready-bound-episode.json"
        val shown=MediaFacts.present(evidence=evidence,target=if(bound)series else movie,episode=if(bound)episode else null,
            now=at,locale=Locale.US,zone=ZoneId.of("UTC"))
        when(filename) {
            "expired-unknown.json","legacy-unknown.json" -> {
                assertNull(evidence);assertTrue(shown.file.isEmpty());assertTrue(shown.release.isEmpty())
            }
            "candidate-source.json" -> {
                assertTrue(evidence!!.sourceVersion!!.startsWith("candidate_v1_"))
                assertEquals(listOf("fra"),evidence.candidate!!.audioLanguages)
                assertNull(evidence.probed);assertTrue(shown.release.any { it.contains("2160p") })
                assertTrue(shown.file.isEmpty());assertTrue(shown.notice!!.contains("unconfirmed"))
            }
            else -> {
                val value=requireNotNull(evidence)
                assertTrue(value.sourceVersion!!.startsWith("file_v1_"))
                assertTrue(value.candidateVersion!!.startsWith("candidate_v1_"));assertNotEquals(value.sourceVersion,value.candidateVersion)
                val streams=value.probed!!.audioStreams!!
                assertNull(streams.single { it.index==1 }.audioDescription)
                assertEquals(false,streams.single { it.index==2 }.audioDescription)
                assertEquals(true,streams.single { it.index==3 }.audioDescription)
                assertTrue(shown.file.any { it.contains("AD tag unknown") })
                assertTrue(shown.file.any { it.contains("AD tag absent") })
                assertTrue(shown.file.any { it.contains("AD tag present") })
                assertTrue(shown.file.any { it.contains("1920 × 1080") })
                assertTrue(shown.file.contains("Dynamic range metadata: SDR"))
                assertTrue(shown.release.any { it.contains("2160p") })
                assertTrue(shown.file.any { it.contains("do not verify the spoken language") })
                if(bound) {
                    assertEquals(episode.id,value.targetEpisode!!.episodeId);assertNull(shown.notice)
                    val wrong=MediaFacts.present(evidence=value,target=series,episode=episode.copy(id="other-leaf"),now=at)
                    assertTrue(wrong.file.isEmpty())
                } else {
                    assertNull(value.titleId);assertTrue(shown.notice!!.contains("unconfirmed"))
                }
                if(filename=="probe-clipped.json") {
                    assertEquals(32,streams.size);assertFalse(value.probed.audioStreamsComplete);assertFalse(value.probed.subtitleStreamsComplete)
                    assertTrue(shown.compactQualification!!.contains("may be incomplete"))
                }
                val expired=MediaFacts.present(evidence=value,target=if(bound)series else movie,episode=if(bound)episode else null,
                    now=at.plus(Duration.ofMinutes(16)))
                assertTrue(expired.file.isEmpty());assertTrue(expired.release.isEmpty())
                assertEquals(value.probed.observedAt,at.toString())
            }
        }
    }
}
