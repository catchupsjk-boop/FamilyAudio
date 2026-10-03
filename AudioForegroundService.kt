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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class AudioForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_sharing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START_AUDIO"
        const val ACTION_STOP = "STOP_AUDIO"

        @Volatile var running = false
        /** Called from a background thread with a level 0..100 */
        @Volatile var levelListener: ((Int) -> Unit)? = null
        @Volatile var stateListener: ((Boolean) -> Unit)? = null
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var capturing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }

        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }

        running = true
        stateListener?.invoke(true)
        startMicrophone()
        return START_NOT_STICKY
    }

    private fun startMicrophone() {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, rate / 5)
        try {
            record = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            record?.startRecording()
        } catch (e: SecurityException) { shutdown(); return }

        capturing = true
        thread = Thread {
            val buf = ShortArray(bufSize / 2)
            while (capturing) {
                val n = record?.read(buf, 0, buf.size) ?: -1
                if (n <= 0) continue
                // === WebRTC hook (Stage 3): send `buf` PCM frames here ===
                var peak = 0
                for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
                levelListener?.invoke((peak * 100 / 32768).coerceIn(0, 100))
            }
        }.also { it.start() }
    }

    private fun stopMicrophone() {
        capturing = false
        try { thread?.join(500) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        thread = null
    }

    private fun shutdown() {
        stopMicrophone()
        running = false
        stateListener?.invoke(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Audio Sharing", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows when microphone audio sharing is active"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AudioForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Audio sharing is active")
            .setContentText("Your microphone is currently being shared")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        if (running) { stopMicrophone(); running = false; stateListener?.invoke(false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
