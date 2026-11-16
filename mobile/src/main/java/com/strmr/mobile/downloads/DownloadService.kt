package com.strmr.mobile.downloads

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.strmr.tv.app.App
import com.strmr.tv.app.AppLog
import com.strmr.tv.app.AppSession

/**
 * Keeps the app's process alive while downloads are queued or running (a `dataSync`
 * foreground service with the progress notification), so they continue with the app in the
 * background or swiped away. The transfers themselves run in [Downloads]; this only holds the
 * process and the notification. A cold restart needs a newly verified owner context before
 * transfers can continue; it never adopts the account currently selected by another session.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(OfflineAccess.current()==null) {stop();return START_NOT_STICKY}
        Downloads.start()
        val items=Downloads.items.value
        if(!wanted(items)) {stop();return START_NOT_STICKY}
        val type=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching {ServiceCompat.startForeground(this,DownloadNotifications.ONGOING_ID,DownloadNotifications.ongoing(items),type)}
            .onFailure {stop();return START_NOT_STICKY}
        running=true
        return START_NOT_STICKY
    }

    /** Android 15's daily limit for data-sync services: pause until the app is opened again. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("Downloads", "foreground time limit reached; pausing downloads")
        Downloads.items.value.filter { it.status == DownloadItem.Status.DOWNLOADING || it.status == DownloadItem.Status.PENDING }
            .forEach { Downloads.pause(it.id) }
        stop()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun stop() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        @Volatile private var running = false

        private fun wanted(items: List<DownloadItem>) =
            items.any { OfflineAccess.allows(it) && (it.status == DownloadItem.Status.DOWNLOADING || it.status == DownloadItem.Status.PENDING) }

        /** Starts the service while there is work, updates its notification, stops it once there is none. */
        fun sync(items: List<DownloadItem>) {
            val context = App.context
            val items=items.filter {OfflineAccess.allows(it)}
            when {
                wanted(items) && !running -> runCatching {
                    running = true
                    ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
                }.onFailure {
                    // Started from the background (Android 12+): the queue waits for the app.
                    running = false
                    AppLog.w("Downloads", "couldn't start the download service: ${it.message}")
                }
                wanted(items) -> DownloadNotifications.update(items)
                running -> context.stopService(Intent(context, DownloadService::class.java))
            }
        }
    }
}
