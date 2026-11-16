package com.strmr.tv.api

import com.strmr.tv.player.PlaybackSessionContext
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** One player's immutable image transport; its metadata is not a content authorization. */
class ThumbnailImageAccess internal constructor(
    private val base:HttpUrl,private val headers:Map<String,String>,val ownership:PlaybackSessionContext,
) {
    data class Image(val url:String,val cacheKey:String)
    private val prefix=base.encodedPath.trimEnd('/')
    private val imagePath="$prefix/api/video/thumbnails/image/"
    private val cacheScope=UUID.randomUUID().toString()
    @Volatile private var retired=false
    fun isCurrent()=!retired && ownership.isCurrent()
    fun retire(){retired=true}
    private val secrets=buildSet {
        headers.forEach {(name,value)->
            if(name.equals("Authorization",true)) {add(value);value.takeIf {it.startsWith("Bearer ",true)}?.substringAfter(' ')?.let(::add)}
            if(name.equals("X-Profile-Unlock",true) || name.equals("X-Remote-Access-Credential",true))add(value)
        }
    }.filter {it.isNotEmpty()}.toSet()
    private fun sameOrigin(url:HttpUrl)=url.scheme==base.scheme && url.host==base.host && url.port==base.port
    private fun ownImage(url:HttpUrl):Boolean {
        if(!sameOrigin(url) || !url.encodedPath.startsWith(imagePath))return false
        val tail=url.encodedPath.removePrefix(imagePath).split('/')
        return tail.size==2 && tail.all {it.isNotEmpty()} && url.pathSegments.takeLast(2).all {part->
            part !in setOf(".","..") && part.none {it=='/' || it=='\\' || it.isISOControl()}
        }
    }
    private fun ownerSelector(name:String)=name.equals("profileId",true) || name.equals("userId",true)
    private fun validate(url:HttpUrl):HttpUrl? {
        if(!ownImage(url)) {
            // Do not rewrite unknown provider parameters or send an embedded backend secret.
            if(url.queryParameterNames.any {name->url.queryParameterValues(name).any {it in secrets}})return null
            return url
        }
        val names=url.queryParameterNames.filter(::ownerSelector)
        if(names.any {name->url.queryParameterValues(name).any {it!=ownership.profileId}})return null
        return if(names.isEmpty())url.newBuilder().addQueryParameter("profileId",ownership.profileId).build()else url
    }
    fun image(raw:String):Image? {
        if(!isCurrent() || raw.isBlank() || raw.any(Char::isISOControl))return null
        val directory=base.newBuilder().encodedPath(prefix+"/").build()
        val absolute=raw.toHttpUrlOrNull()
        val resolved=absolute ?: directory.resolve(raw)?.let {url->
            if(raw.startsWith("/api/"))url.newBuilder().encodedPath(prefix+url.encodedPath).build()else url
        } ?: return null
        val url=validate(resolved) ?: return null
        val key=MessageDigest.getInstance("SHA-256").digest((cacheScope+":"+ownership.epoch+":"+ownership.profileId+":"+url).toByteArray())
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        return Image(url.toString(),"thumbnail-v1:$key")
    }
    fun requireCurrent() {ownership.requireCurrent();if(retired)throw kotlinx.coroutines.CancellationException("Thumbnail request retired")}
    /** Used before and after Coil's memory/disk/HTTP pipeline, not only its network fetcher. */
    suspend fun<T> read(fetch:suspend ()->T):T {
        requireCurrent();val result=fetch();requireCurrent();return result
    }
    private fun networkCurrent() {if(!isCurrent())throw IOException("Thumbnail context changed")}
    private val network=Interceptor {chain->
        networkCurrent()
        val original=chain.request()
        val url=validate(original.url) ?: throw IOException("Thumbnail image is unavailable")
        val request=original.newBuilder().url(url)
        if(ownImage(url))headers.forEach {(name,value)->request.header(name,value)}
        else headers.forEach {(name,value)->
            val values=original.headers.values(name);val kept=values.filterNot {it==value}
            if(kept.size!=values.size){request.removeHeader(name);kept.forEach {request.addHeader(name,it)}}
        }
        val response=chain.proceed(request.build())
        try {networkCurrent()}catch(error:IOException){response.close();throw error}
        response
    }
    fun client(baseClient:OkHttpClient):OkHttpClient=baseClient.newBuilder().addNetworkInterceptor(network).build()
    companion object {
        fun capture(ownership:PlaybackSessionContext)=ownership.use {ThumbnailImageAccess(ApiClient.baseUrl,ApiClient.mediaHeaders.toMap(),ownership)}
    }
}
