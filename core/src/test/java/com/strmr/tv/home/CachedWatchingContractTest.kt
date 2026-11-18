package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Exact Go bytes plus an explicitly simulated transport Date, not a DTO clock. */
internal object WatchingFixture {
    fun bytes(name:String)=requireNotNull(javaClass.getResourceAsStream("/continue-watching-cached-go/$name.json")).use{it.readBytes()}
    fun wire(name:String)=String(bytes(name),Charsets.UTF_8)
    fun root(name:String="unfiltered")=ApiJson.parseToJsonElement(wire(name)).jsonObject
    val url="http://127.0.0.1:8123/api/users/synthetic/display-list?source=continue-watching-cached".toHttpUrl()
    val date:Long get()=OffsetDateTime.parse(root().getValue("pool").jsonObject.getValue("observedAt").jsonPrimitive.content).toInstant().toEpochMilli()+1000
    fun clock(at:Long=date,rttMs:Long=1):ObservedResponseClock {
        val date=DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'",Locale.US).format(java.time.Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))
        val now=System.nanoTime();return NetworkResponseObservation(url,listOf(date),now-rttMs*1_000_000,now).clock(url)
    }
    fun page(name:String,limit:Int?=null,offset:Int=0)=decodeCachedWatchingPage(wire(name),clock(),limit,offset)
    fun list(expected:Long=ProfileDataContext.current,producer:Job=Job())=requireNotNull(BrowseList.ContinueWatchingCached.open(expected,producer){true})
}
class CachedWatchingContractTest {
    private fun reject(raw:String)=assertTrue(runCatching{decodeCachedWatchingPage(raw,WatchingFixture.clock())}.exceptionOrNull() is ManagementPageFailure)
    @Test fun `all ten actual Go reply bytes match exact producer hashes`() {
        val manifest=WatchingFixture.root("manifest").getValue("sha256").jsonObject;assertEquals(10,manifest.size)
        for((name,hash) in manifest){val bytes=WatchingFixture.bytes(name.removeSuffix(".json"));assertEquals(hash.jsonPrimitive.content,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
            if(name=="missing.json")reject(String(bytes))else decodeCachedWatchingPage(String(bytes),WatchingFixture.clock())}
    }
    @Test fun `late full language pools unknown OR rare and zero result preserve server totals`() {
        assertEquals(700,WatchingFixture.page("unfiltered",2).total)
        assertEquals(696,WatchingFixture.page("unknown-only",0).entries.size)
        assertEquals(698,WatchingFixture.page("or-unknown",0).total)
        assertEquals(1,WatchingFixture.page("rare").total)
        assertEquals(0,WatchingFixture.page("empty",0).total)
        assertEquals(3,WatchingFixture.page("random-pool",0).entries.size)
        assertEquals(1,WatchingFixture.page("continuation",1,1).entries.size)
        assertEquals("tmdb:movie:650",(WatchingFixture.page("late-selected").entries.single() as BrowseEntry.CachedWatchingEntry).item.seriesId)
    }
    @Test fun `actual Go episode history and resume identities remain exact and presentation only`() {
        val row=(WatchingFixture.page("show-episode").entries.single() as BrowseEntry.CachedWatchingEntry).item
        assertEquals("tmdb:tv:800",row.seriesId);assertEquals("series",row.mediaType)
        assertEquals("tvdb:episode:8002",row.nextEpisode!!.episodeId);assertEquals("tmdb:tv:800:s01e02",row.nextEpisode.itemId)
        assertEquals(row.nextEpisode,row.lastWatched);assertEquals(270.0,row.resume!!.position,0.0);assertTrue(row.resume.eligible)
        val card=HomeCard.from(row);assertTrue(card.cacheOnlyBrowseEntry);assertNull(card.titleSourceCoordinate());assertNull(card.artworkUrl);assertNull(card.episode);assertFalse(card.target!!.isMovie)
    }
    @Test fun `numbering complete IDs and zero season special survive without reconstructing history ID`() {
        val root=WatchingFixture.root("show-episode");val row=root.getValue("items").jsonArray.single().jsonObject
        val episode=row.getValue("nextEpisode").jsonObject+mapOf("seasonNumber" to JsonPrimitive(0),"absoluteEpisodeNumber" to JsonPrimitive(123),"tvdbId" to JsonPrimitive("8002"),"numbering" to buildJsonObject{put("seriesId","tvdb:series:77");put("ordering","dvd")})
        val changed=JsonObject(root+("items" to JsonArray(listOf(JsonObject(row+("nextEpisode" to JsonObject(episode)))))))
        val next=(decodeCachedWatchingPage(changed.toString(),WatchingFixture.clock()).entries.single() as BrowseEntry.CachedWatchingEntry).item.nextEpisode!!
        assertEquals(0,next.seasonNumber);assertEquals(123,next.absoluteEpisodeNumber);assertEquals("dvd",next.numbering!!.ordering);assertEquals("tvdb:series:77",next.numbering.seriesId);assertEquals("tmdb:tv:800:s01e02",next.itemId)
    }
    @Test fun `missing marker pool schema rows and malformed scalar reject whole page`() {
        val root=WatchingFixture.root();for(key in listOf("source","items","total","pool","originalLanguageFilterSupported"))reject(JsonObject(root-key).toString())
        for((key,v) in mapOf("source" to JsonPrimitive("continue-watching"),"items" to JsonNull,"total" to JsonPrimitive("700"),"originalLanguageFilterSupported" to JsonPrimitive("true"),"total" to JsonPrimitive(4097)))reject(JsonObject(root+(key to v)).toString())
        val row=root.getValue("items").jsonArray.first().jsonObject
        for(key in listOf("seriesId","seriesTitle","mediaType","updatedAt"))reject(JsonObject(root+("items" to JsonArray(listOf(row,JsonObject(row-key))))).toString())
        reject(JsonObject(root+("items" to JsonArray(listOf(row,row)))).toString())
        for((key,v) in mapOf("mediaType" to JsonPrimitive("series"),"percentWatched" to JsonPrimitive(101),"resumePercent" to JsonPrimitive("10"),"posterUrl" to JsonPrimitive("private")))reject(JsonObject(root+("items" to JsonArray(listOf(JsonObject(row+(key to v)))))).toString())
    }
    @Test fun `pool expiry future bad calendar and incomplete observation fail without client wallclock`() {
        val root=WatchingFixture.root();val pool=root.getValue("pool").jsonObject
        for((key,v) in mapOf("id" to JsonPrimitive("bad"),"complete" to JsonPrimitive(false),"observedAt" to JsonPrimitive("2026-02-30T00:00:00Z"),"expiresAt" to JsonPrimitive("2027-11-16T00:00:00Z")))reject(JsonObject(root+("pool" to JsonObject(pool+(key to v)))).toString())
        assertTrue(runCatching{decodeCachedWatchingPage(root.toString(),WatchingFixture.clock(WatchingFixture.date+700_000))}.isFailure)
        assertTrue(runCatching{decodeCachedWatchingPage(root.toString(),WatchingFixture.clock(WatchingFixture.date-60_000))}.isFailure)
        assertEquals(700,decodeCachedWatchingPage(root.toString(),WatchingFixture.clock()).total)
    }
    @Test fun `request query is explicit bounded canonical and unsupported options fail before read`() {
        val list=WatchingFixture.list();val q=cachedWatchingRequest("profile/a",list,0,0,BrowseOptions(originalLanguages=setOf("fr","nob"),includeUnknownOriginalLanguage=true)).query
        assertEquals("fra,nob",q["originalLanguages"]);assertEquals("true",q["includeUnknownOriginalLanguage"]);assertEquals("continue-watching-cached",q["source"])
        assertFalse(q.containsKey("includeUnwatchedCounts"));assertFalse(q.containsKey("includeFacets"))
        for(options in listOf(BrowseOptions(watch=WatchFilter.WATCHED),BrowseOptions(genres=setOf("Drama")),BrowseOptions(sort=BrowseSort.ADDED),BrowseOptions(sort=BrowseSort.RATING),BrowseOptions(ratingSource=RatingSource.TMDB)))assertTrue(runCatching{cachedWatchingRequest("p",list,2,0,options)}.isFailure)
        assertTrue(runCatching{cachedWatchingRequest("p",list,4097,0,BrowseOptions())}.isFailure)
        assertTrue(runCatching{cachedWatchingRequest("p",list,2,0,BrowseOptions(),mapOf("homeView" to "movies"))}.isFailure)
    }
    @Test fun `entry factory rejects retired original producer page or epoch before navigation acceptance`() {
        val expected=ProfileDataContext.current;val producer=Job();assertNotNull(BrowseList.ContinueWatchingCached.open(expected,producer){true})
        assertNull(BrowseList.ContinueWatchingCached.open(expected,producer){false});producer.cancel();assertNull(BrowseList.ContinueWatchingCached.open(expected,producer){true})
        ProfileDataContext.invalidate();assertNull(BrowseList.ContinueWatchingCached.open(expected,Job()){true})
    }
    @Test fun `adoption pause cannot move deadline beyond original actual response receipt anchor`() {
        val received=System.nanoTime();val started=received-1_000_000L
        val at=(WatchingFixture.date/1000L)*1000L
        val date=java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'",java.util.Locale.US).format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC))
        val clock=NetworkResponseObservation(WatchingFixture.url,listOf(date),started,received).clock(WatchingFixture.url)
        val expires=at+1502L // Date + 1s precision + 2ms measured upper RTT + 500ms.
        val expected=received+500_000_000L
        assertEquals(expected,clock.deadlineNanos(expires))
        val row=WatchingFixture.page("late-selected").watching!!
        val observation=row.copy(expiresAt=java.time.Instant.ofEpochMilli(expires).toString(),clock=clock)
        Thread.sleep(25)
        val list=WatchingFixture.list();assertEquals(expected,list.accept(observation))
        Thread.sleep(25);assertEquals(expected,list.accept(observation));assertEquals(expected,clock.deadlineNanos(expires))
    }

}
