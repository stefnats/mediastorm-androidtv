package com.strmr.mobile.downloads

import android.os.StatFs
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.App
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Raw files are retained across ownership changes; only an eligible owned record can address them. */
object DownloadStorage {
    val directory:File by lazy {File(App.context.filesDir,"downloads").apply {mkdirs()}}
    val recordsFile:File get()=File(App.context.filesDir,"downloads.json")
    internal val files by lazy {OwnedDownloadFiles(directory)}
    fun file(item:DownloadItem,context:OfflineContext)=files.file(item,context)
    fun poster(item:DownloadItem):File? {
        val context=OfflineAccess.current() ?: return null
        return try {files.use(item,context){files.poster(item,context).takeIf {it.isFile}}}catch(_:Exception){null}
    }
    fun removeFiles(item:DownloadItem,context:OfflineContext)=files.remove(item,context)
    fun bytes(item:DownloadItem,context:OfflineContext)=files.bytes(item,context)
    val availableBytes:Long get()=runCatching {StatFs(directory.path).availableBytes}.getOrDefault(0)
    val totalBytes:Long get()=runCatching {StatFs(directory.path).totalBytes}.getOrDefault(0)
    fun hasRoom(bytes:Long,free:Long=availableBytes)=bytes<=0 || free>=bytes*1.1

    @OptIn(InternalCoroutinesApi::class)
    suspend fun savePoster(item:DownloadItem,context:OfflineContext)=withContext(Dispatchers.IO) {
        OfflineAccess.require(context,item)
        val captured=OwnedDownloadHttp.poster(item,context) ?: return@withContext
        val call=captured.client(ApiClient.http).newCall(captured.request).apply {timeout().timeout(30,TimeUnit.SECONDS)}
        val cancelled=currentCoroutineContext().job.invokeOnCompletion(onCancelling=true,invokeImmediately=true){if(it!=null)call.cancel()}
        try {
            call.execute().use {response->
                val bytes=response.body?.bytes()
                ensureActive()
                if(response.isSuccessful && bytes?.isNotEmpty()==true)files.use(item,context){files.poster(item,context).writeBytes(bytes)}
            }
        } catch(error:CancellationException) {throw error
        } catch(_:Exception) {ensureActive()
        } finally {cancelled.dispose()}
    }
}
