package com.jk.familyaudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    private lateinit var linkInput: EditText
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var webView: WebView

    private var pendingQuery: String? = null
    private var autoClicked = false

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.RECORD_AUDIO] == true) beginSharing()
            else status.text = "Microphone permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        linkInput = findViewById(R.id.linkInput)
        status = findViewById(R.id.status)
        startBtn = findViewById(R.id.startButton)
        stopBtn = findViewById(R.id.stopButton)
        webView = findViewById(R.id.webView)

        val prefs = getSharedPreferences("family", MODE_PRIVATE)
        linkInput.setText(prefs.getString("link", ""))

        setupWebView()

        startBtn.setOnClickListener { requestAndStart() }
        stopBtn.setOnClickListener { stopSharing() }

        // Back button: keep sharing, just go to the home screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AudioForegroundService.running) moveTaskToBack(true) else finish()
            }
        })

        // Re-open from the notification while the service is already running
        if (AudioForegroundService.running && prefs.getString("link", "").orEmpty().isNotBlank()) {
            beginSharing()
        } else render(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(AudioBridge(), "AndroidAudio")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                // Automatically press "Start Sharing" once the page has loaded
                if (!autoClicked && url.contains("role=share")) {
                    autoClicked = true
                    view.evaluateJavascript(
                        "document.getElementById('start-sharing-btn').click();", null)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    /** Lets the page choose where incoming "Talk" voice plays. */
    inner class AudioBridge {
        @JavascriptInterface
        fun setOutput(mode: String) { runOnUiThread { routeAudio(mode) } }
    }

    @Suppress("DEPRECATION")
    private fun routeAudio(mode: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        when (mode) {
            "phone" -> {            // quiet, front earpiece
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val dev = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (dev != null) am.setCommunicationDevice(dev) else am.clearCommunicationDevice()
                } else {
                    am.isSpeakerphoneOn = false
                }
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxCall * 0.6).toInt().coerceAtLeast(1), 0)
            }
            "speaker" -> {          // loud, bottom loudspeaker
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val dev = am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (dev != null) am.setCommunicationDevice(dev)
                } else {
                    am.isSpeakerphoneOn = true
                }
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0)
            }
            else -> {               // back to normal
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private fun requestAndStart() {
        val query = extractQuery(linkInput.text.toString())
        if (query == null) {
            status.text = "That link doesn't look right. Paste the full share link."
            return
        }
        getSharedPreferences("family", MODE_PRIVATE).edit()
            .putString("link", linkInput.text.toString().trim()).apply()

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) beginSharing() else permissions.launch(needed.toTypedArray())
    }

    /** Pull "role=share&to=...&name=..." out of the pasted link. */
    private fun extractQuery(link: String): String? {
        val uri = try { Uri.parse(link.trim()) } catch (e: Exception) { return null }
        val to = uri.getQueryParameter("to") ?: return null
        val sb = StringBuilder("role=share&to=").append(Uri.encode(to))
        uri.getQueryParameter("name")?.let { sb.append("&name=").append(Uri.encode(it)) }
        return sb.toString()
    }

    // Must be called while the app is visible (Android 12+ background-start rule)
    private fun beginSharing() {
        val query = extractQuery(
            getSharedPreferences("family", MODE_PRIVATE).getString("link", "").orEmpty()
        ) ?: return
        ContextCompat.startForegroundService(this,
            Intent(this, AudioForegroundService::class.java)
                .setAction(AudioForegroundService.ACTION_START))
        autoClicked = false
        pendingQuery = query
        render(true)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html?$query")
    }

    private fun stopSharing() {
        startService(Intent(this, AudioForegroundService::class.java)
            .setAction(AudioForegroundService.ACTION_STOP))
        webView.loadUrl("about:blank")
        routeAudio("reset")
        render(false)
    }

    private fun render(on: Boolean) {
        webView.visibility = if (on) View.VISIBLE else View.GONE
        linkInput.visibility = if (on) View.GONE else View.VISIBLE
        startBtn.visibility = if (on) View.GONE else View.VISIBLE
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        status.text = if (on) "🔴 Sharing — you can lock the phone or switch apps"
                      else "Paste the share link, then tap Start"
    }

    // Keep the page (and its microphone/WebRTC) running when the screen locks
    override fun onPause() { super.onPause(); webView.resumeTimers() }
    override fun onStop() { super.onStop(); webView.onResume(); webView.resumeTimers() }

    override fun onDestroy() {
        if (isFinishing) {
            startService(Intent(this, AudioForegroundService::class.java)
                .setAction(AudioForegroundService.ACTION_STOP))
            webView.destroy()
        }
        super.onDestroy()
    }
}
