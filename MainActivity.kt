package com.jk.familyaudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {

    private lateinit var linkInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var webView: WebView

    private var pendingQuery: String? = null
    private var autoClicked = false
    private var listenMode = false
    private var pendingListen = false
    private lateinit var listenBtn: Button

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (pendingListen) { pendingListen = false; startListenMode() }
            else if (r[Manifest.permission.RECORD_AUDIO] == true) beginSharing()
            else status.text = "Microphone permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        linkInput = findViewById(R.id.linkInput)
        passwordInput = findViewById(R.id.passwordInput)
        status = findViewById(R.id.status)
        startBtn = findViewById(R.id.startButton)
        listenBtn = findViewById(R.id.listenButton)
        stopBtn = findViewById(R.id.stopButton)
        webView = findViewById(R.id.webView)

        val prefs = getSharedPreferences("family", MODE_PRIVATE)
        linkInput.setText(prefs.getString("link", ""))
        if (prefs.getString("pw", "").orEmpty().isNotEmpty())
            passwordInput.hint = "Password saved (type a new one to change it)"

        setupWebView()

        startBtn.setOnClickListener { requestAndStart() }
        stopBtn.setOnClickListener { if (listenMode) stopListenMode() else stopSharing() }
        listenBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingListen = true   // microphone is only needed for the Talk button
                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            } else startListenMode()
        }

        // Back button: keep sharing, just go to the home screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (listenMode) stopListenMode()
                else if (AudioForegroundService.running) moveTaskToBack(true) else finish()
            }
        })

        // Auto-connect: if a link is saved and sharing was on, start again by itself on open
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val saved = prefs.getString("link", "").orEmpty().isNotBlank()
        val hasPw = prefs.getString("pw", "").orEmpty().length >= 8
        if (saved && hasPw && hasMic && (AudioForegroundService.running || prefs.getBoolean("autostart", false))) {
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

        /** Copy text to the clipboard (the browser clipboard is often blocked inside the app). */
        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Family Audio link", text))
                Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }

        /** Open the normal Android share sheet (WhatsApp, Messages...). */
        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(i, "Share link"))
            }
        }

        /** True when wired / USB / Bluetooth earphones are connected. */
        @JavascriptInterface
        fun hasHeadset(): Boolean {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val types = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        }

        /** The listening password, set by the owner in this app. Only our own page can reach this. */
        @JavascriptInterface
        fun getPassword(): String =
            getSharedPreferences("family", MODE_PRIVATE).getString("pw", "") ?: ""

        /** Security alert shown as a normal phone notification (wrong password, second device...). */
        @JavascriptInterface
        fun alertOwner(title: String, text: String) { runOnUiThread { showAlert(title, text) } }
    }

    private fun showAlert(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel("security", "Security alerts", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Warns you about wrong passwords and second devices"
            nm.createNotificationChannel(ch)
            val open = PendingIntent.getActivity(this, 2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, "security")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt() + 2000, n)
        } catch (e: Exception) { }
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
            "headset" -> {          // wired / USB / Bluetooth earphones
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val types = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET)
                    val dev = am.availableCommunicationDevices.firstOrNull { it.type in types }
                    if (dev != null) am.setCommunicationDevice(dev) else am.clearCommunicationDevice()
                } else {
                    am.isSpeakerphoneOn = false
                }
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxCall * 0.7).toInt().coerceAtLeast(1), 0)
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
        val prefsNow = getSharedPreferences("family", MODE_PRIVATE)
        val typedPw = passwordInput.text.toString()
        val savedPw = prefsNow.getString("pw", "").orEmpty()
        val finalPw = if (typedPw.isNotEmpty()) typedPw else savedPw
        if (finalPw.length < 8) {
            status.text = "Set a password of at least 8 characters. The listener must type it."
            return
        }
        prefsNow.edit()
            .putString("link", linkInput.text.toString().trim())
            .putString("pw", finalPw).apply()
        passwordInput.setText("")

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
        getSharedPreferences("family", MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
        autoClicked = false
        pendingQuery = query
        render(true)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html?$query")
    }

    private fun stopSharing() {
        startService(Intent(this, AudioForegroundService::class.java)
            .setAction(AudioForegroundService.ACTION_STOP))
        getSharedPreferences("family", MODE_PRIVATE).edit().putBoolean("autostart", false).apply()
        webView.loadUrl("about:blank")
        routeAudio("reset")
        render(false)
    }

    /** "Listen on this phone": opens the listener page inside the app so the sound can be routed. */
    private fun startListenMode() {
        listenMode = true
        webView.visibility = View.VISIBLE
        linkInput.visibility = View.GONE
        passwordInput.visibility = View.GONE
        startBtn.visibility = View.GONE
        listenBtn.visibility = View.GONE
        stopBtn.visibility = View.VISIBLE
        stopBtn.text = "⬅ Back"
        status.text = "🎧 Listening mode — choose where to hear below"
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun stopListenMode() {
        listenMode = false
        webView.loadUrl("about:blank")
        routeAudio("reset")
        stopBtn.text = "⏹ Stop & Close"
        render(false)
    }

    private fun render(on: Boolean) {
        listenBtn.visibility = if (on) View.GONE else View.VISIBLE
        webView.visibility = if (on) View.VISIBLE else View.GONE
        linkInput.visibility = if (on) View.GONE else View.VISIBLE
        passwordInput.visibility = if (on) View.GONE else View.VISIBLE
        startBtn.visibility = if (on) View.GONE else View.VISIBLE
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        status.text = if (on) "🔴 Sharing — you can lock the phone or switch apps"
                      else "Paste the share link, set a password, then tap Start"
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
