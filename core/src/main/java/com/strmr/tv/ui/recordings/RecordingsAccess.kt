package com.strmr.tv.ui.recordings

import com.strmr.tv.api.ApiClient
import com.strmr.tv.live.*

/** Existing profile-owned requests; no recording creation or capture is initiated by a refresh. */
interface RecordingsAccess {
    suspend fun recordings(context:Long):List<Recording>
    suspend fun rules(context:Long):List<SeriesRule>
    suspend fun plan(context:Long):RecordingPlan?
    suspend fun cancel(id:String,context:Long)
    suspend fun delete(id:String,context:Long)
    suspend fun deleteRule(id:String,context:Long)
}
object ApiRecordingsAccess:RecordingsAccess {
    override suspend fun recordings(context:Long)=ApiClient.recordings(context)
    override suspend fun rules(context:Long)=ApiClient.recordingRules(context)
    override suspend fun plan(context:Long)=ApiClient.recordingPlan(context)
    override suspend fun cancel(id:String,context:Long)=ApiClient.cancelRecording(id,context)
    override suspend fun delete(id:String,context:Long)=ApiClient.deleteRecording(id,context)
    override suspend fun deleteRule(id:String,context:Long)=ApiClient.deleteRecordingRule(id,context)
}
