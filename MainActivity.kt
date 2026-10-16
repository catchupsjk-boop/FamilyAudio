package com.jk.familyaudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
    private var pendingSetup = false
    private var setupShown = false
    private lateinit var listenBtn: Button
    private lateinit var keepBtn: Button

    // Native heartbeat: browser timers get slowed down when the screen is off, so Android pokes the page instead.
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (AudioForegroundService.running || listenMode) {
                try { webView.evaluateJavascript("window.__nativeTick&&window.__nativeTick()", null) } catch (_: Exception) {}
            }
            handler.postDelayed(this, 3000)
        }
    }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (pendingSetup) { pendingSetup = false; openKeepAliveSettings(); return@registerForActivityResult }
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
        keepBtn = findViewById(R.id.keepAliveButton)
        keepBtn.setOnClickListener { openKeepAliveSettings() }
        webView = findViewById(R.id.webView)

        val prefs = getSharedPreferences("family", MODE_PRIVATE)
        linkInput.setText(prefs.getString("link", ""))
        if (prefs.getString("pw", "").orEmpty().isNotEmpty())
            passwordInput.hint = "Password saved (type a new one to change it)"

        setupWebView()
        handler.postDelayed(ticker, 3000)

        startBtn.setOnClickListener { requestAndStart() }
        stopBtn.setOnClickListener { if (listenMode) stopListenMode() else stopSharing() }
        listenBtn.setOnClickListener {
            // microphone is only needed for the Talk button; notifications show the "listening" bar
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
            if (needed.isEmpty()) startListenMode()
            else { pendingListen = true; permissions.launch(needed.toTypedArray()) }
        }

        // Back button: keep sharing, just go to the home screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // while sharing OR listening: keep going in the background, just go home
                if (listenMode || AudioForegroundService.running) moveTaskToBack(true) else finish()
            }
        })

        // Auto-connect: if a link is saved and sharing was on, start again by itself on open
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasPw = prefs.getString("pw", "").orEmpty().length >= 8
        if (hasPw && hasMic && ((AudioForegroundService.running && AudioForegroundService.mode == AudioForegroundService.MODE_SHARE) || prefs.getBoolean("autostart", false))) {
            beginSharing()
        } else if (prefs.getBoolean("listenerPhone", false)) {
            startListenMode()      // this phone is the listener: open straight into listening
        } else render(false)

        handler.post { checkBackgroundSetup() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)   // lets you inspect the page from a PC (chrome://inspect)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(AudioBridge(), "AndroidAudio")
        // keep the page's engine at high priority even when the screen is off
        if (Build.VERSION.SDK_INT >= 26) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

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
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Family Audio link", text))
                    Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { }
            }
        }

        /** Open the normal Android share sheet (WhatsApp, Messages...). */
        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    startActivity(Intent.createChooser(i, "Share link"))
                } catch (e: Exception) { }
            }
        }

        /** True when wired / USB / Bluetooth earphones are connected. */
        @JavascriptInterface
        fun hasHeadset(): Boolean = try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val types = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        } catch (e: Exception) { false }

        /** Vibrate this phone only (no sound). */
        @JavascriptInterface
        fun vibrate(ms: Long) { runOnUiThread { doVibrate(ms) } }

        /** The page's "Turn Off" button: stop the background service and close the page. */
        @JavascriptInterface
        fun turnOff() { runOnUiThread { stopSharing() } }

        /** The listening password, set by the owner in this app. Only our own page can reach this. */
        @JavascriptInterface
        fun getPassword(): String =
            getSharedPreferences("family", MODE_PRIVATE).getString("pw", "") ?: ""

        /** Security alert shown as a normal phone notification (wrong password, second device...). */
        @JavascriptInterface
        fun alertOwner(title: String, text: String) { runOnUiThread { showAlert(title, text) } }
    }

    @Suppress("DEPRECATION")
    private fun doVibrate(ms: Long) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(100, 5000), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
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

    /** Any audio-routing problem must never crash the app. */
    private var focusReq: AudioFocusRequest? = null

    private fun routeAudio(mode: String) {
        try { routeAudioUnsafe(mode) } catch (e: Exception) { }
        // shows in the page's "Technical details" so you can see what Android actually did
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val dev = if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.type else null
            webView.evaluateJavascript("dlog('route=$mode amMode=${am.mode} device=$dev')", null)
        } catch (e: Exception) { }
    }

    /** Ask Android to treat our playback as a voice call, so the call route (earpiece / speaker) is honoured. */
    private fun takeCallFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT < 26 || focusReq != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focusReq = req
    }

    private fun dropCallFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 26) focusReq?.let { am.abandonAudioFocusRequest(it) }
        focusReq = null
    }

    @Suppress("DEPRECATION")
    private fun routeAudioUnsafe(mode: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        if (mode == "phone" || mode == "speaker" || mode == "headset") takeCallFocus(am)
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
                dropCallFocus(am)
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private fun requestAndStart() {
        val prefsNow = getSharedPreferences("family", MODE_PRIVATE)
        val typedPw = passwordInput.text.toString()
        val savedPw = prefsNow.getString("pw", "").orEmpty()
        val finalPw = if (typedPw.isNotEmpty()) typedPw else savedPw
        if (finalPw.length < 8) {
            status.text = "Set a password of at least 8 characters. The listener types the same password. Make it unique (a short phrase), because the password is what connects the two phones."
            return
        }
        prefsNow.edit().putString("pw", finalPw).apply()
        passwordInput.setText("")

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) beginSharing() else permissions.launch(needed.toTypedArray())
    }

    // Must be called while the app is visible (Android 12+ background-start rule)
    private fun beginSharing() {
        val query = "role=share"
        ContextCompat.startForegroundService(this,
            Intent(this, AudioForegroundService::class.java)
                .setAction(AudioForegroundService.ACTION_START))
        getSharedPreferences("family", MODE_PRIVATE).edit()
            .putBoolean("autostart", true).putBoolean("listenerPhone", false).apply()
        ensureBatteryExempt()
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
        getSharedPreferences("family", MODE_PRIVATE).edit().putBoolean("listenerPhone", true).apply()
        webView.visibility = View.VISIBLE
        linkInput.visibility = View.GONE
        passwordInput.visibility = View.GONE
        startBtn.visibility = View.GONE
        listenBtn.visibility = View.GONE
        keepBtn.visibility = View.GONE
        stopBtn.visibility = View.VISIBLE
        stopBtn.text = "⏹ Stop Listening"
        status.text = "🎧 Listening — you can lock the phone or switch apps"
        // keeps the app alive (and the sound playing) while the screen is off
        try {
            ContextCompat.startForegroundService(this,
                Intent(this, AudioForegroundService::class.java)
                    .setAction(AudioForegroundService.ACTION_START)
                    .putExtra(AudioForegroundService.EXTRA_MODE, AudioForegroundService.MODE_LISTEN))
        } catch (e: Exception) {
            status.text = "Listening works, but it may stop when the screen turns off."
        }
        ensureBatteryExempt()
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun stopListenMode() {
        listenMode = false
        getSharedPreferences("family", MODE_PRIVATE).edit().putBoolean("listenerPhone", false).apply()
        startService(Intent(this, AudioForegroundService::class.java)
            .setAction(AudioForegroundService.ACTION_STOP))
        webView.loadUrl("about:blank")
        routeAudio("reset")
        stopBtn.text = "⏹ Stop & Close"
        render(false)
    }

    /** First launch (and every launch until allowed): ask for the permissions the app needs to keep working with the screen off. */
    private fun checkBackgroundSetup() {
        if (setupShown || isFinishing) return
        setupShown = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (exempt && needed.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Allow background running")
            .setMessage("Family Audio must keep running when the screen is off or the app is closed to the background. " +
                "Please allow:\n\n• Notifications (shows the \"Family Audio is on\" bar)\n• Microphone\n" +
                "• Run in background (no battery limit)\n\nOn Xiaomi, Oppo, Vivo, Realme or Samsung phones, also turn on " +
                "\"Autostart\" and set Battery to \"Unrestricted\" in the app's settings page.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                if (needed.isNotEmpty()) { pendingSetup = true; permissions.launch(needed.toTypedArray()) }
                else openKeepAliveSettings()
            }
            .setNeutralButton("App settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Button: lets the phone owner allow the app to keep running with the screen off. */
    @SuppressLint("BatteryLife")
    private fun openKeepAliveSettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Battery is already allowed. Also set Battery to \"Unrestricted\" and allow Autostart here.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) { }
    }

    /** Asks (at most twice) to be excluded from battery optimisation so audio keeps flowing with the screen off. */
    @SuppressLint("BatteryLife")
    private fun ensureBatteryExempt() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val prefs = getSharedPreferences("family", MODE_PRIVATE)
            val n = prefs.getInt("battAsked", 0)
            if (n >= 2) return
            prefs.edit().putInt("battAsked", n + 1).apply()
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (e: Exception) { }
    }

    private fun render(on: Boolean) {
        listenBtn.visibility = if (on) View.GONE else View.VISIBLE
        keepBtn.visibility = if (on) View.GONE else View.VISIBLE
        webView.visibility = if (on) View.VISIBLE else View.GONE
        linkInput.visibility = View.GONE
        passwordInput.visibility = if (on) View.GONE else View.VISIBLE
        startBtn.visibility = if (on) View.GONE else View.VISIBLE
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        status.text = if (on) "🟢 Family Audio is ON — the microphone runs only while your listener is connected. You can lock the phone."
                      else "Set a password, then tap Turn On. The listener types the same password to connect."
    }

    // Keep the page (and its microphone/WebRTC) running when the screen locks
    override fun onPause() { super.onPause(); webView.resumeTimers() }
    override fun onStop() { super.onStop(); webView.onResume(); webView.resumeTimers() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            startService(Intent(this, AudioForegroundService::class.java)
                .setAction(AudioForegroundService.ACTION_STOP))
            webView.destroy()
        }
        super.onDestroy()
    }
}


/** A WebView that always reports "visible", so Chrome does not slow down or freeze the page when the screen turns off. */
class AlwaysVisibleWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    WebView(context, attrs) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
