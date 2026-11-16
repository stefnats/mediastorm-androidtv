package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
internal data class TrailerStatus(val id:String,val status:String,val error:String?=null)

/** Existing trailer preparation retains its original profile through every poll and URL lookup. */
suspend fun ApiClient.trailerUrl(trailer:Trailer,expectedContext:Long=ProfileDataContext.current):String? {
    val profile=inContext(expectedContext){profileId}
    return resolveTrailerUrl(trailer,expectedContext,profile,
        post={body,query->send("POST","/api/metadata/trailers/prequeue",query,body=body,promptOnLock=false,timeoutMs=15_000,expectedContext=expectedContext)},
        poll={query->send("GET","/api/metadata/trailers/prequeue/status",query,promptOnLock=false,timeoutMs=10_000,expectedContext=expectedContext)},
        serve={id->inContext(expectedContext) {
            if(profileId!=profile)throw CancellationException("Trailer context changed")
            val query=buildMap {put("id",id);put("profileId",profile);token?.let {put("token",it)}}
            url("/api/metadata/trailers/prequeue/serve",query).toString()
        }})
}

/** Actual request/poll orchestration; injected transports let tests delay real operation boundaries. */
internal suspend fun resolveTrailerUrl(
    trailer:Trailer,expected:Long,profile:String,
    post:suspend (JsonObject,Map<String,String>)->TrailerStatus,
    poll:suspend (Map<String,String>)->TrailerStatus,
    serve:(String)->String,pause:suspend ()->Unit={delay(1_000)},
):String? {
    fun check() {ProfileDataContext.requireCurrent(expected)}
    suspend fun active() {currentCoroutineContext().ensureActive();check()}
    active()
    val source=trailer.url.toHttpUrlOrNull() ?: return null
    if(source.host.lowercase() !in youTubeHosts) {active();return trailer.url}
    return try {
        active()
        val queued=post(buildJsonObject {put("url",trailer.url)},mapOf("profileId" to profile))
        active()
        repeat(120) {
            active()
            val status=poll(mapOf("id" to queued.id,"profileId" to profile))
            active()
            when(status.status) {
                "ready"->{check();return serve(queued.id)}
                "failed"->return null
                else->{pause();active()}
            }
        }
        null
    } catch(error:CancellationException) {throw error
    } catch(_:Exception) {null}
}

private val youTubeHosts=setOf("youtube.com","www.youtube.com","m.youtube.com","youtu.be","www.youtu.be")
