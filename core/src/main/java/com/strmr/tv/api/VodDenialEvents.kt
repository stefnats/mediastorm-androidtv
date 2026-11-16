package com.strmr.tv.api

import com.strmr.tv.library.ProfileDataContext
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Request ordering only. This is neither a grant nor a persisted content-policy cache. */
data class VodAttempt internal constructor(val titleId:String,val context:Long,val sequence:Long)
data class ObservedVodDenial(val attempt:VodAttempt,val reason:VodAccessDenial)

object VodDenialEvents {
    private val sequence=AtomicLong()
    private val events=MutableSharedFlow<ObservedVodDenial>(replay=0,extraBufferCapacity=64,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val observed=events.asSharedFlow()
    fun begin(titleId:String,context:Long)=VodAttempt(titleId,context,sequence.incrementAndGet())
    fun fence():Long=sequence.get()
    /** Publishers must additionally check their own request/player generation before calling. */
    fun reject(attempt:VodAttempt,reason:VodAccessDenial) {
        if(attempt.context==ProfileDataContext.current)events.tryEmit(ObservedVodDenial(attempt,reason))
    }
}
