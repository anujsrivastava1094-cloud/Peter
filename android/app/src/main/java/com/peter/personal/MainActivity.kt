package com.peter.personal

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.app.KeyguardManager
import android.webkit.JavascriptInterface
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startPeterVoiceService()
            }
        }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PETER_COMMAND) return
            val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
            val wakeOnly = intent.getBooleanExtra(EXTRA_WAKE_ONLY, false)
            if (wakeOnly) {
                speakPeterWakeOnly()
            } else if (command.isNotBlank()) {
                runPeterCommand(command)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val showSpidy = intent.getBooleanExtra(EXTRA_SHOW_SPIDY, false)
        if (showSpidy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true

        /* Native voice feedback state bridge. The WebView only loads PETER's
           controlled GitHub Pages origin, and external links are opened outside
           the WebView. */
        webView.addJavascriptInterface(PeterWebBridge(this), "PeterNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return if (uri.host == "anujsrivastava1094-cloud.github.io") {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                disableWebSpeechRecognition()
                if (intent.getBooleanExtra(EXTRA_SHOW_SPIDY, false)) {
                    showPeterWake()
                    intent.removeExtra(EXTRA_SHOW_SPIDY)
                }
                consumePendingCommand()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val origin = request?.origin?.host ?: return
                if (origin == "anujsrivastava1094-cloud.github.io") {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        webView.loadUrl(PETER_URL)
        registerVoiceReceiver()

        requestRuntimePermissions()
    }

    private var receiverRegistered = false

    private class PeterWebBridge(private val context: Context) {
        @JavascriptInterface
        fun setSpeechBusy(busy: Boolean) {
            context.sendBroadcast(
                Intent(PeterVoiceService.ACTION_SPEECH_STATE).apply {
                    setPackage(context.packageName)
                    putExtra(PeterVoiceService.EXTRA_SPEECH_BUSY, busy)
                }
            )
        }
    }

    private fun registerVoiceReceiver() {
        if (receiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, IntentFilter(ACTION_PETER_COMMAND), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(voiceReceiver, IntentFilter(ACTION_PETER_COMMAND))
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        consumePendingCommand()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(voiceReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        webView.destroy()
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startPeterVoiceService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startPeterVoiceService() {
        val intent = Intent(this, PeterVoiceService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun disableWebSpeechRecognition() {
        webView.evaluateJavascript(
            """
            (function(){
                window.peterNativeMode = true;
                try {
                    if (typeof peterListening !== 'undefined') peterListening = false;
                    if (typeof peterAwake !== 'undefined') peterAwake = false;
                    if (typeof recognition !== 'undefined' && recognition) recognition.stop();
                } catch(e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun speakPeterWakeOnly() {
        webView.evaluateJavascript(
            """(function(){ try {
                if (typeof peterAwake !== 'undefined') peterAwake = true;
                if (typeof peterListening !== 'undefined') peterListening = true;
                if (typeof sleepRequested !== 'undefined') sleepRequested = false;
                if (typeof peterSpeak === 'function') peterSpeak("Yes, Boss.");
            } catch(e) {} })();""".trimIndent(),
            null
        )
    }

    private fun showPeterWake() {
        webView.evaluateJavascript(
            """
            (function(){
                try {
                    if (typeof peterAwake !== 'undefined') peterAwake = true;
                    if (typeof peterListening !== 'undefined') peterListening = true;
                    if (typeof sleepRequested !== 'undefined') sleepRequested = false;
                    if (typeof openPeterVoiceMode === 'function') openPeterVoiceMode();
                    if (typeof peterSpeak === 'function') peterSpeak("Yes, Boss.");
                } catch(e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun runPeterCommand(command: String) {
        val safe = org.json.JSONObject.quote(command)
        webView.evaluateJavascript(
            """
            (function(){
                try {
                    if (typeof peterAwake !== 'undefined') peterAwake = true;
                    if (typeof peterListening !== 'undefined') peterListening = true;
                    if (typeof peterProcessCommand === 'function') {
                        peterProcessCommand($safe);
                    }
                } catch(e) {
                    console.error(e);
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun consumePendingCommand() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val command = prefs.getString(PENDING_COMMAND, null)
        val wakeOnly = prefs.getBoolean(PENDING_WAKE_ONLY, false)

        if (command.isNullOrBlank() && !wakeOnly) return

        prefs.edit()
            .remove(PENDING_COMMAND)
            .putBoolean(PENDING_WAKE_ONLY, false)
            .apply()

        if (command.isNullOrBlank()) speakPeterWakeOnly() else runPeterCommand(command)
    }

    companion object {
        const val ACTION_PETER_COMMAND = "com.peter.personal.PETER_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_WAKE_ONLY = "wake_only"
        const val EXTRA_SHOW_SPIDY = "show_spidy"
        const val PREFS = "peter_native_voice"
        const val PENDING_COMMAND = "pending_command"
        const val PENDING_WAKE_ONLY = "pending_wake_only"
        const val PETER_URL = "https://anujsrivastava1094-cloud.github.io/Peter/?native=1"
    }
}
