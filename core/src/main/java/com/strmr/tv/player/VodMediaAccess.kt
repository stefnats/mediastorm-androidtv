package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.strmr.tv.live.HlsUriPolicy
import com.strmr.tv.api.HttpFailure
import com.strmr.tv.api.VodAttempt
import com.strmr.tv.api.VodDenialEvents
import com.strmr.tv.api.ObservedVodDenial
import com.strmr.tv.api.VodAccessDenial
import com.strmr.tv.api.vodAccessDenial
import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** The final response, not an original DataSpec URL before redirects, establishes backend origin. */
internal class VodMediaAccess(private val backend:HttpUrl,private val enabled:Boolean,private val backendHeaders:Map<String,String> = emptyMap(),private val denialTitleId:String?=null,private val context:Long=0,private val requireCurrent:()->Unit={},private val profileId:String?=null) {
    private val marker="X-Mediastorm-Local-Vod-Denial"
    private val nonce=UUID.randomUUID().toString()
    private val apiPrefix=backend.encodedPath.trimEnd('/')+"/api/"
    private val prefix=apiPrefix+"video/"
    private fun sameOrigin(url:HttpUrl)=url.scheme==backend.scheme && url.host==backend.host && url.port==backend.port
    private fun owns(url:HttpUrl)=sameOrigin(url) &&
        (url.encodedPath==prefix+"stream" || url.encodedPath.startsWith(prefix+"hls/"))

    private fun current()=runCatching {requireCurrent();true}.getOrDefault(false)
    private fun checkNetworkContext() {if(!current())throw java.io.IOException("Playback context changed")}

    /** OkHttp follow-up requests do not re-enter application interceptors. */
    val requestInterceptor=Interceptor {chain->
        checkNetworkContext()
        val original = chain.request()
        val url = profileId?.let { HlsUriPolicy(backend, it).withProfile(original.url) } ?: original.url
        val request=original.newBuilder().url(url).removeHeader(marker)
        if(!sameOrigin(original.url) || !original.url.encodedPath.startsWith(apiPrefix)) {
            for((name,value) in backendHeaders) {
                val values=chain.request().headers.values(name)
                val retained=values.filterNot {it==value}
                if(retained.size!=values.size) {request.removeHeader(name);retained.forEach {request.addHeader(name,it)}}
            }
        }
        chain.proceed(request.build())
    }
    val interceptor=Interceptor {chain->
        checkNetworkContext()
        val attempt=denialTitleId?.let {VodDenialEvents.begin(it,context)}
        val response=chain.proceed(chain.request().newBuilder().removeHeader(marker).build())
        try {checkNetworkContext()}catch(error:java.io.IOException){response.close();throw error}
        // Remove every case variant, including forged upstream headers. This marker is never a request header.
        val clean=response.newBuilder()
        response.headers.names().filter {it.equals(marker,true)}.forEach(clean::removeHeader)
        if(owns(response.request.url)) {
            val code=if(response.code in setOf(400,401,403))readCode(response) else null
            val generatedHls=HlsUriPolicy(backend,profileId.orEmpty()).asset(response.request.url)
            // A live raw proxy can forward the provider's uncoded403. Explicit policy/PIN
            // codes and generated local HLS assets are authoritative; external CDNs are not.
            val reason=if(enabled || generatedHls)vodAccessDenial(response.code,code) else code?.let {vodAccessDenial(null,it)}
            reason?.let {clean.header(marker,"$nonce:${it.name}:${attempt?.sequence ?: 0}")}
        }
        clean.build()
    }
    private fun readCode(response:Response):String? = try {
        HttpFailure(response.code,response.peekBody(4096).string()).code
    } catch(_:Exception) {null}

    fun denial(error:Throwable):VodAccessDenial?=failure(error)?.reason
    fun failure(error:Throwable):ObservedVodDenial? {
        var current:Throwable?=error
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        while(current!=null && seen.add(current)) {
            if(current is HttpDataSource.InvalidResponseCodeException) {
                val value=current.headerFields.entries.firstOrNull {it.key.equals(marker,true)}?.value?.singleOrNull()
                if(value?.startsWith("$nonce:")==true) {
                    val fields=value.split(':');val reason=VodAccessDenial.entries.firstOrNull {it.name==fields.getOrNull(1)} ?: return null
                    val operation=fields.getOrNull(2)?.toLongOrNull() ?: return null
                    return ObservedVodDenial(VodAttempt(denialTitleId.orEmpty(),context,operation),reason)
                }
            }
            current=current.cause
        }
        return null
    }
    val loadPolicy=object:DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info:LoadErrorHandlingPolicy.LoadErrorInfo):Long =
            if(!current() || denial(info.exception)!=null)C.TIME_UNSET else super.getRetryDelayMsFor(info)
        override fun getFallbackSelectionFor(options:LoadErrorHandlingPolicy.FallbackOptions,info:LoadErrorHandlingPolicy.LoadErrorInfo):LoadErrorHandlingPolicy.FallbackSelection? =
            if(!current() || denial(info.exception)!=null)null else super.getFallbackSelectionFor(options,info)
    }
}
