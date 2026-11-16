package com.strmr.mobile.downloads

import android.content.Context
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.StartPoint
import com.strmr.tv.api.reportProgress
import com.strmr.tv.app.App
import com.strmr.tv.player.ProgressSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** New progress is owner-bound. Legacy positions/pending keys remain untouched and unsubmitted. */
object OfflineProgress {
    private val prefs by lazy {App.context.getSharedPreferences("strmr.downloads.progress",Context.MODE_PRIVATE)}
    private val store by lazy {OwnedProgressStore({prefs.getString("owned-v2",null)},{prefs.edit().putString("owned-v2",it).apply()})}
    private val flushing=Mutex()
    fun sink(item:DownloadItem,context:OfflineContext):ProgressSink=object:ProgressSink {
        override fun saved(positionSeconds:Double,durationSeconds:Double,ended:Boolean) {
            runCatching {OfflineAccess.use(context,item) {
                val keep=!ended && durationSeconds>0 && positionSeconds.isFinite() && positionSeconds>=30 && positionSeconds/durationSeconds<0.95
                store.position(item,context,positionSeconds.takeIf {keep})
            }}
        }
        override fun undelivered(body:JsonObject) {runCatching {OfflineAccess.use(context,item) {store.pending(item,context,body)}}}
    }
    fun resumePoint(item:DownloadItem):StartPoint? {val context=OfflineAccess.capture();return OfflineAccess.use(context,item) {store.read(item,context)?.position?.let {StartPoint.Seconds(it)}}}
    fun forget(item:DownloadItem,context:OfflineContext)=OfflineAccess.use(context,item) {store.forget(item,context)}
    val hasPending:Boolean get()=OfflineAccess.current()?.let {store.entries().any {row->row.owner==it.owner && row.pending!=null}}==true
    suspend fun flush()=flushing.withLock {
        val context=OfflineAccess.current() ?: return@withLock
        for(row in store.entries().filter {it.owner==context.owner && it.pending!=null}) {
            val item=Downloads.item(row.downloadId) ?: continue
            try {
                OfflineAccess.require(context,item)
                ApiClient.reportProgress(row.pending!!,expectedContext=context.epoch)
                OfflineAccess.use(context,item) {store.delivered(row)}
            } catch(error:CancellationException) {throw error
            } catch(_:Exception) {return@withLock}
        }
    }
}

@Serializable
internal data class OwnedProgress(val owner:DownloadOwner,val downloadId:String,val position:Double?=null,val pending:JsonObject?=null)

/** Read failures never turn into an empty rewrite. Unknown/legacy stores are preserved. */
internal class OwnedProgressStore(private val load:()->String?,private val save:(String)->Unit) {
    private val codec=ListSerializer(OwnedProgress.serializer())
    @Synchronized fun entries():List<OwnedProgress> =load()?.let {ApiJson.decodeFromString(codec,it)} ?: emptyList()
    @Synchronized private fun update(change:(List<OwnedProgress>)->List<OwnedProgress>) {save(ApiJson.encodeToString(codec,change(entries())))}
    fun read(item:DownloadItem,context:OfflineContext)=entries().firstOrNull {it.owner==context.owner && it.downloadId==item.id && item.owner==context.owner}
    private fun change(item:DownloadItem,context:OfflineContext,action:(OwnedProgress)->OwnedProgress?) {
        if(item.owner!=context.owner)throw DownloadAccessError()
        update {rows->val old=rows.firstOrNull {it.owner==context.owner && it.downloadId==item.id} ?: OwnedProgress(context.owner,item.id)
            rows.filterNot {it.owner==context.owner && it.downloadId==item.id}+listOfNotNull(action(old))}
    }
    fun position(item:DownloadItem,context:OfflineContext,value:Double?)=change(item,context){it.copy(position=value)}
    fun pending(item:DownloadItem,context:OfflineContext,value:JsonObject)=change(item,context){it.copy(pending=value)}
    fun forget(item:DownloadItem,context:OfflineContext)=change(item,context){null}
    fun delivered(row:OwnedProgress)=update {rows->rows.map {if(it==row)it.copy(pending=null)else it}}
}
