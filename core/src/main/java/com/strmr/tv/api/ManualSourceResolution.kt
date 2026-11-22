package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The original profile/client remains fixed across the asynchronous resolve queue. */
internal suspend fun resolveManualSource(
    result:SourceResult,titleId:String,startOffset:Double?,profile:String,client:String,expected:Long,
    post:suspend (JsonObject)->PlaybackResolution,
    poll:suspend (Long,Map<String,String>)->PlaybackResolution,
    pause:suspend ()->Unit={delay(1_000)},now:()->Long=System::currentTimeMillis,
):Pair<String,TrackSelection?> {
    val resolved = resolveManualSourceWithQuality(result, titleId, startOffset, profile, client, expected, post, poll, pause, now)
    return resolved.path to resolved.tracks
}

internal suspend fun resolveManualSourceWithQuality(
    result:SourceResult,titleId:String,startOffset:Double?,profile:String,client:String,expected:Long,
    post:suspend (JsonObject)->PlaybackResolution,
    poll:suspend (Long,Map<String,String>)->PlaybackResolution,
    pause:suspend ()->Unit={delay(1_000)},now:()->Long=System::currentTimeMillis,
):ResolvedManualSource {
    suspend fun check() {currentCoroutineContext().ensureActive();ProfileDataContext.requireCurrent(expected)}
    check()
    val body=buildJsonObject {
        put("result",result.raw);put("profileId",profile);put("titleId",titleId);put("clientId",client)
        put("includeTrackSelection",true);startOffset?.let {put("startOffset",it)}
    }
    var resolution=post(body);check()
    val deadline=now()+180_000
    val query=mapOf("includeTrackSelection" to "true","profileId" to profile,"titleId" to titleId,"clientId" to client)
    while(resolution.webdavPath.isNullOrEmpty()) {
        val id=resolution.queueId
        if(id==null || id<=0 || now()>deadline)throw ApiError.NotReady("Source could not be resolved")
        pause();check()
        resolution=poll(id,query);check()
    }
    return ResolvedManualSource(requireNotNull(resolution.webdavPath), resolution.trackSelection, resolution.qualityAttributes.orEmpty())
}
