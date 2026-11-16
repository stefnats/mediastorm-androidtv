package com.strmr.tv.sources

import com.strmr.tv.api.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceFiltersTest {
    @Test fun `unknown metadata defaults to inclusion and no criteria do not claim known media`() {
        val filters=SourceFilters();assertEquals(SourceFilterUnknown.INCLUDE,filters.unknown);assertFalse(filters.active)
        assertEquals("include",filters.json()["unknown"]!!.jsonPrimitive.content);assertEquals("candidate",filters.json()["basis"]!!.jsonPrimitive.content)
    }
    @Test fun `release claims cannot assert same audio track codec or description conjunction`() {
        assertFalse(SourceFilters(audioLanguage="fra",audioCodec="aac").valid());assertFalse(SourceFilters(audioDescription=true).valid());assertFalse(SourceFilters(heightAtLeast=1080).valid())
        assertFalse(SourceFilterMenus.Field.AUDIO_CODEC in SourceFilterMenus.fields(SourceFilters()));assertFalse(SourceFilterMenus.Field.AD in SourceFilterMenus.fields(SourceFilters()))
    }
    @Test fun `file audio conjunction is sent together without changing profile or subtitle preferences`() {
        val filter=SourceFilters(SourceFilterBasis.PROBED,audioLanguage="fra",subtitleLanguage="cym",audioCodec="aac",audioDescription=true)
        assertTrue(filter.valid());val body=sourceSearchPageBody(filter,null)
        assertEquals(setOf("version","filters","limit"),body.keys);val fields=body["filters"]!!.jsonObject
        assertEquals("fra",fields["audioLanguage"]!!.jsonPrimitive.content);assertEquals("cym",fields["subtitleLanguage"]!!.jsonPrimitive.content);assertEquals("aac",fields["audioCodec"]!!.jsonPrimitive.content);assertTrue(fields["audioDescription"]!!.jsonPrimitive.boolean)
        assertFalse(fields.containsKey("preferredAudioLanguage"));assertFalse(body.containsKey("settings"))
    }
    @Test fun `basis switching clears unsupported criteria and preserves compatible choices`() {
        val file=SourceFilters(SourceFilterBasis.PROBED,audioLanguage="fra",heightAtLeast=1080,videoCodec="hevc",dynamicRange="hdr10",audioCodec="aac",audioDescription=true)
        val claims=file.withBasis(SourceFilterBasis.CANDIDATE);assertTrue(claims.valid());assertNull(claims.heightAtLeast);assertNull(claims.audioCodec);assertNull(claims.audioDescription);assertEquals("fra",claims.audioLanguage);assertEquals("hdr10",claims.dynamicRange)
        assertNull(claims.copy(claimedResolutionAtLeast="2160p").withBasis(SourceFilterBasis.PROBED).claimedResolutionAtLeast)
    }
    @Test fun `height and claimed tier are not confused or silently converted`() {
        assertTrue(SourceFilters(claimedResolutionAtLeast="1080p").valid());assertFalse(SourceFilters(SourceFilterBasis.PROBED,claimedResolutionAtLeast="1080p").valid());assertTrue(SourceFilters(SourceFilterBasis.PROBED,heightAtLeast=800).valid())
        assertEquals("Frame height",SourceFilterMenus.title(SourceFilterMenus.Field.RESOLUTION,SourceFilters(SourceFilterBasis.PROBED)))
    }
    @Test fun `unusual ISO language codes and distinct Norwegian tags remain representable`() {
        for(code in listOf("fra","cym","isl","nds","gsw","nob","nno"))assertEquals(code,SourceFilterLanguages.canonical(code))
        assertEquals("fra",SourceFilterLanguages.canonical("fr"));assertEquals("deu",SourceFilterLanguages.canonical("ger"));assertNull(SourceFilterLanguages.canonical("und"));assertNull(SourceFilterLanguages.canonical("mul"));assertNull(SourceFilterLanguages.canonical("zxx"));assertNull(SourceFilterLanguages.canonical("zzz"))
        val choices=SourceFilterLanguages.choices(listOf("nds","gsw"));assertTrue("nds" in choices);assertTrue("gsw" in choices)
    }
    @Test fun `unsupported criteria fail locally instead of being sent as applied filters`() {
        for(filters in listOf(SourceFilters(audioLanguage="und"),SourceFilters(audioLanguage="fr"),SourceFilters(dynamicRange="maybe_hdr"),SourceFilters(SourceFilterBasis.PROBED,audioDescription=false),SourceFilters(SourceFilterBasis.PROBED,heightAtLeast=-1),SourceFilters(videoCodec="HEVC"))) {
            assertFalse(filters.valid());try {sourceSearchPageBody(filters,null);fail("Invalid filters sent")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun `initial query sends metadata episode leaf distinct from watch history identity`() {
        val target=DetailsTarget("tmdb:series:41","Synthetic series",false,year=2026)
        val episode=Episode("tvdb:episode:123",seasonNumber=2,episodeNumber=3,itemId="tmdb:tv:41:s02e03",numbering=EpisodeNumbering("tvdb:series:74852","official"))
        val query=sourceSearchQuery(target,episode,"adult");assertEquals("tvdb:episode:123",query["episodeId"]);assertFalse(query.containsValue(episode.itemId));assertEquals("1",query["sourceFiltersVersion"]);assertEquals("50",query["limit"]);assertEquals("tvdb:series:74852",query["episodeNumberingId"])
        assertNotEquals(SourceSearchTarget.from(target,episode),SourceSearchTarget.from(target,episode.copy(id="tvdb:episode:999")))
        assertEquals(SourceSearchTarget.from(target,episode),SourceSearchTarget.from(target,episode.copy(itemId="other-history-id")))
    }
    @Test fun `explicit probe correlation retains raw source bytes without supplying proof fields`() {
        val raw=buildJsonObject {put("title","Synthetic");put("guid","release");put("serviceType","usenet")}
        val scoped=SourceResult.from(raw,75,"snapshot-a")!!;val legacy=SourceResult.from(raw,0)!!
        for(debrid in listOf(true,false)) {
            val body=trackProbeRequestBody(scoped,"adult",debrid);assertEquals(raw,body["result"]);assertEquals("snapshot-a",body["snapshotId"]!!.jsonPrimitive.content);assertEquals(75,body["sourceIndex"]!!.jsonPrimitive.int);assertFalse(body.containsKey("mediaEvidence"));assertFalse(body.containsKey("candidateVersion"))
            assertFalse(trackProbeRequestBody(legacy,"adult",debrid).containsKey("snapshotId"))
        }
        assertEquals(raw,scoped.raw)
    }
    @Test fun `canonical resolutions and nonpreset accepted filters retain a visible selection`() {
        assertFalse(SourceFilters(claimedResolutionAtLeast="0144p").valid())
        val claims=SourceFilters(claimedResolutionAtLeast="360p",videoCodec="h265")
        assertTrue(claims.valid())
        assertTrue(SourceFilterMenus.choices(SourceFilterMenus.Field.RESOLUTION,claims,emptyList()).any {it.value=="360p"})
        assertTrue(SourceFilterMenus.choices(SourceFilterMenus.Field.VIDEO,claims,emptyList()).any {it.value=="h265"})
        val file=SourceFilters(SourceFilterBasis.PROBED,heightAtLeast=800,audioCodec="pcm_s24le")
        assertTrue(file.valid())
        assertTrue(SourceFilterMenus.choices(SourceFilterMenus.Field.RESOLUTION,file,emptyList()).any {it.value=="800"})
        assertTrue(SourceFilterMenus.choices(SourceFilterMenus.Field.AUDIO_CODEC,file,emptyList()).any {it.value=="pcm_s24le"})
    }

}
