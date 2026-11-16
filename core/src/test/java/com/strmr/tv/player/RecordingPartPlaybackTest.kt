package com.strmr.tv.player

import com.strmr.tv.live.*
import org.junit.Assert.*
import org.junit.Test

class RecordingPartPlaybackTest {
    private fun part(i:Int)=RecordingPart(i,"2026-11-16T01:00:00Z","2026-11-16T01:02:00Z",4096,"capture_failed","recording:r1/parts/$i")
    private val r=Recording("r1","running",title="Synthetic show",capture=RecordingCapture(1,"waiting_capacity",true,1,listOf(part(1),part(2)),emptyList()))
    @Test fun `each explicit part starts independently without resume history report or next-part automation`() {
        val first=recordingPartSession(r,part(1),"https://example.invalid/never-open/part1")
        val second=recordingPartSession(r,part(2),"https://example.invalid/never-open/part2")
        assertNotEquals(first.request.title.titleId,second.request.title.titleId)
        for(s in listOf(first,second)) {
            assertNull(s.request.start);assertNull(s.request.startPosition);assertFalse(s.reportsProgress);assertNull(s.progressSink);assertNull(s.growingRestart);assertNull(s.retuneLive);assertNull(s.prequeueId);assertTrue(s.request.following.isEmpty());assertNull(s.streamPath);assertFalse(s.isLive)
        }
        assertTrue(second.request.title.name.endsWith("Part 2"))
    }
    @Test fun `a part remains direct when overall job waits or runs`() {
        val session=recordingPartSession(r,part(1),"https://example.invalid/never-open/part1")
        assertEquals("https://example.invalid/never-open/part1",session.url);assertNull(session.growingRestart);assertNull(session.liveSessionId)
    }
    @Test fun `active or different recorded part cannot reuse closed-first-part playback intent`() {
        for(p in listOf(part(1).copy(endedAt=null),part(1).copy(streamPath="recording:other/parts/1"),part(3))) {
            try {recordingPartSession(r,p,"https://example.invalid/never-open");fail("Invalid part accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
