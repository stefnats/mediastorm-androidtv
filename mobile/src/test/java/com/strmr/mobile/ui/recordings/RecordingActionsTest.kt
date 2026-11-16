package com.strmr.mobile.ui.recordings

import com.strmr.tv.live.Recording
import com.strmr.tv.live.RecordingFilter
import com.strmr.tv.live.SeriesRule
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingActionsTest {
    private fun rec(id: String, status: String, start: String, path: String? = null, rule: String? = null) =
        Recording(id = id, status = status, startAt = start, endAt = start, outputPath = path, ruleId = rule)

    @Test fun actionsPerStatus() {
        assertEquals(listOf("Play In Progress", "Stop Recording", "Delete Recording"),
                     RecordingActions.of(rec("a", "running", "2026-11-02T20:00:00Z", "/r/a.ts"), null).map { it.label })
        assertEquals(listOf("Cancel Scheduled Recording", "Cancel Series Recording"),
                     RecordingActions.of(rec("b", "pending", "2026-11-02T20:00:00Z", rule = "s"), SeriesRule(id = "s")).map { it.label })
        assertEquals(listOf("Play Recording", "Delete Recording"),
                     RecordingActions.of(rec("c", "completed", "2026-11-02T20:00:00Z", "/r/c.ts"), null).map { it.label })
    }

    @Test fun allGroupsByStatusInOrder() {
        val list = listOf(
            rec("done1", "completed", "2026-11-01T20:00:00Z"),
            rec("up2", "pending", "2026-11-05T20:00:00Z"),
            rec("up1", "pending", "2026-11-04T20:00:00Z"),
            rec("done2", "completed", "2026-11-02T20:00:00Z"),
            rec("live", "running", "2026-11-02T21:00:00Z"),
        )
        val sections = RecordingActions.sections(list, RecordingFilter.ALL)
        assertEquals(listOf(RecordingFilter.RECORDING, RecordingFilter.UPCOMING, RecordingFilter.COMPLETED), sections.map { it.first })
        assertEquals(listOf("up1", "up2"), sections[1].second.map { it.id })
        assertEquals(listOf("done2", "done1"), sections[2].second.map { it.id })
        assertEquals(listOf("up2", "up1"), RecordingActions.sections(list, RecordingFilter.UPCOMING).single().second.map { it.id })
    }

    @Test fun waitingAfterOneClosedPartOffersFirstPartRatherThanGrowingRecording() {
        val p=com.strmr.tv.live.RecordingPart(1,"2026-11-16T01:00:00Z","2026-11-16T01:02:00Z",1024,"source_ended","recording:r1/parts/1")
        val r=Recording("r1","running",outputPath="part1.ts",capture=com.strmr.tv.live.RecordingCapture(1,"waiting_capacity",true,0,listOf(p),emptyList()))
        assertEquals("Play / resume completed parts",RecordingActions.of(r,null).first().label)
        assertEquals("Stop Recording",RecordingActions.of(r,null)[1].label)
    }
    @Test fun activeEmptyPartHasNoPrematurePlayAction() {
        val p=com.strmr.tv.live.RecordingPart(1,"2026-11-16T01:00:00Z",bytes=0)
        val r=Recording("r1","running",outputPath="part1.ts",capture=com.strmr.tv.live.RecordingCapture(1,"capturing",false,0,listOf(p),emptyList()))
        assertEquals(listOf("Stop Recording","Delete Recording"),RecordingActions.of(r,null).map {it.label})
    }
}
