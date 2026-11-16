package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException

/** Original player identity. This transient binding is not a content or offline permission. */
class PlaybackSessionContext private constructor(val epoch:Long,val profileId:String) {
    fun isCurrent():Boolean=runCatching {requireCurrent();true}.getOrDefault(false)
    fun requireCurrent() {use {}}
    fun<T> use(action:()->T):T=ApiClient.inContext(epoch) {
        if(ApiClient.profileId!=profileId)throw CancellationException("Playback context changed")
        action()
    }
    companion object {
        fun capture(expected:Long=ProfileDataContext.current):PlaybackSessionContext=ApiClient.inContext(expected) {PlaybackSessionContext(expected,ApiClient.profileId)}
    }
}

/** Both platform activities use this before constructing a decoder or media connection. */
fun PlayerSession.canOpenPlayer():Boolean=ownership?.isCurrent()==true && playbackAllowed()
