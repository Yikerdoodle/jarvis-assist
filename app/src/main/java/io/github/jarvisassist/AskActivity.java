package io.github.jarvisassist;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one place you ask anything: type or speak, pick an engine (or leave it on Auto), and the
 * answer streams in and is read aloud. Opened by the Quick Settings tile, the launcher icon, or the
 * assistant gesture if this app is chosen as the default assistant.
 */
public class AskActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final String[] ENGINE_IDS = {"auto", "claude", "cloud", "local"};
    private static final String[] ENGINE_LABELS = {"Auto", "Claude", "Free cloud", "On-device"};
    private static final boolean[] ENGINE_READY = {true, true, false, false};

    private Prefs prefs;
    private final BridgeClient bridge = new BridgeClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<JSONObject> history = new ArrayList<>();

    private TextView status;
    private TextView transcript;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private Button mic;
    private CheckBox speakBox;
    private RadioGroup engineGroup;

    private SpeechRecognizer recognizer;
    private boolean recognizerOnDevice;
    private boolean retriedWithDefault;
    private TextToSpeech tts;
    private boolean ttsReady;

    private boolean busy;
    private boolean listening;
    private String transcriptBase = "";
    private final StringBuilder reply = new StringBuilder();
    private final StringBuilder pendingSpeech = new StringBuilder();
    private String lastEngineUsed = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();
        tts = new TextToSpeech(this, code -> ttsReady = code == TextToSpeech.SUCCESS);
        if (savedInstanceState == null && getIntent().getBooleanExtra("voice", false)) {
            ui.postDelayed(this::startListening, 350);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("voice", false)) {
            startListening();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        bridge.cancel();
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        worker.shutdownNow();
        probe.shutdownNow();
        super.onDestroy();
    }

    // ---------- UI ----------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextSize(13);
        top.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button fresh = new Button(this);
        fresh.setText("New");
        fresh.setOnClickListener(v -> newChat());
        top.addView(fresh);
        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings);
        root.addView(top);

        engineGroup = new RadioGroup(this);
        engineGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < ENGINE_IDS.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(ENGINE_LABELS[i]);
            button.setTextSize(13);
            button.setTag(ENGINE_IDS[i]);
            button.setEnabled(ENGINE_READY[i]);
            engineGroup.addView(button, new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (ENGINE_IDS[i].equals(prefs.engine())) {
                button.setChecked(true);
            }
        }
        if (engineGroup.getCheckedRadioButtonId() == -1) {
            ((RadioButton) engineGroup.getChildAt(0)).setChecked(true);
        }
        engineGroup.setOnCheckedChangeListener((group, id) -> {
            RadioButton chosen = group.findViewById(id);
            if (chosen != null) {
                prefs.setEngine((String) chosen.getTag());
            }
        });
        root.addView(engineGroup);

        transcript = new TextView(this);
        transcript.setTextSize(17);
        transcript.setTextIsSelectable(true);
        transcript.setLineSpacing(0f, 1.15f);
        scroll = new ScrollView(this);
        scroll.addView(transcript);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        speakBox = new CheckBox(this);
        speakBox.setText("Speak replies");
        speakBox.setChecked(prefs.speak());
        speakBox.setOnCheckedChangeListener((b, checked) -> {
            prefs.setSpeak(checked);
            if (!checked && tts != null) {
                tts.stop();
            }
        });
        root.addView(speakBox);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Ask anything");
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(input.getText().toString());
                return true;
            }
            return false;
        });
        bottom.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mic = new Button(this);
        mic.setText("Mic");
        mic.setOnClickListener(v -> {
            if (listening) {
                stopListening();
            } else {
                startListening();
            }
        });
        bottom.addView(mic);
        send = new Button(this);
        send.setText("Send");
        send.setOnClickListener(v -> {
            if (busy) {
                bridge.cancel();
            } else {
                submit(input.getText().toString());
            }
        });
        bottom.addView(send);
        root.addView(bottom);

        setContentView(root);
        updateButtons();
    }

    private void updateButtons() {
        send.setText(busy ? "Stop" : "Send");
        mic.setText(listening ? "Stop mic" : "Mic");
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void refreshStatus() {
        final String base = prefs.bridgeUrl();
        if (base.isEmpty() || prefs.token().isEmpty()) {
            setStatus("Not set up: open Settings and add the PC address and token.");
            return;
        }
        if (!BridgeClient.isAllowedBase(base)) {
            setStatus("The PC address must be https, or a Tailscale address.");
            return;
        }
        setStatus("Checking the PC...");
        probe.execute(() -> {
            final String problem = BridgeClient.health(base);
            ui.post(() -> setStatus(problem == null ? "Claude is ready (via your PC)" : problem));
        });
    }

    private void newChat() {
        if (busy) {
            bridge.cancel();
        }
        if (tts != null) {
            tts.stop();
        }
        history.clear();
        transcriptBase = "";
        transcript.setText("");
        refreshStatus();
    }

    // ---------- Asking ----------

    private void submit(String raw) {
        final String text = raw.trim();
        if (busy || text.isEmpty()) {
            return;
        }
        final String base = prefs.bridgeUrl();
        final String token = prefs.token();
        if (base.isEmpty() || token.isEmpty()) {
            setStatus("Not set up: open Settings and add the PC address and token.");
            return;
        }
        if (!BridgeClient.isAllowedBase(base)) {
            setStatus("The PC address must be https, or a Tailscale address.");
            return;
        }
        final String engine = Router.pick(prefs.engine());
        if (engine == null) {
            setStatus("That engine is not set up yet. Choose Auto or Claude.");
            return;
        }
        final JSONObject body = new JSONObject();
        try {
            JSONArray past = new JSONArray();
            for (JSONObject turn : history) {
                past.put(turn);
            }
            body.put("text", text);
            body.put("engine", engine);
            body.put("history", past);
        } catch (JSONException e) {
            setStatus("Could not build the request.");
            return;
        }

        if (tts != null) {
            tts.stop();
        }
        stopListening();
        input.setText("");
        transcriptBase = transcript.getText() + (transcript.length() == 0 ? "" : "\n\n") + "You: " + text + "\n\n";
        reply.setLength(0);
        pendingSpeech.setLength(0);
        busy = true;
        updateButtons();
        setStatus("Thinking...");
        renderReply();

        worker.execute(() -> bridge.ask(base, token, body, new BridgeClient.Listener() {
            @Override
            public void onMeta(String engineName, String model) {
                ui.post(() -> lastEngineUsed = model.isEmpty() ? engineName : engineName + " " + model);
            }

            @Override
            public void onDelta(String delta) {
                ui.post(() -> {
                    reply.append(delta);
                    renderReply();
                    speakChunk(delta);
                });
            }

            @Override
            public void onDone(String full) {
                ui.post(() -> {
                    reply.setLength(0);
                    reply.append(full);
                    renderReply();
                    flushSpeech();
                    remember(text, full);
                    finishAsk("Answered by " + lastEngineUsed);
                });
            }

            @Override
            public void onError(String message) {
                ui.post(() -> {
                    transcriptBase = transcriptBase + "[" + message + "]";
                    transcript.setText(transcriptBase);
                    finishAsk(message);
                });
            }
        }));
    }

    private void finishAsk(String statusText) {
        busy = false;
        updateButtons();
        setStatus(statusText);
    }

    private void remember(String userText, String answer) {
        try {
            history.add(new JSONObject().put("role", "user").put("text", userText));
            history.add(new JSONObject().put("role", "assistant").put("text", answer));
            while (history.size() > 24) {
                history.remove(0);
            }
        } catch (JSONException ignored) {
            // history is a convenience; losing a turn is harmless
        }
    }

    private void renderReply() {
        transcript.setText(transcriptBase + "Jarvis: " + reply);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- Speaking ----------

    private void speakChunk(String delta) {
        if (!speakBox.isChecked() || !ttsReady) {
            return;
        }
        pendingSpeech.append(delta);
        int cut = lastSentenceEnd(pendingSpeech);
        if (cut > 0) {
            String part = pendingSpeech.substring(0, cut);
            pendingSpeech.delete(0, cut);
            tts.speak(part, TextToSpeech.QUEUE_ADD, null, "j" + System.nanoTime());
        }
    }

    private void flushSpeech() {
        if (speakBox.isChecked() && ttsReady && pendingSpeech.toString().trim().length() > 0) {
            tts.speak(pendingSpeech.toString(), TextToSpeech.QUEUE_ADD, null, "j" + System.nanoTime());
        }
        pendingSpeech.setLength(0);
    }

    /** Index just after the last sentence end (. ! ? or a line break followed by more text), or 0. */
    private static int lastSentenceEnd(CharSequence s) {
        for (int i = s.length() - 2; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(s.charAt(i + 1))) {
                return i + 1;
            }
        }
        return 0;
    }

    // ---------- Listening ----------

    private void startListening() {
        if (busy || listening) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("This phone has no speech recogniser.");
            return;
        }
        if (tts != null) {
            tts.stop();
        }
        if (recognizer == null) {
            makeRecognizer(!retriedWithDefault && SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        listening = true;
        updateButtons();
        setStatus("Listening...");
        recognizer.startListening(intent);
    }

    private void stopListening() {
        if (recognizer != null && listening) {
            recognizer.cancel();
        }
        listening = false;
        updateButtons();
    }

    private void makeRecognizer(boolean onDevice) {
        if (recognizer != null) {
            recognizer.destroy();
        }
        recognizerOnDevice = onDevice;
        recognizer = onDevice
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                : SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String best = firstResult(partialResults);
                if (best != null) {
                    input.setText(best);
                    input.setSelection(input.length());
                }
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                updateButtons();
                String best = firstResult(results);
                if (best != null && !best.trim().isEmpty()) {
                    input.setText(best);
                    submit(best);
                } else {
                    setStatus("Did not catch that.");
                }
            }

            @Override
            public void onError(int error) {
                listening = false;
                updateButtons();
                boolean languageProblem = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
                if (recognizerOnDevice && languageProblem && !retriedWithDefault) {
                    retriedWithDefault = true; // offline pack missing: fall back to the normal recogniser
                    makeRecognizer(false);
                    startListening();
                    return;
                }
                setStatus(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Did not catch that." : "Speech recognition error " + error);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private static String firstResult(Bundle bundle) {
        ArrayList<String> list = bundle == null ? null : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                setStatus("Microphone permission was refused. You can still type.");
            }
        }
    }
}
