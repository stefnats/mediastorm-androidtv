package com.strmr.tv.live

import com.strmr.tv.api.ApiJson
import com.strmr.tv.player.recordingPartSession
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Byte-exact actual owned Go handler replies, portable without a provider or optional environment. */
@RunWith(Parameterized::class)
class GoRecordingCaptureContractTest(private val filename:String,private val kind:String) {
    companion object {
        private fun resource(name:String)=requireNotNull(GoRecordingCaptureContractTest::class.java.getResourceAsStream("/recording-capture-go/$name")){"Missing actual Go recording fixture"}.use {it.readBytes()}
        @JvmStatic @Parameterized.Parameters(name="{0}: {1}") fun fixtures():List<Array<String>> {
            val manifest=ApiJson.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
            assertEquals(1,manifest["version"]!!.jsonPrimitive.int)
            val files=manifest["files"]!!.jsonObject;assertEquals(10,files.size)
            val hashes=ApiJson.parseToJsonElement(resource("sha256.json").decodeToString()).jsonObject
            assertEquals(files.keys,hashes.keys)
            for((name,digest) in hashes)assertEquals("Actual Go response changed: $name",digest.jsonPrimitive.content,
                MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString(""){"%02x".format(it.toInt() and 255)})
            return files.entries.sortedBy {it.key}.map {arrayOf(it.key,it.value.jsonPrimitive.content)}
        }
    }
    @Test fun `real public recording bytes preserve capture privacy exact parts and honest playback state`() {
        val bytes=resource(filename).decodeToString()
        assertFalse(bytes.contains("SYNTHETIC_SOURCE_TOKEN"));assertFalse(bytes.contains("/synthetic-private/"));assertFalse(bytes.contains("PRIVATE_STATION_CONTEXT"));assertFalse(bytes.contains("SYNTHETIC_DIAGNOSTIC_TOKEN"))
        val obj=ApiJson.parseToJsonElement(bytes).jsonObject
        val rows=if(kind=="RecordingList")obj["recordings"]!!.jsonArray.map {ApiJson.decodeFromJsonElement<Recording>(it)} else listOf(ApiJson.decodeFromString<Recording>(bytes))
        assertEquals(if(kind=="RecordingList")7 else 1,rows.size)
        for(r in rows) {
            if(r.id=="restored")assertNull(r.outputPath)else assertEquals("part-1.ts",r.outputPath);assertTrue(r.title.isNotEmpty())
            when(r.id) {
                "multipart"->{
                    assertEquals(1,r.capture!!.version);assertEquals(2,r.capture.parts.size);assertEquals(1,r.capture.sourceChanges)
                    assertEquals(listOf(752L,1128L),r.capture.parts.map {it.bytes});assertEquals(1880L,RecordingCaptureRules.totalBytes(r));assertEquals("Incomplete recording",RecordingCaptureRules.status(r));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(r));assertEquals(1,r.capture.gaps.size)
                    assertTrue(r.capture.parts.all {RecordingCaptureRules.playable(r,it)})
                }
                "waiting"->{
                    assertEquals("waiting_capacity",r.capture!!.phase);assertEquals("Waiting for recording capacity",RecordingCaptureRules.status(r));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(r));assertTrue(RecordingRules.canCancel(r));assertTrue(RecordingCaptureRules.playable(r,r.capture.parts.single()));assertNull(r.capture.gaps.single().endedAt)
                }
                "capturing"->{
                    val c=r.capture!!;assertEquals("capturing",c.phase);assertTrue(RecordingCaptureRules.playable(r,c.parts[0]));assertFalse(RecordingCaptureRules.playable(r,c.parts[1]));assertNull(c.parts[1].endedAt);assertEquals(188L,c.parts[1].bytes)
                    assertEquals("Captured data so far",RecordingCaptureRules.partDataLabel(c.parts[1],r));assertTrue(RecordingCaptureRules.endLabel(c.parts[1],r).startsWith("Still capturing"));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(r))
                }
                "restored"->{
                    assertEquals("failed",r.status);assertEquals("interrupted",r.capture!!.phase);assertTrue(r.capture.parts.all {it.streamPath==null});assertFalse(RecordingRules.canPlay(r));assertTrue(r.capture.parts.none {RecordingCaptureRules.playable(r,it)})
                    val unclosed=r.capture.parts.single {it.endedAt==null};assertFalse(RecordingCaptureRules.endLabel(unclosed,r).contains("Still capturing"));assertTrue(RecordingCaptureRules.endLabel(unclosed,r).contains("unavailable"));assertEquals("Last measured data",RecordingCaptureRules.partDataLabel(unclosed,r))
                }
                "first-empty"->{
                    val parts=r.capture!!.parts
                    assertEquals(0L,parts[0].bytes);assertNull(parts[0].streamPath);assertEquals(752L,parts[1].bytes)
                    assertEquals(2,RecordingCaptureRules.firstClosed(r)!!.index);assertTrue(RecordingRules.canPlay(r));assertEquals("Play / resume completed parts",RecordingCaptureRules.playLabel(r))
                }
                "pending"->{assertNull(r.capture);assertEquals("pending",r.status);assertFalse(RecordingRules.canPlay(r))}
                "legacy"->{assertNull(r.capture);assertEquals("completed",r.status);assertTrue(RecordingRules.canPlay(r));assertEquals("Play Recording",RecordingCaptureRules.playLabel(r))}
                else->fail("Unexpected synthetic recording")
            }
            for(part in r.capture?.parts.orEmpty().filter {RecordingCaptureRules.playable(r,it)}) {
                assertEquals("recording:${r.id}/parts/${part.index}",part.streamPath)
                val route=recordingStreamRoute(r,"Synthetic part","adult",part=part);assertTrue(route.startsWith("/live/recordings/${r.id}/stream?"));assertTrue(route.contains("part=${part.index}"));assertTrue(route.contains("profileId=adult"))
                val session=recordingPartSession(r,part,"https://example.invalid/never-open")
                assertFalse(session.reportsProgress);assertNull(session.request.start);assertNull(session.growingRestart);assertNull(session.prequeueId);assertTrue(session.request.following.isEmpty())
            }
        }
    }
}
