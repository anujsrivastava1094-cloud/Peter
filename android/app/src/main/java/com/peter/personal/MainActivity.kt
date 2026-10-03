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
            if (command.isNotBlank()) {
                runPeterCommand(command)
            } else {
                showPeterListening()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return request?.url?.host != "anujsrivastava1094-cloud.github.io"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                disableWebSpeechRecognition()
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

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        consumePendingCommand()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(voiceReceiver)
        } catch (_: Exception) {
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

    private fun showPeterListening() {
        webView.evaluateJavascript(
            """
            (function(){
                try {
                    if (typeof peterAwake !== 'undefined') peterAwake = true;
                    if (typeof peterListening !== 'undefined') peterListening = true;
                    if (typeof openPeterVoiceMode === 'function') openPeterVoiceMode();
                    if (typeof peterSpeak === 'function') peterSpeak("I'm listening.");
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
                    if (typeof openPeterVoiceMode === 'function') openPeterVoiceMode();
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

        if (command.isNullOrBlank()) showPeterListening() else runPeterCommand(command)
    }

    companion object {
        const val ACTION_PETER_COMMAND = "com.peter.personal.PETER_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val PREFS = "peter_native_voice"
        const val PENDING_COMMAND = "pending_command"
        const val PENDING_WAKE_ONLY = "pending_wake_only"
        const val PETER_URL = "https://anujsrivastava1094-cloud.github.io/Peter/"
    }
}
