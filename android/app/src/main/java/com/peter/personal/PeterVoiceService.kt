package com.peter.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

class PeterVoiceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var restarting = false
    private var lastWakeAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recognizer == null) startListening()
        return START_STICKY
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        handler.post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(listener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                recognizer?.startListening(intent)
            } catch (_: Exception) {
                scheduleRestart(1000)
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onEndOfSpeech() {
            scheduleRestart(250)
        }

        override fun onError(error: Int) {
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 600)
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val transcript = texts.firstOrNull().orEmpty()
            if (transcript.isNotBlank()) handleTranscript(transcript)
            scheduleRestart(300)
        }
    }

    private fun handleTranscript(raw: String) {
        val normalized = raw.lowercase(Locale.ROOT)
            .replace("पीटर", "peter")
            .replace("पीटार", "peter")
            .replace("peta", "peter")
            .trim()

        if (!Regex("(^|\\s)peter(\\s|$)").containsMatchIn(normalized)) return

        val now = System.currentTimeMillis()
        if (now - lastWakeAt < 1800) return
        lastWakeAt = now

        val command = normalized
            .replaceFirst(Regex("^.*?\\bpeter\\b\\s*"), "")
            .trim()

        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(MainActivity.PENDING_COMMAND, command)
            .putBoolean(MainActivity.PENDING_WAKE_ONLY, command.isBlank())
            .apply()

        sendBroadcast(Intent(MainActivity.ACTION_PETER_COMMAND).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_COMMAND, command)
        })

        try {
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(launch)
        } catch (_: Exception) {
            // Android may block background activity launches on some versions.
            // The pending command remains stored for the next visible app launch.
        }
    }

    private fun scheduleRestart(delay: Long) {
        if (restarting) return
        restarting = true
        handler.postDelayed({
            restarting = false
            startListening()
        }, delay)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "PETER Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible notification while PETER voice mode is active."
                setShowBadge(false)
            }
        )
    }

    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PETER voice mode is active")
            .setContentText("Microphone access is active. Tap to open PETER.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "peter_voice"
        private const val NOTIFICATION_ID = 7301
    }
}
