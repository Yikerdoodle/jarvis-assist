package io.github.jarvisassist;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The PC address and token, the spoken-reply voice, and a shortcut to the system assistant picker. */
public class SettingsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private EditText url;
    private EditText token;
    private TextView result;
    private final NanoEngine nano = new NanoEngine();
    private TextView nanoStatus;

    private TextToSpeech tts;
    private boolean ttsReady;
    private TextView voiceStatus;
    private Spinner engineSpinner;
    private Spinner languageSpinner;
    private Spinner voiceSpinner;
    private final List<TextToSpeech.EngineInfo> listedEngines = new ArrayList<>();
    private final List<String> listedLanguages = new ArrayList<>();
    private final List<Voice> listedVoices = new ArrayList<>();
    // Filling a spinner makes it report a "selection" that the owner did not make; each spinner skips that first one.
    private boolean engineArmed;
    private boolean languageArmed;
    private boolean voiceArmed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom);
            return WindowInsets.CONSUMED;
        });

        root.addView(label("PC address (Tailscale), for example http://100.x.y.z:8787"));
        url = new EditText(this);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.bridgeUrl());
        root.addView(url);

        root.addView(label("App token (from the PC, file bridge-token)"));
        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.token());
        root.addView(token);

        Button save = new Button(this);
        save.setText("Save and test");
        save.setOnClickListener(v -> saveAndTest());
        root.addView(save);

        result = label("");
        root.addView(result);

        root.addView(label("\nOn-device model (Gemini Nano, from Google)"));
        nanoStatus = label("Checking...");
        root.addView(nanoStatus);
        Button nanoDownload = new Button(this);
        nanoDownload.setText("Download Gemini Nano (Wi-Fi only)");
        nanoDownload.setOnClickListener(v -> startNanoDownload());
        root.addView(nanoDownload);
        Button nanoCheck = new Button(this);
        nanoCheck.setText("Check again");
        nanoCheck.setOnClickListener(v -> checkNano());
        root.addView(nanoCheck);
        Button nanoProbe = new Button(this);
        nanoProbe.setText("Check what Gemini Nano can do (tool calling)");
        nanoProbe.setOnClickListener(v -> {
            nanoStatus.setText("Asking the phone...");
            nano.probeCapabilities(lines -> ui.post(() -> nanoStatus.setText(android.text.TextUtils.join("\n", lines))));
        });
        root.addView(nanoProbe);

        root.addView(label("\nSpoken replies"));
        voiceStatus = label("Loading voices...");
        root.addView(voiceStatus);

        root.addView(label("Speech engine"));
        engineSpinner = new Spinner(this);
        root.addView(engineSpinner);

        root.addView(label("Language"));
        languageSpinner = new Spinner(this);
        root.addView(languageSpinner);

        root.addView(label("Voice (\"Automatic\" uses the voice you picked in the engine's own settings)"));
        voiceSpinner = new Spinner(this);
        root.addView(voiceSpinner);

        root.addView(label("Speech speed"));
        SeekBar speed = new SeekBar(this);
        speed.setMax(80); // 0.60x to 1.40x
        speed.setProgress(Math.round((prefs.ttsRate() - 0.6f) * 100f));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.setTtsRate(0.6f + bar.getProgress() / 100f);
                if (ttsReady) {
                    Voices.apply(tts, prefs);
                    sample();
                }
            }
        });
        root.addView(speed);

        CheckBox online = new CheckBox(this);
        online.setText("Allow online voices (more natural, but the text of each reply is sent to Google)");
        online.setChecked(prefs.ttsOnlineVoices());
        online.setOnCheckedChangeListener((box, checked) -> {
            prefs.setTtsOnlineVoices(checked);
            if (ttsReady) {
                Voices.apply(tts, prefs);
                refreshVoiceList();
            }
        });
        root.addView(online);

        Button hear = new Button(this);
        hear.setText("Hear this voice");
        hear.setOnClickListener(v -> sample());
        root.addView(hear);

        Button moreVoices = new Button(this);
        moreVoices.setText("Get more voices (opens the engine's settings)");
        moreVoices.setOnClickListener(v -> openVoiceSettings());
        root.addView(moreVoices);

        root.addView(label("\nDefault assistant (optional). Choosing this app there makes the power-button "
                + "hold open Jarvis instead of Gemini. You can switch back at any time."));
        Button assistant = new Button(this);
        assistant.setText("Open assistant settings");
        assistant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)));
        root.addView(assistant);

        setContentView(scroll);
        startTts();
        checkNano();
    }

    // ---------- On-device model ----------

    private void checkNano() {
        nano.checkStatus((text, state) -> ui.post(() -> nanoStatus.setText(text)));
    }

    /** Only runs when the owner taps the button, and only on an unmetered (Wi-Fi) connection. */
    private void startNanoDownload() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            nanoStatus.setText("You are on mobile data or offline. Connect to Wi-Fi first, then tap again.");
            return;
        }
        nanoStatus.setText("Starting the download from Google...");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // keep the screen on while it runs
        nano.download(new NanoEngine.DownloadListener() {
            @Override
            public void onProgress(long bytes) {
                ui.post(() -> nanoStatus.setText(String.format(Locale.ROOT,
                        "Downloading Gemini Nano: %.0f MB so far. Keep this screen open.", bytes / 1048576.0)));
            }

            @Override
            public void onDone() {
                ui.post(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    nanoStatus.setText("Downloaded. Gemini Nano is ready.");
                });
            }

            @Override
            public void onError(String message) {
                ui.post(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    nanoStatus.setText("Download problem: " + message);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        probe.shutdownNow();
        nano.stopDownload();
        nano.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ---------- Speech ----------

    private void startTts() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        ttsReady = false;
        String engine = prefs.ttsEngine();
        tts = new TextToSpeech(this, code -> {
            ttsReady = code == TextToSpeech.SUCCESS;
            if (!ttsReady && !prefs.ttsEngine().isEmpty()) {
                prefs.setTtsEngine(""); // the chosen engine is gone: go back to the phone's default
                startTts();
                return;
            }
            if (!ttsReady) {
                voiceStatus.setText("No speech engine is available.");
                return;
            }
            Voices.apply(tts, prefs);
            refreshEngineList();
            refreshLanguageList();
            refreshVoiceList();
        }, engine.isEmpty() ? null : engine);
    }

    private void refreshEngineList() {
        listedEngines.clear();
        listedEngines.addAll(tts.getEngines());
        List<String> labels = new ArrayList<>();
        labels.add("Phone default");
        int selected = 0;
        for (int i = 0; i < listedEngines.size(); i++) {
            labels.add(listedEngines.get(i).label);
            if (listedEngines.get(i).name.equals(prefs.ttsEngine())) {
                selected = i + 1;
            }
        }
        engineSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        engineArmed = false;
        engineSpinner.setSelection(selected);
        engineSpinner.setOnItemSelectedListener(new SimpleSelection() {
            @Override
            void chosen(int position) {
                if (!engineArmed) {
                    engineArmed = true;
                    return;
                }
                prefs.setTtsEngine(position == 0 ? "" : listedEngines.get(position - 1).name);
                prefs.setTtsVoice(""); // voice names belong to one engine
                startTts();
            }
        });
    }

    private void refreshLanguageList() {
        listedLanguages.clear();
        listedLanguages.addAll(Voices.englishTags(tts));
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < listedLanguages.size(); i++) {
            labels.add(Voices.languageLabel(listedLanguages.get(i)));
            if (listedLanguages.get(i).equals(prefs.ttsLanguage())) {
                selected = i;
            }
        }
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        languageArmed = false;
        languageSpinner.setSelection(selected);
        languageSpinner.setOnItemSelectedListener(new SimpleSelection() {
            @Override
            void chosen(int position) {
                if (!languageArmed) {
                    languageArmed = true;
                    return;
                }
                prefs.setTtsLanguage(listedLanguages.get(position));
                prefs.setTtsVoice("");
                Voices.apply(tts, prefs);
                refreshVoiceList();
                sample();
            }
        });
    }

    private void refreshVoiceList() {
        listedVoices.clear();
        listedVoices.addAll(Voices.ranked(tts, prefs.ttsOnlineVoices(), prefs.ttsLanguage()));
        List<String> labels = new ArrayList<>();
        labels.add("Automatic");
        int selected = 0;
        String wanted = prefs.ttsVoice();
        for (int i = 0; i < listedVoices.size(); i++) {
            labels.add(Voices.label(listedVoices.get(i)));
            if (listedVoices.get(i).getName().equals(wanted)) {
                selected = i + 1;
            }
        }
        voiceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        voiceArmed = false;
        voiceSpinner.setSelection(selected);
        voiceSpinner.setOnItemSelectedListener(new SimpleSelection() {
            @Override
            void chosen(int position) {
                if (!voiceArmed) {
                    voiceArmed = true;
                    return;
                }
                prefs.setTtsVoice(position == 0 ? "" : listedVoices.get(position - 1).getName());
                Voices.apply(tts, prefs);
                showCurrentVoice();
                sample();
            }
        });
        showCurrentVoice();
    }

    private void showCurrentVoice() {
        Voice current = tts.getVoice();
        voiceStatus.setText(current == null ? "In use: unknown" : "In use: " + Voices.label(current));
    }

    private void sample() {
        if (ttsReady) {
            tts.speak("This is how I sound. It is twenty-two forty-five, and nine degrees outside.",
                    TextToSpeech.QUEUE_FLUSH, null, "sample");
        }
    }

    private void openVoiceSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
            } catch (ActivityNotFoundException e2) {
                startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
            }
        }
    }

    /** A spinner listener that only needs to know which position was picked. */
    private abstract static class SimpleSelection implements AdapterView.OnItemSelectedListener {
        abstract void chosen(int position);

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            chosen(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    // ---------- Layout and connection ----------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private void saveAndTest() {
        final String base = url.getText().toString().trim();
        prefs.saveBridge(base, token.getText().toString());
        if (!BridgeClient.isAllowedBase(base)) {
            result.setText("Not allowed: use https, or an http address on your Tailscale network (100.x.x.x).");
            return;
        }
        result.setText("Testing...");
        probe.execute(() -> {
            final String problem = BridgeClient.health(base);
            ui.post(() -> result.setText(problem == null
                    ? "Connected. Claude is ready on the PC."
                    : "Not ready: " + problem));
        });
    }
}
