package com.peter.personal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PeterVoiceService extends Service implements TextToSpeech.OnInitListener {
    private static final String CHANNEL_ID = "peter_voice";
    private static final int NOTIFICATION_ID = 701;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean awake = false;
    private boolean speaking = false;
    private boolean restarting = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = buildNotification("PETER is ready");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        tts = new TextToSpeech(this, this);
        setupRecognizer();
        startListeningSoon();
    }

    private void setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition is unavailable on this device");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                updateNotification(awake ? "Listening for PETER commands" : "Waiting for PETER");
            }

            @Override public void onBeginningOfSpeech() {}

            @Override public void onRmsChanged(float rmsdB) {}

            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {}

            @Override public void onError(int error) {
                startListeningSoon();
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = chooseBest(matches);
                if (best != null) {
                    handleSpeech(best);
                }
                startListeningSoon();
            }

            @Override public void onPartialResults(Bundle partialResults) {}

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private String chooseBest(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return null;
        for (String item : matches) {
            if (item != null && item.toLowerCase(Locale.ROOT).contains("peter")) return item;
        }
        return matches.get(0);
    }

    private void startListeningSoon() {
        if (restarting) return;
        restarting = true;

        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            restarting = false;
            if (recognizer == null || speaking) {
                startListeningSoon();
                return;
            }

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            );
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

            try {
                recognizer.startListening(intent);
            } catch (Exception ignored) {
                startListeningSoon();
            }
        }, 350);
    }

    private void handleSpeech(String raw) {
        if (raw == null) return;

        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return;

        if (s.contains("peter stop") || s.equals("stop peter") ||
                s.contains("peter ruk") || s.contains("peter bas")) {
            stopSpeaking();
            return;
        }

        if (s.contains("peter") && (s.contains("sleep") || s.contains("so ja") ||
                s.contains("band karo") || s.contains("band kar") || s.contains("sula do"))) {
            awake = false;
            sendCommandToWeb("sleep");
            speak("Going to sleep.");
            updateNotification("PETER is sleeping");
            return;
        }

        if (!awake) {
            if (s.contains("peter")) {
                awake = true;
                updateNotification("PETER is awake");
                speak("I'm listening.");
                String command = stripWakeWord(raw);
                if (!command.isEmpty()) sendCommandToWeb(command);
            }
            return;
        }

        String command = stripWakeWord(raw);
        if (command.isEmpty()) {
            speak("I'm listening.");
            return;
        }

        sendCommandToWeb(command);

        if (command.contains("what time") || command.contains("time kya") ||
                command.contains("kya time") || command.equals("time")) {
            String time = new java.text.SimpleDateFormat(
                    "h:mm a", Locale.ENGLISH
            ).format(new java.util.Date());
            speak("It is " + time + ".");
        } else if (command.contains("what date") || command.contains("date kya") ||
                command.contains("aaj ki date")) {
            String date = new java.text.SimpleDateFormat(
                    "d MMMM yyyy", Locale.ENGLISH
            ).format(new java.util.Date());
            speak("Today is " + date + ".");
        }
    }

    private String stripWakeWord(String s) {
        return s.replaceAll("(?i)\\bpeter\\b", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void sendCommandToWeb(String command) {
        Intent intent = new Intent("com.peter.personal.PETER_COMMAND");
        intent.setPackage(getPackageName());
        intent.putExtra("command", command);
        sendBroadcast(intent);
    }

    private void speak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        speaking = true;
        updateNotification("PETER speaking");

        tts.setLanguage(Locale.US);
        tts.setSpeechRate(1.02f);
        tts.setPitch(0.72f);

        if (Build.VERSION.SDK_INT >= 21) {
            for (Voice voice : tts.getVoices()) {
                String name = voice.getName().toLowerCase(Locale.ROOT);
                String lang = voice.getLocale().toLanguageTag().toLowerCase(Locale.ROOT);
                if (lang.startsWith("en") &&
                        (name.contains("male") || name.contains("david") ||
                         name.contains("george") || name.contains("daniel") ||
                         name.contains("rishi"))) {
                    tts.setVoice(voice);
                    break;
                }
            }
        }

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PETER");
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            speaking = false;
            startListeningSoon();
            updateNotification(awake ? "PETER is awake" : "PETER is sleeping");
        }, Math.max(900, text.length() * 55L));
    }

    private void stopSpeaking() {
        if (tts != null) tts.stop();
        speaking = false;
        updateNotification(awake ? "PETER is listening" : "PETER is sleeping");
        startListeningSoon();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US);
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("PETER")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle("PETER")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PETER Voice",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("PETER voice assistant foreground service");
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            recognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
