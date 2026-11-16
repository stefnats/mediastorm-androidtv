package com.strmr.mobile.downloads

import com.strmr.mobile.connection.ConnectionManager
import com.strmr.mobile.connection.NetworkMonitor
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Episode
import com.strmr.tv.api.TrackSelection
import com.strmr.tv.app.AppSession
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DownloadError(message:String):Exception(message)

/** Public projection is always filtered again at access, including the interval before a flow observer runs. */
object Downloads {
    private val scope=MainScope()
    private var started=false
    private val error=MutableStateFlow<String?>(null)
    val accessMessage:StateFlow<String?> = error
    private val manager by lazy {OwnedDownloadManager(scope,OfflineAccess::current,OfflineAccess::require,
        load=DownloadRecords::load,save={revision,records->withContext(Dispatchers.IO){DownloadRecords.save(revision,records)}},
        transfer={item,context,progress->DownloadTransfer(item,context,progress).run()},
        remove={item,context->withContext(Dispatchers.IO){DownloadStorage.removeFiles(item,context);OfflineProgress.forget(item,context)}},
        bytes={item,context->withContext(Dispatchers.IO){DownloadStorage.bytes(item,context)}},
        changed={all,visible->DownloadNotifications.reconcile(all,visible);DownloadService.sync(visible)},
        completed=DownloadNotifications::completed,failed=DownloadNotifications::failed)}
    private fun filtered(rows:List<DownloadItem>):List<DownloadItem> {
        val context=OfflineAccess.current();return rows.filter {OfflineAccess.allows(it,context)}
    }
    @OptIn(InternalCoroutinesApi::class,ExperimentalForInheritanceCoroutinesApi::class)
    val items:StateFlow<List<DownloadItem>> = object:StateFlow<List<DownloadItem>> {
        override val value get()=filtered(manager.items.value)
        override val replayCache get()=listOf(value)
        override suspend fun collect(collector:FlowCollector<List<DownloadItem>>):Nothing=manager.items.collect {collector.emit(filtered(it))}
    }
    @OptIn(InternalCoroutinesApi::class,ExperimentalForInheritanceCoroutinesApi::class)
    val usedBytes:StateFlow<Long> = object:StateFlow<Long> {
        override val value get()=manager.currentBytes()
        override val replayCache get()=listOf(value)
        override suspend fun collect(collector:FlowCollector<Long>):Nothing=manager.usedBytes.collect {collector.emit(manager.currentBytes())}
    }
    fun start() {
        if(started)return
        try {manager.start();started=true} catch(_:Exception) {error.value="Saved downloads could not be read. Their files were kept.";return}
        scope.launch {
            combine(NetworkMonitor.path,ConnectionManager.status,AppSession.phase,DownloadSettings.changes){_,_,_,_->Unit}
                .combine(ProfileDataContext.changes){_,_->Unit}.combine(AppSession.profile){_,_->Unit}.combine(AppSession.pinChallenge){_,_->Unit}
                .collect {processQueue()}
        }
    }
    private fun processQueue() {
        error.value=if(OfflineAccess.current()==null)OfflineAccess.unavailableMessage()else null
        val path=NetworkMonitor.path.value
        manager.process(DownloadQueue.Conditions(DownloadSettings.maxConcurrent,DownloadSettings.wifiOnly,path.satisfied && !path.metered,
            AppSession.phase.value==AppSession.Phase.Ready && ConnectionManager.status.value==ConnectionManager.Status.Online,System.currentTimeMillis()))
        DownloadActions.picking.value?.let {if(it.ownership!=OfflineAccess.current())DownloadActions.picking.value=null}
        manager.refreshUsage()
    }
    fun item(id:String)=manager.item(id)
    fun find(titleId:String,season:Int?,episode:Int?)=manager.find(titleId,season,episode)
    fun find(target:DetailsTarget,episode:Episode?):DownloadItem?=if(target.isMovie)find(target.titleId,null,null)else episode?.let {find(target.titleId,it.seasonNumber,it.episodeNumber)}
    fun state(target:DetailsTarget,episode:Episode?):Flow<DownloadItem?> = items.map {find(target,episode)}.distinctUntilChanged()
    val completed get()=items.value.filter {it.isCompleted}
    fun start(target:DetailsTarget,episode:Episode?,streamPath:String,releaseName:String?,expectedBytes:Long,tracks:TrackSelection?=null,context:OfflineContext=OfflineAccess.capture()):DownloadItem {
        start();OfflineAccess.require(context)
        find(target,episode)?.let {return it}
        val free=DownloadStorage.availableBytes
        if(!DownloadStorage.hasRoom(expectedBytes,free))throw DownloadError("Not enough storage for this download.")
        val item=DownloadItem.create(target,if(target.isMovie)null else episode,streamPath,releaseName,expectedBytes,tracks,owner=context.owner)
        val stored=manager.add(item,context)
        scope.launch {DownloadStorage.savePoster(stored,context)}
        processQueue();return stored
    }
    fun pause(id:String) {runCatching {manager.pause(id)}}
    fun resume(id:String) {runCatching {manager.resume(id)}}
    fun delete(id:String)=delete(listOf(id))
    fun delete(ids:Collection<String>) {runCatching {manager.delete(ids)}}
    fun refreshUsage()=manager.refreshUsage()
    fun persistNow()=manager.persistNow()
}
