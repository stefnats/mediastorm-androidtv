package com.strmr.mobile.cast

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.reportProgress
import com.strmr.tv.live.keepLiveSessionAlive
import com.strmr.tv.live.stopLiveSession
import com.strmr.tv.player.PlaybackCoordinator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FAILURE_LIMIT=8

/** Every receiver reply belongs to the same original generation and profile as LOAD. */
internal fun CastController.startPolling(expected:CastOwnership.Ticket) {
    pollJob?.cancel()
    pollJob=scope.launch {
        var failures=0;var ticks=0;var sawPlayback=false
        while(isActive && ownership.owns(expected)) {
            delay(1_000)
            ownership.require(expected)
            val receiver=transport ?: return@launch
            val state=ownership.command(expected,receiver){receiver.status()}
            ownership.require(expected)
            if(transport!==receiver || ticket!==expected)return@launch
            ticks++
            if(state==null) {
                if(++failures>=FAILURE_LIMIT) {finish("receiver stopped answering",false,expected=expected);return@launch}
                continue
            }
            failures=0;apply(state)
            if(state.phase in setOf(CastPlaybackState.Phase.PLAYING,CastPlaybackState.Phase.PAUSED))sawPlayback=true
            if(state.phase==CastPlaybackState.Phase.FINISHED || sawPlayback && state.phase==CastPlaybackState.Phase.IDLE) {
                val ended=state.phase==CastPlaybackState.Phase.FINISHED || session?.let {CastEnd.isNearEnd(it.isLive,it.state.position,it.state.duration)}==true
                finish("receiver went idle",false,ended,expected);return@launch
            }
            if(ticks%10==0)reportProgress(false,expected=expected)
            if(ticks%(if(session?.isLive==true)10 else 20)==0)keepAlive(expected)
        }
    }
}

private fun CastController.apply(state:CastPlaybackState) {
    val current=session ?: return
    val duration=if(state.duration>0 && !current.isLive)state.duration else current.state.duration
    session=current.copy(state=state.copy(duration=duration,volume=state.volume ?: current.state.volume))
}

internal object CastEnd {
    fun isNearEnd(live:Boolean,position:Double,duration:Double)=!live && duration>0 && (position>=duration-60 || position/duration>=0.92)
}

internal fun CastController.reportProgress(ended:Boolean,closing:Boolean=false,expected:CastOwnership.Ticket?=currentTicket()) {
    val expected=expected?.takeIf {it===currentTicket()} ?: return
    val (request,path)=playback ?: return
    val state=session?.state ?: return
    val position=if(ended)maxOf(state.position,state.duration)else state.position
    if(state.duration<=0 || position<=0)return
    val body=expected.context.use {request.progressReport(position,state.duration,paused=state.phase==CastPlaybackState.Phase.PAUSED,ended=ended,sourcePath=path)}
    scope.launch {
        withContext(NonCancellable) {
            runCatching {
                if(closing) {expected.context.requireCurrent();ApiClient.reportProgress(body,expectedContext=expected.context.epoch)}
                else ownership.server(expected){context->ApiClient.reportProgress(body,expectedContext=context.epoch)}
            }
        }
    }
}

private fun CastController.keepAlive(expected:CastOwnership.Ticket) {
    val id=hlsSessionId ?: return
    val paused=session?.state?.phase==CastPlaybackState.Phase.PAUSED
    scope.launch {ownership.server(expected){context->ApiClient.keepLiveSessionAlive(id,paused=paused,expectedContext=context.epoch)}}
}

/** Detach only this generation; an old cleanup never changes a replacement's fields or transport. */
internal suspend fun CastController.finish(reason:String,stopReceiver:Boolean,ended:Boolean=false,expected:CastOwnership.Ticket?=ticket) {
    val own=expected ?: return
    if(ticket!==own)return
    val receiver=transport ?: own.transport
    val id=hlsSessionId
    if(ownership.owns(own))reportProgress(ended,closing=true,expected=own)
    pollJob?.cancel();pollJob=null
    ownership.retire(own)
    ticket=null;transport=null;hlsSessionId=null;playback=null;session=null;remoteVisible=false
    withContext(NonCancellable) {
        if(stopReceiver && receiver!=null)runCatching {ownership.stopCaptured(own,receiver)}
        id?.let {runCatching {ApiClient.stopLiveSession(it,own.context.epoch)}}
    }
    if(own.context.isCurrent())PlaybackCoordinator.sessionsEnded.value+=1
}
