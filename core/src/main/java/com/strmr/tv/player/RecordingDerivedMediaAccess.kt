package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.strmr.tv.live.recordingDerivedStreamUrl
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Exact immutable raw endpoint only. A cache miss never selects another recording source. */
internal class RecordingDerivedMediaAccess(
    base: HttpUrl, private val source: RecordingDerivedPlayback, private val headers: Map<String, String>,
) {
    private val expected = recordingDerivedStreamUrl(base, source.owner.profileId,
        source.observation.descriptor.recordingId, requireNotNull(source.observation.descriptor.id))
    private fun check(url: HttpUrl) {
        if (url != expected || !source.allowed()) throw IOException("Joined recording context changed")
    }
    val interceptor = Interceptor { chain ->
        check(chain.request().url)
        val original = chain.request()
        if (original.method !in setOf("GET", "HEAD") || original.headers.values("Range").size > 1 || original.header("If-Range") != null)
            throw IOException("Joined recording request is unavailable")
        val request = original.newBuilder()
        headers.forEach { (key, value) -> request.header(key, value) }
        val response = chain.proceed(request.build())
        try {
            check(response.request.url)
            if (response.code !in setOf(200, 206) || response.body?.contentType()?.let { it.type == "video" && it.subtype == "x-matroska" } != true) {
                source.deny(); throw IOException("Joined recording is no longer available. Read status or prepare explicitly.")
            }
            val originalBody = requireNotNull(response.body)
            val checked = object : ResponseBody() {
                private val input = object : ForwardingSource(originalBody.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        check(expected)
                        val pending = Buffer()
                        try {
                            val count = super.read(pending, minOf(byteCount, 32L * 1024))
                            check(expected)
                            if (count > 0) sink.write(pending, count)
                            return count
                        } finally { pending.clear() }
                    }
                }.buffer()
                override fun contentType() = originalBody.contentType()
                override fun contentLength() = originalBody.contentLength()
                override fun source(): BufferedSource = input
            }
            response.newBuilder().body(checked).build()
        } catch (error: Exception) { response.close(); throw error }
    }
    /** Guard the consumer boundary too: Okio may drain bytes without another source read. */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val input = upstream.createDataSource()
        object : DataSource by input {
            override fun open(dataSpec: DataSpec): Long {
                val url = dataSpec.uri.toString().toHttpUrlOrNull()
                    ?: throw IOException("Joined recording URL is unavailable")
                check(url)
                try {
                    val length = input.open(dataSpec)
                    check(url)
                    return length
                } catch (error: Exception) {
                    input.close()
                    throw error
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
                check(expected)
                if (length == 0) return 0
                val pending = ByteArray(minOf(length, 32 * 1024))
                try {
                    val count = input.read(pending, 0, pending.size)
                    check(expected)
                    if (count > 0) pending.copyInto(buffer, offset, 0, count)
                    return count
                } finally {
                    pending.fill(0)
                }
            }
        }
    }
    val requestInterceptor = Interceptor { chain -> check(chain.request().url); chain.proceed(chain.request()) }
    val loadPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
        override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions, info: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? = null
    }
}
