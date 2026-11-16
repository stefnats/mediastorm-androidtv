package com.strmr.mobile.downloads

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.Artwork
import com.strmr.tv.api.mediaHeaders
import com.strmr.tv.api.streamUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Immutable headers belong to the captured account; redirects cannot borrow replacement credentials. */
internal class OwnedDownloadHttp(
    val request:Request,private val backend:HttpUrl,private val headers:Map<String,String>,
    private val verify:()->Unit,
) {
    private val backendStart=request.url.scheme==backend.scheme && request.url.host==backend.host && request.url.port==backend.port
    fun client(base:OkHttpClient):OkHttpClient=base.newBuilder().addNetworkInterceptor {chain->
        verify()
        val original=chain.request();val builder=original.newBuilder()
        if(original.url.scheme!=backend.scheme || original.url.host!=backend.host || original.url.port!=backend.port) {
            for((name,value) in if(backendStart)headers else emptyMap()) {
                val values=original.headers.values(name);val retained=values.filterNot {it==value}
                if(retained.size!=values.size){builder.removeHeader(name);retained.forEach {builder.addHeader(name,it)}}
            }
            val unlock=if(backendStart)request.url.queryParameter("profileUnlock")else null
            if(unlock!=null && original.url.queryParameter("profileUnlock")==unlock)builder.url(original.url.newBuilder().removeAllQueryParameters("profileUnlock").build())
        }
        chain.proceed(builder.build())
    }.build()
    companion object {
        fun stream(item:DownloadItem,context:OfflineContext,range:String?):OwnedDownloadHttp=OfflineAccess.use(context,item) {
            val headers=ApiClient.mediaHeaders
            val request=Request.Builder().url(ApiClient.streamUrl(item.streamPath)).apply {headers.forEach { (k,v)->header(k,v)};range?.let {header("Range",it)}}.build()
            OwnedDownloadHttp(request,ApiClient.baseUrl,headers){OfflineAccess.require(context,item)}
        }
        fun poster(item:DownloadItem,context:OfflineContext):OwnedDownloadHttp?=OfflineAccess.use(context,item) {
            val url=Artwork.url(item.posterUrl,"w342") ?: return@use null
            val base=ApiClient.baseUrl;val headers=ApiClient.mediaHeaders
            val request=Request.Builder().url(url).apply {
                val parsed=url.toHttpUrlOrNull()
                if(parsed!=null && parsed.scheme==base.scheme && parsed.host==base.host && parsed.port==base.port && parsed.encodedPath.startsWith(base.encodedPath.trimEnd('/')+"/api/"))headers.forEach {(k,v)->header(k,v)}
            }.build()
            OwnedDownloadHttp(request,base,headers){OfflineAccess.require(context,item)}
        }
    }
}
