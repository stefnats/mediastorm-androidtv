package com.strmr.tv.player

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import com.strmr.tv.api.ThumbnailImageAccess
import okhttp3.OkHttpClient

/** A dedicated loader never invokes the global artwork interceptor or reads a replacement profile. */
class ThumbnailImages(context:Context,private val access:ThumbnailImageAccess) {
    private val application=context.applicationContext
    private var instance:ImageLoader?=null
    val loader:ImageLoader get() {access.requireCurrent();return instance ?: ImageLoader.Builder(application)
        .okHttpClient {access.client(OkHttpClient.Builder().build())}
        .components {add(object:coil.intercept.Interceptor {override suspend fun intercept(chain:coil.intercept.Interceptor.Chain):coil.request.ImageResult=access.read {chain.proceed(chain.request)}})}
        .build().also {instance=it}}
    fun request(raw:String):ImageRequest? {
        val image=access.image(raw) ?: return null
        return ImageRequest.Builder(application).data(image.url).memoryCacheKey(image.cacheKey).diskCacheKey(image.cacheKey).build()
    }
    fun close(){access.retire();instance?.shutdown();instance=null}
}
