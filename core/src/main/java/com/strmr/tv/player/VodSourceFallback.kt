package com.strmr.tv.player

import com.strmr.tv.api.rethrowVodDenial

/** A missing/broken release may fall through. An authorization rejection ends this attempt. */
internal suspend fun <T,R> resolveVodFallback(candidates:List<T>,attempt:suspend (T)->R,unavailable:(T)->Unit={}):Pair<T,R>? {
    for(candidate in candidates) {
        try {return candidate to attempt(candidate)} catch(error:Exception) {
            error.rethrowVodDenial();unavailable(candidate)
        }
    }
    return null
}
