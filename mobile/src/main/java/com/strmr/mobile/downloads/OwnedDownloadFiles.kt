package com.strmr.mobile.downloads

import java.io.File

internal interface DownloadFileGuard {
    fun <T> use(context:OfflineContext,item:DownloadItem,action:()->T):T
}
internal object CurrentDownloadFiles:DownloadFileGuard {
    override fun <T> use(context:OfflineContext,item:DownloadItem,action:()->T):T=OfflineAccess.use(context,item,action)
}

/** File mutations hold the same identity boundary as profile/account transitions. */
internal class OwnedDownloadFiles(private val directory:File,private val access:DownloadFileGuard=CurrentDownloadFiles) {
    companion object {
        fun safe(item:DownloadItem)=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(item.id) &&
            item.fileName.startsWith(item.id+".") && DownloadItem.mediaExtension(item.fileName.removePrefix(item.id+"."))
    }
    fun <T> use(item:DownloadItem,context:OfflineContext,action:()->T):T=access.use(context,item) {
        if(!safe(item))throw DownloadAccessError();action()
    }
    fun file(item:DownloadItem,context:OfflineContext):File=use(item,context){File(directory,item.fileName)}
    fun part(item:DownloadItem,context:OfflineContext):File=use(item,context){File(directory,item.id+".part")}
    fun poster(item:DownloadItem,context:OfflineContext):File=use(item,context){File(directory,item.id+".jpg")}
    fun remove(item:DownloadItem,context:OfflineContext)=use(item,context) {
        listOf(File(directory,item.fileName),File(directory,item.id+".part"),File(directory,item.id+".jpg")).forEach {it.delete()}
    }
    fun bytes(item:DownloadItem,context:OfflineContext):Long=use(item,context) {
        listOf(File(directory,item.fileName),File(directory,item.id+".part"),File(directory,item.id+".jpg")).sumOf {it.length()}
    }
    fun finish(item:DownloadItem,context:OfflineContext):Long=use(item,context) {
        val part=File(directory,item.id+".part");val target=File(directory,item.fileName)
        if(target.exists() && !target.delete())throw java.io.IOException("The download could not be saved.")
        if(!part.renameTo(target))throw java.io.IOException("The download could not be saved.")
        target.length()
    }
}
