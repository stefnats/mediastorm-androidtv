package com.strmr.tv.api

import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/** Credential routing for the global image loader, independent of content/cache authorization. */
object ServerArtwork {
    /** Resolve server-relative artwork without losing query encoding or reverse-proxy paths. */
    fun url(path: String): String? {
        return try {
            val owner = PlaybackSessionContext.capture()
            owner.use { ArtworkImageAccess(ApiClient.baseUrl, emptyMap(), owner.profileId, owner::isCurrent).url(path) }
        } catch (_: CancellationException) { null }
    }

    /** Public images need pairing transport; only protected library artwork needs account/PIN. */
    fun client(base: OkHttpClient = OkHttpClient()): OkHttpClient = ArtworkImageAccess.client(base) {
        val owner = PlaybackSessionContext.capture()
        owner.use { ArtworkImageAccess(ApiClient.baseUrl, ApiClient.mediaHeaders, owner.profileId, owner::isCurrent) }
    }
}
