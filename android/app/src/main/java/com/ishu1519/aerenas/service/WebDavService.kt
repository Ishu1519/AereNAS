package com.ishu1519.aerenas.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.ishu1519.aerenas.R
import com.ishu1519.aerenas.server.WebDavServer
import com.ishu1519.aerenas.ui.MainActivity
import com.ishu1519.aerenas.utils.Prefs
import java.net.InetAddress
import java.text.DecimalFormat

class WebDavService : LifecycleService() {

    companion object {
        const val TAG = "WebDavService"
        const val NOTIF_CHANNEL_ID = "aerenas_service"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.ishu1519.aerenas.START"
        const val ACTION_STOP  = "com.ishu1519.aerenas.STOP"

        // Broadcast actions to update UI
        const val BROADCAST_STATUS  = "com.ishu1519.aerenas.STATUS"
        const val EXTRA_RUNNING     = "running"
        const val EXTRA_IP          = "ip"
        const val EXTRA_SPEED       = "speed"
        const val EXTRA_TOTAL_BYTES = "total_bytes"
        const val EXTRA_LOG_EVENT   = "log_event"

        var isRunning = false
            private set
    }

    private var server: WebDavServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP  -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning) return

        val prefs = Prefs(this)
        val port = prefs.port
        val rootPath = prefs.rootPath
        val username = prefs.username
        val password = prefs.password

        server = WebDavServer(
            port = port,
            rootPath = rootPath,
            username = username,
            password = password,
            onTransferUpdate = { speed, total ->
                broadcastStatus(speed = speed, totalBytes = total)
                updateNotification(speed)
            },
            onConnectionEvent = { event ->
                broadcastStatus(logEvent = event)
            }
        )

        try {
            server!!.start()
            isRunning = true
            acquireWakeLock()
            startForeground(NOTIF_ID, buildNotification("Running on port $port"))
            broadcastStatus(running = true, ip = getLocalIpAddress())
            Log.i(TAG, "WebDAV server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            isRunning = false
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        isRunning = false
        releaseWakeLock()
        broadcastStatus(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "WebDAV server stopped")
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AereNAS::ServerLock").apply {
            acquire(24 * 60 * 60 * 1000L) // 24h max, released on stop
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun getLocalIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                InetAddress.getByAddress(
                    byteArrayOf(
                        (ip and 0xff).toByte(),
                        (ip shr 8 and 0xff).toByte(),
                        (ip shr 16 and 0xff).toByte(),
                        (ip shr 24 and 0xff).toByte()
                    )
                ).hostAddress ?: getIpFromNetworkInterfaces()
            } else {
                getIpFromNetworkInterfaces()
            }
        } catch (e: Exception) {
            getIpFromNetworkInterfaces()
        }
    }

    private fun getIpFromNetworkInterfaces(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun buildNotification(contentText: String): Notification {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WebDavService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("AereNAS")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_nas)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(speedBps: Long) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIF_ID, buildNotification("Active • ${formatSpeed(speedBps)}"))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "AereNAS Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "WebDAV server status"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    private fun broadcastStatus(
        running: Boolean? = null,
        ip: String? = null,
        speed: Long? = null,
        totalBytes: Long? = null,
        logEvent: String? = null
    ) {
        val intent = Intent(BROADCAST_STATUS).apply {
            running?.let    { putExtra(EXTRA_RUNNING, it) }
            ip?.let         { putExtra(EXTRA_IP, it) }
            speed?.let      { putExtra(EXTRA_SPEED, it) }
            totalBytes?.let { putExtra(EXTRA_TOTAL_BYTES, it) }
            logEvent?.let   { putExtra(EXTRA_LOG_EVENT, it) }
        }
        sendBroadcast(intent)
    }

    private fun formatSpeed(bps: Long): String {
        val df = DecimalFormat("#.##")
        return when {
            bps >= 1_000_000 -> "${df.format(bps / 1_000_000.0)} MB/s"
            bps >= 1_000     -> "${df.format(bps / 1_000.0)} KB/s"
            else             -> "$bps B/s"
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
