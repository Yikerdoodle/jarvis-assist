package io.github.jarvisassist;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
        super.onDestroy();
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
