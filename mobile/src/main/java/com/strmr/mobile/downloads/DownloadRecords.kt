package com.strmr.mobile.downloads

import com.strmr.tv.app.AppLog

/** The download records on disk (`files/downloads.json`), written atomically. */
object DownloadRecords {
    private val repository by lazy {DownloadRepository(DownloadStorage.recordsFile)}
    fun load():List<DownloadItem> = repository.load()
    fun save(revision:Long,items:List<DownloadItem>) {
        runCatching {repository.save(revision,items)}.onFailure {AppLog.w("Downloads","Saved download records could not be updated")}
    }
}
