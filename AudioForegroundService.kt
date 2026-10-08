package com.jk.familyaudio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app process alive with the "microphone" foreground-service type
 * so the WebView (which does the WebRTC/PeerJS work) can keep capturing the
 * mic while the screen is locked or the app is minimised.
 */
class AudioForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_sharing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START_AUDIO"
        const val ACTION_STOP = "STOP_AUDIO"
        const val EXTRA_MODE = "mode"
        const val MODE_SHARE = "share"     // speaker phone: microphone is shared
        const val MODE_LISTEN = "listen"   // listener phone: keeps listening with the screen off

        @Volatile var mode = MODE_SHARE

        @Volatile var running = false
        @Volatile var stateListener: ((Boolean) -> Unit)? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val newMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SHARE
        // Only the speaker (sharing) needs the microphone permission; listening does not.
        if (newMode == MODE_SHARE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        mode = newMode

        createChannel()
        // Android crashes the whole app if a service started with startForegroundService() never
        // reaches startForeground(). So we try several service types and never give up early.
        val notification = buildNotification()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val types = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= 30) {
            if (newMode == MODE_LISTEN) {
                types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (hasMic) types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE   // works with an older manifest
            } else {
                types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            types += 0
        }
        var started = false
        for (t in types) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, t)
                started = true; break
            } catch (e: Exception) { /* try the next type */ }
        }
        if (!started) {
            try { startForeground(NOTIFICATION_ID, notification); started = true } catch (e: Exception) { }
        }
        if (!started) { stopSelf(); return START_NOT_STICKY }

        acquireLocks()
        running = true
        stateListener?.invoke(true)
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "familyaudio:mic").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "familyaudio:wifi").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
    }

    private fun shutdown() {
        releaseLocks()
        running = false
        stateListener?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Audio Sharing", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows when audio sharing or listening is active"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AudioForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val listening = mode == MODE_LISTEN
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (listening) "🎧 Listening to family audio" else "🟢 Family Audio is on")
            .setContentText(if (listening) "Listening continues with the screen off"
                            else "The microphone turns on only while your listener is connected")
            .setSmallIcon(if (listening) android.R.drawable.ic_media_play else android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        if (running) { running = false; stateListener?.invoke(false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
