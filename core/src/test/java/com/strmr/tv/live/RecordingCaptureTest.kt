package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.library.ProfileDataContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecordingCaptureTest {
    private val at="2026-11-16T01:00:00Z"
    private val end="2026-11-16T01:02:00Z"
    private fun part(index:Int=1,closed:Boolean=true,bytes:Long?=1024)=RecordingPart(index,at,if(closed)end else null,bytes,if(closed)"source_ended" else null,"recording:r1/parts/$index")
    private fun recording(parts:List<RecordingPart> = listOf(part()),phase:String="interrupted",status:String="failed",incomplete:Boolean=true)=
        Recording("r1",status,outputPath="part-1.ts",capture=RecordingCapture(1,phase,incomplete,0,parts,emptyList()))
    private fun decode(capture:String)=ApiJson.decodeFromString<Recording>("""{"id":"r1","status":"failed","title":"Synthetic recording","outputPath":"part-1.ts","capture":$capture}""")
    private fun encoded(r:Recording)=ApiJson.encodeToString(Recording.serializer(),r)

    @Test fun `legacy absent capture retains recording and first-file compatibility`() {
        val legacy=ApiJson.decodeFromString<Recording>("""{"id":"r1","status":"completed","outputPath":"legacy.ts"}""")
        assertNull(legacy.capture);assertTrue(RecordingRules.canPlay(legacy));assertEquals("Play Recording",RecordingCaptureRules.playLabel(legacy))
    }
    @Test fun `malformed optional capture never removes its parent recording`() {
        for(raw in listOf("[]","true","1","\"bad\"","{}","""{"version":2}""","""{"version":"1"}""")) {
            val r=decode(raw);assertEquals("Synthetic recording",r.title);assertEquals("part-1.ts",r.outputPath);assertEquals(0,r.capture!!.version);assertNotNull(RecordingCaptureRules.summary(r))
        }
    }
    @Test fun `capture parser roundtrips distinct closed parts without merging bytes or timeline`() {
        val r=recording(listOf(part(2),part(1)));val copy=ApiJson.decodeFromString<Recording>(encoded(r))
        assertEquals(listOf(1,2),copy.capture!!.parts.map {it.index});assertEquals(2048L,RecordingCaptureRules.totalBytes(copy));assertTrue(RecordingCaptureRules.incomplete(copy));assertEquals("Incomplete recording",RecordingCaptureRules.status(copy))
        assertNotEquals(copy.capture.parts[0].streamPath,copy.capture.parts[1].streamPath)
    }
    @Test fun `capture required fields and bounds remain conservative`() {
        val base=ApiJson.parseToJsonElement(encoded(recording())).jsonObject["capture"]!!.jsonObject
        for(key in listOf("phase","incomplete","sourceChanges","parts","gaps")) {
            assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base-key)).version)
            assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base+(key to JsonNull))).version)
        }
        assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base+("phase" to JsonPrimitive("future_phase")))).version)
        assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base+("sourceChanges" to JsonPrimitive(-1)))).version)
        val many=JsonArray(List(257){base["parts"]!!.jsonArray.first()});assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base+("parts" to many))).version)
    }
    @Test fun `invalid duplicate indices bytes and capture timing do not become playable facts`() {
        val base=ApiJson.parseToJsonElement(encoded(recording())).jsonObject["capture"]!!.jsonObject
        val first=base["parts"]!!.jsonArray.single().jsonObject
        for((key,value) in listOf("index" to JsonPrimitive(0),"index" to JsonPrimitive(257),"bytes" to JsonPrimitive(-1),"bytes" to JsonPrimitive("1024"),"endedAt" to JsonPrimitive("2026-11-15T00:00:00Z"),"startedAt" to JsonPrimitive("not a date"))) {
            val changed=JsonArray(listOf(JsonObject(first+(key to value))));assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base+("parts" to changed))).version)
        }
        assertEquals(0,RecordingCaptureSerializer.parse(JsonObject(base+("parts" to JsonArray(listOf(first,first))))).version)
    }
    @Test fun `only exact owned canonical part path can enable a closed part`() {
        val r=recording();assertTrue(RecordingCaptureRules.playable(r,part()))
        for(path in listOf("recording:other/parts/1","recording:r1/parts/01","recording:r1/parts/2","recording:r1/parts/1/extra","https://example.invalid/file","/private/capture.ts")) {
            val p=part().copy(streamPath=path);assertFalse(RecordingCaptureRules.playable(recording(listOf(p)),p))
        }
        val alien=part().copy(startedAt="2026-11-16T01:01:00Z");assertFalse(RecordingCaptureRules.playable(r,alien))
    }
    @Test fun `active zero missing or unconfirmed parts never offer explicit playback`() {
        for(p in listOf(part(closed=false),part(bytes=0),part(bytes=null),part().copy(streamPath=null),part().copy(endReason="unknown"),part().copy(endedAt=null))) {
            assertFalse(RecordingCaptureRules.playable(recording(listOf(p)),p))
        }
    }
    @Test fun `one closed part waiting for capacity is not presented as growing continuous playback`() {
        val r=recording(phase="waiting_capacity",status="running")
        assertEquals("Waiting for recording capacity",RecordingCaptureRules.status(r));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(r));assertEquals(part(),RecordingCaptureRules.firstClosed(r));assertTrue(RecordingRules.canPlay(r))
    }
    @Test fun `known active first-part legacy playback requires actual measured data`() {
        val active=recording(listOf(part(closed=false)),"capturing","running",false)
        assertTrue(RecordingRules.canPlay(active));assertNull(RecordingCaptureRules.firstClosed(active));assertEquals("Play In Progress",RecordingCaptureRules.playLabel(active))
        assertFalse(RecordingRules.canPlay(active.copy(capture=active.capture!!.copy(parts=listOf(part(closed=false,bytes=0))))))
        assertFalse(RecordingRules.canPlay(active.copy(capture=active.capture.copy(parts=emptyList()))))
    }
    @Test fun `multipart or recorded gaps cannot falsely claim completion even with contradictory status flag`() {
        val multi=recording(listOf(part(),part(2)),"finished","completed",false)
        assertTrue(RecordingCaptureRules.incomplete(multi));assertFalse(RecordingFilter.COMPLETED.matches(multi));assertTrue(RecordingFilter.HISTORY.matches(multi));assertEquals("Incomplete recording",RecordingCaptureRules.status(multi));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(multi))
        val gap=RecordingGap(at,end,"capacity");assertTrue(RecordingCaptureRules.incomplete(recording(incomplete=false).copy(capture=recording(incomplete=false).capture!!.copy(gaps=listOf(gap)))))
    }
    @Test fun `unknown sizes and arithmetic overflow stay unknown instead of inventing a captured total`() {
        assertNull(RecordingCaptureRules.totalBytes(recording(listOf(part(bytes=null)))))
        assertNull(RecordingCaptureRules.totalBytes(recording(listOf(part(bytes=Long.MAX_VALUE),part(2,bytes=1)))))
        assertNull(RecordingCaptureRules.totalBytes(recording(emptyList())))
    }
    @Test fun `part end labels never expose arbitrary source errors or source identifiers`() {
        assertEquals("Capture interrupted",RecordingCaptureRules.endLabel(part().copy(endReason="capture_failed")))
        assertEquals("Part end is unconfirmed; playback is unavailable.",RecordingCaptureRules.endLabel(part().copy(endReason="https://example.invalid/private")))
    }
    @Test fun `actual part transport contains one explicit canonical selector and legacy route has none`() {
        val r=recording();val route=recordingStreamRoute(r,"Synthetic part","adult",part=part())
        assertTrue(route.startsWith("/live/recordings/r1/stream?"));assertTrue(route.contains("part=1"));assertTrue(route.contains("profileId=adult"));assertFalse(route.contains("outputPath"));assertFalse(recordingStreamRoute(r,"Synthetic","adult").contains("part="))
        try {recordingStreamRoute(r,"Synthetic","adult",part=part().copy(streamPath="recording:other/parts/1"));fail("Borrowed path accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun `stale profile APIs reject before reading replacement profile or starting hls`() = runTest {
        val old=ProfileDataContext.current;ProfileDataContext.invalidate()
        suspend fun reject(call:suspend ()->Unit) {try {call();fail("Stale call accepted")}catch(_:CancellationException){}}
        reject {ApiClient.recordings(old)};reject {ApiClient.recordingRules(old)};reject {ApiClient.cancelRecording("r1",old)};reject {ApiClient.deleteRecording("r1",old)};reject {ApiClient.deleteRecordingRule("rule1",old)};reject {ApiClient.startRecordingHls(recording(),"Synthetic",old)}
        reject {ApiClient.recordingStreamUrl(recording(),"Synthetic",old,part())}
    }
    @Test fun `native contract exports are emitted from production recording and part request helpers`() {
        val path=System.getProperty("recordingPartsContractsDir") ?: return
        val dir=File(path).apply {mkdirs()};val r=recording(listOf(part(),part(2)))
        File(dir,"native-capture-recording.json").writeText(encoded(r))
        File(dir,"native-part-request.json").writeText(buildJsonObject {put("method","GET");put("path",recordingStreamRoute(r,"Synthetic recording · Part 2","adult",part=part(2)))}.toString())
        File(dir,"native-first-file-request.json").writeText(buildJsonObject {put("method","GET");put("path",recordingStreamRoute(r,"Synthetic recording","adult"))}.toString())
    }

    @Test fun `shared TV and phone labels separate scheduled window and provisional captured data`() {
        val r=recording(listOf(part(closed=false)),"capturing","running",false).copy(startAt=at,endAt=end)
        assertEquals("Scheduled: 2m",RecordingCaptureRules.scheduledDuration(r));assertEquals("Captured data so far",RecordingCaptureRules.dataLabel(r))
        assertEquals("Captured data so far",RecordingCaptureRules.partDataLabel(part(closed=false)))
        assertEquals("Not measured yet",RecordingCaptureRules.partDataSize(part(closed=false,bytes=0)))
        assertEquals("0 bytes",RecordingCaptureRules.partDataSize(part(bytes=0)))
        assertEquals("Captured data",RecordingCaptureRules.partDataLabel(part()))
        assertEquals("2m",RecordingCaptureRules.scheduledDuration(r.copy(capture=null)))
    }

    @Test fun `historical unclosed part never promises that stopped capture will resume`() {
        val p=part(closed=false);val restored=recording(listOf(p),"interrupted","failed")
        assertEquals("Part end is unconfirmed; playback is unavailable.",RecordingCaptureRules.endLabel(p,restored))
        assertEquals("Last measured data",RecordingCaptureRules.partDataLabel(p,restored));assertFalse(RecordingCaptureRules.playable(restored,p))
        assertEquals("Not measured",RecordingCaptureRules.partDataSize(p.copy(bytes=null),restored))
        val current=restored.copy(status="running",capture=restored.capture!!.copy(phase="capturing"))
        assertEquals("Still capturing; play after this part closes",RecordingCaptureRules.endLabel(p,current));assertEquals("Captured data so far",RecordingCaptureRules.partDataLabel(p,current))
    }

    @Test fun `default action selects lowest nonempty closed owned part after empty first attempt`() {
        val empty=part(bytes=0).copy(streamPath=null);val saved=part(2,bytes=752);val r=recording(listOf(empty,saved))
        assertEquals(saved,RecordingCaptureRules.firstClosed(r));assertTrue(RecordingRules.canPlay(r));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(r))
        val active=part(closed=false);val withEarlierActive=recording(listOf(active,saved),"capturing","running")
        assertNull(RecordingCaptureRules.firstClosed(withEarlierActive));assertFalse(RecordingRules.canPlay(withEarlierActive))
    }
    @Test fun `unconfirmed single active part cannot invoke growing playback outside capturing phase`() {
        for(phase in listOf("waiting_capacity","interrupted","finished")) {
            val r=recording(listOf(part(closed=false)),phase,"running")
            assertFalse(RecordingRules.canPlay(r));assertFalse(RecordingCaptureRules.endLabel(r.capture!!.parts.single(),r).contains("Still capturing"))
        }
    }
}
