package com.strmr.mobile.downloads

import com.strmr.tv.api.Episode
import com.strmr.tv.api.SourceResult
import com.strmr.tv.api.rethrowVodDenial

/** Fallback is for an unavailable pack, never an authorization rejection. */
internal suspend fun resolveSeasonDownloads(
    source:SourceResult,episodes:List<Episode>,
    batch:suspend ()->List<BatchResolution>,
    single:suspend (SourceResult)->String?,
    enqueue:(Episode,String?,Long?)->Unit,
) {
    val results=try {batch()} catch(error:Exception) {error.rethrowVodDenial();emptyList()}
    if(results.isNotEmpty()) {
        for(entry in results) {
            val episode=episodes.firstOrNull {it.seasonNumber==entry.seasonNumber && it.episodeNumber==entry.episodeNumber} ?: continue
            enqueue(episode,entry.resolution?.webdavPath,entry.resolution?.fileSize)
        }
    } else {
        for(episode in episodes) {
            val path=source.targeting(episode)?.let {targeted->
                try {single(targeted)} catch(error:Exception) {error.rethrowVodDenial();null}
            }
            enqueue(episode,path,null)
        }
    }
}
