package com.strmr.mobile.downloads

import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One main-thread ledger; foreign and unowned records are retained, never exposed as queue work. */
internal class OwnedDownloadManager(
    private val scope:CoroutineScope,
    private val current:()->OfflineContext?,
    private val verify:(OfflineContext,DownloadItem?)->Unit,
    private val load:()->List<DownloadItem>,
    private val save:suspend (Long,List<DownloadItem>)->Unit,
    private val transfer:suspend (DownloadItem,OfflineContext,(Long,Long?)->Unit)->DownloadTransfer.Outcome,
    private val remove:suspend (DownloadItem,OfflineContext)->Unit,
    private val bytes:suspend (DownloadItem,OfflineContext)->Long,
    private val changed:(List<DownloadItem>,List<DownloadItem>)->Unit={_,_->},
    private val completed:(DownloadItem)->Unit={},private val failed:(DownloadItem)->Unit={},
    private val now:()->Long=System::currentTimeMillis,
) {
    private var records=emptyList<DownloadItem>()
    private val visible=MutableStateFlow<List<DownloadItem>>(emptyList())
    val items:StateFlow<List<DownloadItem>> = visible
    val usedBytes=MutableStateFlow(0L)
    private var usageContext:OfflineContext?=null
    fun currentBytes()=if(usageContext==current())usedBytes.value else 0L
    private var revision=0L
    private var started=false
    private var lastContext:OfflineContext?=null
    private val deleting=mutableSetOf<String>()
    private var timer:Job?=null
    private var persistence:Job?=null
    private var conditions=DownloadQueue.Conditions(1,true,false,false,0)
    private data class Flight(val context:OfflineContext,val item:DownloadItem,var job:Job?=null)
    private val flights=mutableMapOf<String,Flight>()
    private val retiring=mutableMapOf<String,MutableSet<Job>>()
    private fun retire(id:String) {
        val job=flights.remove(id)?.job ?: return
        job.cancel()
        retiring.getOrPut(id){mutableSetOf()}.add(job)
        scope.launch {job.join();retiring[id]?.let {it.remove(job);if(it.isEmpty())retiring.remove(id)}}
    }
    private suspend fun awaitWriters(id:String) {
        while(true) {
            val jobs=retiring[id]?.toList().orEmpty()
            if(jobs.isEmpty())return
            jobs.joinAll();currentCoroutineContext().ensureActive()
            retiring[id]?.let {it.removeAll(jobs.toSet());if(it.isEmpty())retiring.remove(id)}
        }
    }
    private fun allowed(item:DownloadItem,context:OfflineContext?)=context!=null && OwnedDownloadFiles.safe(item) && item.owner==context.owner && item.owner is DownloadOwner.Known && item.owner.valid()
    private fun contextCurrent(context:OfflineContext)=current()==context
    private fun project() {
        val context=current()
        visible.value=records.filter {it.id !in deleting && allowed(it,context)}
        changed(records,visible.value)
    }
    fun start() {if(started)return;records=load();started=true;lastContext=current();project()}
    fun item(id:String):DownloadItem? {val context=current();return records.firstOrNull {it.id==id && allowed(it,context)}}
    fun find(titleId:String,season:Int?,episode:Int?)=items.value.firstOrNull {allowed(it,current()) && it.matches(titleId,season,episode)}
    fun add(item:DownloadItem,context:OfflineContext):DownloadItem {
        start();verify(context,item)
        val existing=records.firstOrNull {allowed(it,context) && it.matches(item.titleId,item.seasonNumber,item.episodeNumber)}
        if(existing!=null)return existing
        if(records.any {it.id==item.id})throw DownloadAccessError()
        records=records+item;revision++;project();persistNow();process(conditions);return item
    }
    private fun replace(item:DownloadItem) {records=records.map {if(it.id==item.id && it.owner==item.owner) item else it};revision++;project()}
    fun pause(id:String) {
        val context=current() ?: return;val item=item(id) ?: return;verify(context,item)
        val next=DownloadQueue.paused(item) ?: return
        retire(id);replace(next);persistNow();process(conditions)
    }
    fun resume(id:String) {
        val context=current() ?: return;val item=item(id) ?: return;verify(context,item)
        val next=DownloadQueue.resumed(item) ?: return;replace(next);persistNow();process(conditions)
    }
    fun delete(ids:Collection<String>) {
        val context=current() ?: return
        val selected=records.filter {it.id in ids && allowed(it,context)}
        if(selected.isEmpty())return
        deleting+=selected.map {it.id};project()
        selected.forEach {retire(it.id)}
        scope.launch {
            try {
                for(item in selected) {awaitWriters(item.id);verify(context,item);remove(item,context)}
                verify(context,null)
                records=records.filterNot {row->selected.any {it.id==row.id && it.owner==row.owner}};revision++;project();persistNow();refreshUsage();process(conditions)
            } catch(error:CancellationException) {throw error
            } catch(_:Exception) {} finally {deleting.removeAll(selected.map {it.id}.toSet());project()}
        }
    }
    /** Called for identity as well as network changes. A previous epoch's transfer never resumes implicitly. */
    fun process(value:DownloadQueue.Conditions) {
        if(!started)return
        conditions=value.copy(now=now());val context=current()
        val previous=lastContext
        if(previous!=null && previous!=context)records=records.map {row->
            if(allowed(row,previous) && row.status in setOf(DownloadItem.Status.PENDING,DownloadItem.Status.DOWNLOADING)) {
                revision++;row.copy(status=DownloadItem.Status.PAUSED,bytesPerSecond=0.0,waitingReason=null,retryAt=null)
            }else row
        }
        lastContext=context
        for((id,flight) in flights.toMap())if(context==null || flight.context!=context || !allowed(flight.item,context)) {
            retire(id)
            records=records.map {if(it.id==id && it.owner==flight.item.owner)it.copy(status=DownloadItem.Status.PAUSED,bytesPerSecond=0.0,waitingReason=null,retryAt=null)else it};revision++
        }
        // A process restart may leave an owned record marked running. Foreign records remain byte-for-byte metadata peers.
        records=records.map {row->if(allowed(row,context) && row.status==DownloadItem.Status.DOWNLOADING && row.id !in flights && row.id !in deleting)DownloadQueue.relaunched(row).also {revision++}else row}
        project()
        if(context==null) {timer?.cancel();persistSoon();usedBytes.value=0;return}
        val plan=DownloadQueue.plan(visible.value,conditions)
        for(id in plan.hold) {
            if(id !in flights)continue
            retire(id)
            item(id)?.let {replace(it.copy(status=DownloadItem.Status.PENDING,bytesPerSecond=0.0))}
        }
        plan.waiting.forEach {(id,reason)->item(id)?.takeIf {it.waitingReason!=reason}?.let {replace(it.copy(waitingReason=reason))}}
        for(id in plan.start)item(id)?.let {launch(it,context)}
        timer?.cancel();plan.nextRetryAt?.let {at->timer=scope.launch {delay((at-now()).coerceAtLeast(0)+100);process(conditions)}}
        persistSoon()
    }
    private fun launch(item:DownloadItem,context:OfflineContext) {
        if(item.id in flights)return
        verify(context,item)
        val flight=Flight(context,item);flights[item.id]=flight
        replace(item.copy(status=DownloadItem.Status.DOWNLOADING,waitingReason=null,retryAt=null))
        flight.job=scope.launch {
            val result=try {awaitWriters(item.id);verify(context,item);transfer(item,context) {written,total->scope.launch {
                if(flights[item.id]===flight && contextCurrent(context))this@OwnedDownloadManager.item(item.id)?.let {row->replace(row.copy(bytesWritten=written,expectedBytes=total?.takeIf {it>0} ?: row.expectedBytes));persistSoon()}
            }}} catch(error:CancellationException) {throw error
            } catch(_:Exception) {DownloadTransfer.Outcome.Failed("The download could not continue.",true)}
            if(flights[item.id]!==flight || !contextCurrent(context))return@launch
            verify(context,item);flights.remove(item.id)
            val row=this@OwnedDownloadManager.item(item.id) ?: return@launch
            when(result) {
                is DownloadTransfer.Outcome.Completed->{val done=row.copy(status=DownloadItem.Status.COMPLETED,bytesWritten=result.bytes,expectedBytes=result.bytes,bytesPerSecond=0.0,completedAt=now(),waitingReason=null,errorMessage=null,retries=0);replace(done);completed(done);refreshUsage()}
                is DownloadTransfer.Outcome.Failed->{val next=DownloadQueue.afterFailure(row,result.message,result.retryable,now());replace(next);if(next.status==DownloadItem.Status.FAILED)failed(next)}
            }
            persistNow();process(conditions)
        }
    }
    fun refreshUsage() {
        val context=current() ?: run {usedBytes.value=0;return}
        val snapshot=visible.value
        scope.launch {
            val total=try {snapshot.sumOf {verify(context,it);bytes(it,context)}}catch(error:CancellationException){throw error}catch(_:Exception){0L}
            if(contextCurrent(context)){usageContext=context;usedBytes.value=total}
        }
    }
    fun persistNow() {if(!started)return;persistence?.cancel();val snapshot=records.toList();val version=revision;scope.launch {save(version,snapshot)}}
    private fun persistSoon() {if(persistence?.isActive==true)return;persistence=scope.launch {delay(5_000);persistNow()}}
}
