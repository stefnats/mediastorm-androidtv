package com.strmr.tv.player

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.live.Recording
import com.strmr.tv.live.RecordingCaptureRules
import com.strmr.tv.live.RecordingPart
import com.strmr.tv.live.RecordingRules

/** Explicit parts have independent starts and never participate in catalogue history or Up Next. */
internal fun recordingPartSession(recording:Recording,part:RecordingPart,url:String,ownership:PlaybackSessionContext?=null,partPlayback:RecordingPartPlayback?=null):PlayerSession {
    require(RecordingCaptureRules.playable(recording,part)) {"Recording part is unavailable"}
    val title="${RecordingRules.title(recording)} · Part ${part.index}"
    val request=PlaybackRequest(DetailsTarget("recording:${recording.id}/parts/${part.index}",title,isMovie=true),
        start=partPlayback?.startPositionMs?.takeIf {it>0}?.let {com.strmr.tv.api.StartPoint.Seconds(it/1000.0)})
    return PlayerSession(request,url,null,reportsProgress=false,sourceLabel=StreamInfo.recording(recording.channelName),ownership=ownership,recordingPart=partPlayback)
}

/** Selected closed files, copied once. Capture wall-clock intervals are never used as media durations. */
internal class RecordingPartSnapshot private constructor(val recording:Recording,val parts:List<RecordingPart>,private val signatures:Map<Int,String>) {
    fun closedSignature(partIndex:Int)=signatures[partIndex]
    fun accepts(cursor:RecordingPartCursor)=cursor.recordingId==recording.id && cursor.localPositionMs>=0 &&
        closedSignature(cursor.partIndex)==cursor.closedSignature
    companion object {
        fun capture(recording:Recording):RecordingPartSnapshot? {
            val parts=RecordingCaptureRules.queueParts(recording) ?: return null
            val copy=recording.copy(capture=recording.capture!!.copy(parts=parts.toList(),gaps=recording.capture.gaps.toList()))
            val signatures=parts.associate {part ->
                val fields=listOf(recording.id,part.index.toString(),part.startedAt,part.endedAt.orEmpty(),part.bytes.toString(),part.endReason.orEmpty(),part.streamPath.orEmpty())
                part.index to java.security.MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") {"${it.length}:$it"}.toByteArray()).joinToString("") {"%02x".format(it)}
            }
            return RecordingPartSnapshot(copy,parts.toList(),signatures)
        }
    }
}

/** One current queue, retired by any newer playback intent. This is local lifetime bookkeeping only. */
internal object RecordingPartQueues {
    private var current:RecordingPartQueue?=null
    fun replace(queue:RecordingPartQueue?) {if(current!==queue){current?.cancel();current=queue}}
    fun current(queue:RecordingPartQueue)=current===queue
}

internal class RecordingPartQueue(
    val owner:PlaybackSessionContext,val snapshot:RecordingPartSnapshot,
    private val store:(RecordingPartCursor)->Unit={RecordingPartCursorStore.write(owner,snapshot,it)},
) {
    private var open=true
    private var active:RecordingPartPlayback?=null
    fun current(run:RecordingPartPlayback)=open && owner.isCurrent() && RecordingPartQueues.current(this) && active===run
    fun cancel() {open=false;active?.retire()}
    fun start(cursor:RecordingPartCursor?=null):RecordingPartPlayback? {
        if(active!=null || !open || !owner.isCurrent() || !RecordingPartQueues.current(this))return null
        val saved=cursor?.takeIf(snapshot::accepts)
        var position=saved?.let {snapshot.parts.indexOfFirst {part->part.index==it.partIndex}} ?: 0
        if(saved?.ended==true)position++
        if(position !in snapshot.parts.indices)position=0
        return run(position,if(saved?.ended!=true)saved?.localPositionMs ?: 0 else 0)
    }
    private fun run(offset:Int,start:Long):RecordingPartPlayback = RecordingPartPlayback(this,offset,start).also {active=it}
    fun next(previous:RecordingPartPlayback):RecordingPartPlayback? {
        if(!current(previous) || !previous.released || !previous.finished || previous.offset+1 !in snapshot.parts.indices)return null
        return run(previous.offset+1,0)
    }
    fun write(run:RecordingPartPlayback,position:Long,ended:Boolean) {
        if(current(run) && !run.released && position>=0)owner.use {
            if(current(run))store(RecordingPartCursor(snapshot.recording.id,run.part.index,position,requireNotNull(snapshot.closedSignature(run.part.index)),ended))
        }
    }
}

/** Actual player adapter. A next receipt can be consumed only once and after the old decoder is released. */
class RecordingPartPlayback internal constructor(internal val queue:RecordingPartQueue,internal val offset:Int,val startPositionMs:Long) {
    val part:RecordingPart get()=queue.snapshot.parts[offset]
    internal val owner get()=queue.owner
    internal var released=false;private set
    internal var finished=false;private set
    private var retired=false
    private var claimed=false
    private val nextState=kotlinx.coroutines.flow.MutableStateFlow<RecordingPartHandoff?>(null)
    internal val next:kotlinx.coroutines.flow.StateFlow<RecordingPartHandoff?> get()=nextState
    private var lastSaved:Long?=null
    internal fun canPlay()=!retired && !released && queue.current(this)
    internal fun canStart()=canPlay() && !finished && !claimed
    internal fun wasReplaced()=!RecordingPartQueues.current(queue)
    internal fun claim() {check(canStart()) {"Recording part playback changed"};claimed=true}
    internal fun tick(position:Long) {if(!retired && !finished && (lastSaved==null || kotlin.math.abs(position-lastSaved!!)>=5000)) {queue.write(this,position,false);lastSaved=position}}
    internal fun ended(position:Long,hasError:Boolean):Boolean {
        if(retired || released || finished || !queue.current(this))return false
        if(hasError){cancel();return false}
        queue.write(this,position,true);finished=true
        if(offset+1<queue.snapshot.parts.size)nextState.value=RecordingPartHandoff(this)
        return true
    }
    internal fun cancel() {queue.cancel()}
    internal fun retire(){retired=true;nextState.value=null}
    internal fun release(position:Long) {
        if(released)return
        if(!finished && !retired)queue.write(this,position,false)
        released=true
        if(!finished || nextState.value==null)queue.cancel()
    }
}

class RecordingPartHandoff internal constructor(private val original:RecordingPartPlayback) {
    private var consumed=false
    internal fun take():RecordingPartPlayback? {
        if(consumed)return null
        val next=original.queue.next(original) ?: return null
        consumed=true;return next
    }
}

/** Both activities check this before creating a decoder; an active run is not a replayable Intent grant. */
fun PlayerSession.recordingPartReady()=recordingPart?.canStart()!=false
