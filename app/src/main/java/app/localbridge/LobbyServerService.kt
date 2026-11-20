package app.localbridge

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

/** Keeps the local-network receiving endpoint and mDNS advertisement alive while the UI is backgrounded. */
class LobbyServerService : Service() {
    companion object {
        @Volatile var activeServer: LocalBridgeServer? = null
            private set
        @Volatile var uiListener: (() -> Unit)? = null
        private const val NOTIFICATION_ID = 4105
    }

    private var discovery: LobbyDiscovery? = null

    override fun onCreate() {
        super.onCreate()
        LobbyNotifications.createChannel(this)
        val notification = NotificationCompat.Builder(this, "lobby_transfers")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("LoBBY is receiving")
            .setContentText("This device is discoverable on your local network")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startLocalServer()
        } catch (error: Exception) {
            android.util.Log.e("LoBBY", "Could not start receiving service", error)
            stopSelf(startId)
        }
        return START_STICKY
    }

    private fun startLocalServer() {
        if (activeServer != null) return
        val prefs = getSharedPreferences("localbridge", MODE_PRIVATE)
        val allFilesMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && prefs.getBoolean("all_files_mode", false)
        if (allFilesMode && !Environment.isExternalStorageManager()) throw IllegalStateException("All Files Access permission is missing")
        val fileRoot = if (allFilesMode) File(prefs.getString("file_root", "/storage/emulated/0") ?: "/storage/emulated/0") else null
        if (fileRoot != null && (!fileRoot.exists() || !fileRoot.isDirectory)) throw IllegalStateException("Selected shared folder is unavailable")
        val sharedUri = if (allFilesMode) null else prefs.getString("shared_uri", null)?.let(Uri::parse)
        val websiteUri = prefs.getString("website_uri", null)?.let(Uri::parse)
        val sharePath = prefs.getString("share_path", "/files") ?: "/files"
        val server = LocalBridgeServer(
            this,
            sharedUri,
            websiteUri,
            sharePath,
            fileRoot,
            onTransferChanged = { uiListener?.invoke() }
        )
        server.start()
        activeServer = server
        val advertiser = LobbyDiscovery(this)
        advertiser.advertise(8765, "LoBBY-${Build.MODEL}", sharePath, LobbyIdentity.get(this).deviceId)
        discovery = advertiser
        uiListener?.invoke()
    }

    override fun onDestroy() {
        activeServer?.stop()
        activeServer = null
        discovery?.stop()
        discovery = null
        uiListener?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
