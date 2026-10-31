package io.github.startclaude;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Insets;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The one settings screen: where the PC is, how to wake it, and the button for the home screen. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private EditText url;
    private EditText token;
    private EditText mac;
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

        TextView title = new TextView(this);
        title.setText("Start Claude on my PC");
        title.setTextSize(22);
        root.addView(title);
        root.addView(label("Put a button on your home screen. One tap wakes your PC if it is asleep, starts Claude "
                + "on it, and opens the Claude app so you can start sessions from here. Set it up once:"));

        root.addView(label("1. The PC's Tailscale address, with the port. For example http://100.x.y.z:8788"));
        url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("http://100.x.y.z:8788");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.bridgeUrl());
        root.addView(url);

        root.addView(label("2. The token. On the PC it is the file pc\\data\\token. Do not share it."));
        token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.token());
        root.addView(token);

        root.addView(label("3. Optional: the PC's network address (MAC), so the button can wake it when it is "
                + "asleep. It only works on your home Wi-Fi. It looks like AA:BB:CC:DD:EE:FF."));
        mac = new EditText(this);
        mac.setSingleLine(true);
        mac.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mac.setText(prefs.pcMac());
        root.addView(mac);

        Button fetchMac = new Button(this);
        fetchMac.setText("Get it from the PC");
        fetchMac.setOnClickListener(v -> fetchMac());
        root.addView(fetchMac);

        Button save = new Button(this);
        save.setText("Save and test");
        save.setOnClickListener(v -> saveAndTest());
        root.addView(save);

        CheckBox openClaude = new CheckBox(this);
        openClaude.setText("Open the Claude app when the PC is ready");
        openClaude.setChecked(prefs.openClaudeWhenReady());
        openClaude.setOnCheckedChangeListener((box, checked) -> prefs.setOpenClaudeWhenReady(checked));
        root.addView(openClaude);

        Button startNow = new Button(this);
        startNow.setText("Start Claude on the PC now");
        startNow.setOnClickListener(v -> {
            if (saveFields()) {
                startActivity(new Intent(this, WakeActivity.class));
            }
        });
        root.addView(startNow);

        Button pin = new Button(this);
        pin.setText("Add a button to the home screen");
        pin.setOnClickListener(v -> addToHomeScreen());
        root.addView(pin);

        result = label("");
        root.addView(result);

        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        probe.shutdownNow();
        super.onDestroy();
    }

    /** Saves what is typed if it is acceptable. Otherwise says what is wrong and returns false. */
    private boolean saveFields() {
        String address = url.getText().toString().trim();
        if (!address.isEmpty() && !BridgeClient.isAllowedBase(address)) {
            result.setText("Not allowed: use https, or an http address on your Tailscale network (100.x.x.x).");
            return false;
        }
        String text = mac.getText().toString().trim();
        if (!text.isEmpty()) {
            byte[] parsed = WakeOnLan.parseMac(text);
            if (parsed == null) {
                result.setText("That is not a MAC address. It looks like AA:BB:CC:DD:EE:FF.");
                return false;
            }
            text = WakeOnLan.format(parsed);
            mac.setText(text);
        }
        prefs.savePc(address, token.getText().toString());
        prefs.setPcMac(text);
        return true;
    }

    private void saveAndTest() {
        if (!saveFields()) {
            return;
        }
        final String base = url.getText().toString().trim();
        result.setText("Testing...");
        probe.execute(() -> {
            final String problem = BridgeClient.health(base);
            ui.post(() -> result.setText(problem == null
                    ? "Connected. The PC is ready to start Claude."
                    : "Not ready: " + problem));
        });
    }

    /** Asks the PC which network cards it has, and fills in the likeliest one. */
    private void fetchMac() {
        final String base = url.getText().toString().trim();
        final String secret = token.getText().toString().trim();
        if (!BridgeClient.isAllowedBase(base) || secret.isEmpty()) {
            result.setText("Fill in the PC address and the token above first.");
            return;
        }
        result.setText("Asking the PC...");
        probe.execute(() -> {
            String message;
            String found = null;
            try {
                JSONArray cards = BridgeClient.wakeInfo(base, secret);
                if (cards.length() == 0) {
                    message = "The PC did not report a network card. Type its MAC address by hand: on the PC, run "
                            + "ipconfig /all and copy the Physical Address of the Ethernet adapter.";
                } else {
                    StringBuilder list = new StringBuilder("Found on the PC:");
                    for (int i = 0; i < cards.length(); i++) {
                        JSONObject card = cards.getJSONObject(i);
                        list.append('\n').append(card.optString("name")).append(": ").append(card.getString("mac"));
                    }
                    found = cards.getJSONObject(0).getString("mac");
                    message = list + "\nFilled in the first one. A cable connection wakes a PC most reliably.";
                }
            } catch (IOException | JSONException e) {
                message = "Could not ask the PC: " + e.getMessage();
            }
            final String shown = message;
            final String filledIn = found;
            ui.post(() -> {
                if (filledIn != null) {
                    mac.setText(filledIn);
                }
                result.setText(shown);
            });
        });
    }

    /** Asks the home screen to add a one-tap "Start Claude" button. The home screen shows its own confirmation. */
    private void addToHomeScreen() {
        if (!saveFields()) {
            return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            result.setText("This home screen cannot add buttons. Touch and hold the Start Claude icon instead: "
                    + "\"Start Claude on my PC\" is in its menu, and you can drag it out onto the home screen.");
            return;
        }
        ShortcutInfo info = new ShortcutInfo.Builder(this, "start_pc_pinned")
                .setShortLabel(getString(R.string.shortcut_start_short))
                .setLongLabel(getString(R.string.shortcut_start_long))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_pc_shortcut))
                .setIntent(new Intent(this, WakeActivity.class).setAction(Intent.ACTION_VIEW))
                .build();
        result.setText(manager.requestPinShortcut(info, null)
                ? "Your home screen will ask you to confirm."
                : "The home screen said no. Touch and hold the Start Claude icon instead: the button is in its menu.");
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
}
