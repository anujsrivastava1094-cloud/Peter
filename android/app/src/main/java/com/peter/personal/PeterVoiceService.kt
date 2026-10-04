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
    private var awake = false
    private var sleepingForSpeech = false

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
        if (sleepingForSpeech) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        handler.post {
            if (sleepingForSpeech) return@post
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
            scheduleRestart(220)
        }

        override fun onError(error: Int) {
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 500)
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val transcript = texts.firstOrNull().orEmpty()
            if (transcript.isNotBlank()) handleTranscript(transcript)
            scheduleRestart(280)
        }
    }

    private fun normalize(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace("पीटर", "peter")
            .replace("पीटार", "peter")
            .replace("पीटर्", "peter")
            .replace("peta", "peter")
            .replace("peeter", "peter")
            .replace(Regex("[,!?;:.]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stripWakeWord(text: String): String {
        return text.replaceFirst(Regex("^.*?\\bpeter\\b\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isWakeWord(text: String): Boolean =
        Regex("(^|\\s)peter(\\s|$)").containsMatchIn(text)

    private fun isSleepCommand(text: String): Boolean {
        return Regex("\\b(?:sleep|sleeping time|go to sleep|stop listening|good night|goodnight)\\b").containsMatchIn(text) ||
            Regex("\\b(?:band|so jao|sojao)\\b").containsMatchIn(text)
    }

    private fun handleTranscript(raw: String) {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return

        if (!awake) {
            if (!isWakeWord(normalized)) return

            val now = System.currentTimeMillis()
            if (now - lastWakeAt < 1600) return
            lastWakeAt = now

            awake = true
            val command = stripWakeWord(normalized)
            publish(command, wakeOnly = command.isBlank())
            return
        }

        // Once awake, PETER no longer requires the wake word for every command.
        if (isSleepCommand(normalized) ||
            (isWakeWord(normalized) && isSleepCommand(stripWakeWord(normalized)))) {
            awake = false
            publish("sleep", wakeOnly = false)
            return
        }

        val command = if (isWakeWord(normalized)) stripWakeWord(normalized) else normalized
        if (command.isBlank()) return
        publish(command, wakeOnly = false)
    }

    private fun publish(command: String, wakeOnly: Boolean) {
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(MainActivity.PENDING_COMMAND, command)
            .putBoolean(MainActivity.PENDING_WAKE_ONLY, wakeOnly)
            .apply()

        sendBroadcast(Intent(MainActivity.ACTION_PETER_COMMAND).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_COMMAND, command)
            putExtra(MainActivity.EXTRA_WAKE_ONLY, wakeOnly)
        })

        // Bring PETER up only when the wake word is first heard.
        // After wake, commands are delivered to the already-running activity
        // without repeatedly opening the screen.
        if (wakeOnly) {
            try {
                val launch = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(launch)
            } catch (_: Exception) {
                // Pending state remains available for the next app launch.
            }
        }
    }

    private fun scheduleRestart(delay: Long) {
        if (restarting || sleepingForSpeech) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (!sleepingForSpeech) startListening()
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
            .setContentText("Say “Peter” to wake PETER. Say “sleeping time” to stop.")
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
