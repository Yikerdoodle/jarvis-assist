package io.github.jarvisassist;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Where the PC address and the token are entered, plus a shortcut to the system assistant picker. */
public class SettingsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private EditText url;
    private EditText token;
    private TextView result;

    private TextToSpeech tts;
    private boolean ttsReady;
    private Spinner voiceSpinner;
    private TextView voiceStatus;
    private final List<Voice> listedVoices = new ArrayList<>();
    private boolean spinnerArmed;

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

        root.addView(label("\nVoice for spoken replies"));
        voiceStatus = label("Loading voices...");
        root.addView(voiceStatus);
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
        moreVoices.setText("Get more natural voices");
        moreVoices.setOnClickListener(v -> openVoiceSettings());
        root.addView(moreVoices);

        tts = new TextToSpeech(this, code -> {
            ttsReady = code == TextToSpeech.SUCCESS;
            if (ttsReady) {
                Voices.apply(tts, prefs);
                refreshVoiceList();
            } else {
                voiceStatus.setText("No speech engine is available.");
            }
        });

        root.addView(label("\nDefault assistant (optional). Choosing this app there makes the power-button "
                + "hold open Jarvis instead of Gemini. You can switch back at any time."));
        Button assistant = new Button(this);
        assistant.setText("Open assistant settings");
        assistant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)));
        root.addView(assistant);

        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        probe.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private void refreshVoiceList() {
        listedVoices.clear();
        listedVoices.addAll(Voices.ranked(tts, prefs.ttsOnlineVoices()));
        List<String> labels = new ArrayList<>();
        labels.add("Automatic (best available)");
        int selected = 0;
        String wanted = prefs.ttsVoice();
        for (int i = 0; i < listedVoices.size(); i++) {
            labels.add(Voices.label(listedVoices.get(i)));
            if (listedVoices.get(i).getName().equals(wanted)) {
                selected = i + 1;
            }
        }
        voiceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinnerArmed = false; // the first callback comes from setSelection below, not from the owner
        voiceSpinner.setSelection(selected);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerArmed) {
                    spinnerArmed = true;
                    return;
                }
                prefs.setTtsVoice(position == 0 ? "" : listedVoices.get(position - 1).getName());
                Voices.apply(tts, prefs);
                sample();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        Voice current = tts.getVoice();
        voiceStatus.setText(listedVoices.isEmpty()
                ? "No English voices found. Try \"Get more natural voices\"."
                : listedVoices.size() + " English voices. In use: " + (current == null ? "unknown" : Voices.label(current)));
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
