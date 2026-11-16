package com.strmr.mobile.cast

import com.strmr.tv.player.PlaybackSessionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Actual Cast starts and receiver commands share this generation and per-receiver boundary. */
internal class CastOwnership(private val eligible:(PlaybackSessionContext)->Boolean,private val stopServer:suspend (String,PlaybackSessionContext)->Unit={_,_->}) {
    class Ticket internal constructor(val context:PlaybackSessionContext,val deviceId:String,val generation:Long) {
        internal var transport:CastTransport?=null
    }
    private var active:Ticket?=null
    private var generation=0L
    private val receivers=mutableMapOf<String,Mutex>()
    fun allowed(context:PlaybackSessionContext)=context.isCurrent() && eligible(context)
    fun begin(context:PlaybackSessionContext,deviceId:String):Ticket {
        if(!allowed(context))throw CancellationException("Casting context is unavailable")
        return Ticket(context,deviceId,++generation).also {active=it}
    }
    fun owns(ticket:Ticket)=active===ticket && allowed(ticket.context)
    fun accepts(generation:Long?)=active?.let {it.generation==generation && owns(it)}==true
    fun require(ticket:Ticket) {if(!owns(ticket))throw CancellationException("Casting context changed")}
    fun retire(ticket:Ticket) {if(active===ticket)active=null}
    private fun receiver(deviceId:String)=receivers.getOrPut(deviceId){Mutex()}

    /** Keep a canceled SDK load serialized until its actual reply; no later load can overtake it. */
    suspend fun start(ticket:Ticket,prepare:suspend ()->PreparedCast,publish:(PreparedCast)->Unit) {
        var prepared:PreparedCast?=null
        var accepted=false
        try {
            require(ticket)
            val ready=prepare().also {prepared=it}
            ticket.transport=ready.transport
            require(ticket)
            receiver(ticket.deviceId).withLock {
                require(ticket)
                withContext(NonCancellable) {ready.transport.load(ready.media)}
                currentCoroutineContext().ensureActive()
                require(ticket)
                publish(ready)
                accepted=true
            }
        } finally {
            if(!accepted)withContext(NonCancellable) {
                prepared?.hlsSessionId?.takeIf {ticket.context.isCurrent()}?.let {id->runCatching {stopServer(id,ticket.context)}}
                ticket.transport?.let {captured->runCatching {stopCaptured(ticket,captured)}}
            }
        }
    }

    suspend fun<T> command(ticket:Ticket,transport:CastTransport,action:suspend ()->T):T =
        receiver(ticket.deviceId).withLock {
            require(ticket)
            if(ticket.transport!==transport)throw CancellationException("Casting receiver changed")
            val result=action()
            require(ticket)
            result
        }
    /** Heartbeats and preparation helpers cannot prepare a request under a replacement owner. */
    suspend fun<T> server(ticket:Ticket,action:suspend (PlaybackSessionContext)->T):T {
        currentCoroutineContext().ensureActive();require(ticket)
        val result=action(ticket.context)
        require(ticket);return result
    }
    /** Captured receiver only. A replacement reservation owns the same physical receiver now. */
    suspend fun stopCaptured(ticket:Ticket,transport:CastTransport) = withContext(NonCancellable) {
        receiver(ticket.deviceId).withLock {
            val replacement=active
            if(replacement!==ticket && replacement?.deviceId==ticket.deviceId)return@withLock
            transport.stop()
        }
    }
}
