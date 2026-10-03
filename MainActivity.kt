package com.jk.familyaudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.RECORD_AUDIO] == true) startAudioService()
            else status.text = "Microphone permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        level = findViewById(R.id.level)
        startBtn = findViewById(R.id.startButton)
        stopBtn = findViewById(R.id.stopButton)

        startBtn.setOnClickListener { startAudioSharing() }
        stopBtn.setOnClickListener {
            startService(Intent(this, AudioForegroundService::class.java)
                .setAction(AudioForegroundService.ACTION_STOP))
        }
        render(AudioForegroundService.running)
    }

    override fun onStart() {
        super.onStart()
        AudioForegroundService.levelListener = { v -> runOnUiThread { level.progress = v } }
        AudioForegroundService.stateListener = { on -> runOnUiThread { render(on) } }
        render(AudioForegroundService.running)
    }

    override fun onStop() {
        AudioForegroundService.levelListener = null
        AudioForegroundService.stateListener = null
        super.onStop()
    }

    private fun render(on: Boolean) {
        status.text = if (on) "🔴 Sharing — you can lock the phone" else "Not sharing"
        startBtn.isEnabled = !on
        stopBtn.isEnabled = on
        if (!on) level.progress = 0
    }

    private fun startAudioSharing() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) startAudioService() else permissions.launch(needed.toTypedArray())
    }

    // Must be called while the app is visible (Android 12+ background-start rule)
    private fun startAudioService() {
        ContextCompat.startForegroundService(this,
            Intent(this, AudioForegroundService::class.java)
                .setAction(AudioForegroundService.ACTION_START))
    }
}
