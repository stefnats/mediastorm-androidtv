package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.live.Recording
import com.strmr.tv.live.RecordingCaptureRules
import com.strmr.tv.live.RecordingPart
import com.strmr.tv.live.RecordingRules

/** Explicit parts have independent starts and never participate in catalogue history or Up Next. */
internal fun recordingPartSession(recording:Recording,part:RecordingPart,url:String):PlayerSession {
    require(RecordingCaptureRules.playable(recording,part)) {"Recording part is unavailable"}
    val title="${RecordingRules.title(recording)} · Part ${part.index}"
    val request=PlaybackRequest(DetailsTarget("recording:${recording.id}/parts/${part.index}",title,isMovie=true))
    return PlayerSession(request,url,null,reportsProgress=false,sourceLabel=StreamInfo.recording(recording.channelName))
}
