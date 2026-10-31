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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The one settings screen: your computers in priority order, and the button for the home screen. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private LinearLayout computersBox;
    private EditText address;
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
        root.addView(label("One tap on the home-screen button goes down this list, top first. It wakes a sleeping "
                + "computer if it can (only from your home Wi-Fi), starts Claude on it unless it is already "
                + "running, and opens the Claude app. A computer that cannot be reached or woken is skipped for "
                + "the next one."));

        root.addView(label("Your computers, first choice first"));
        computersBox = new LinearLayout(this);
        computersBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(computersBox);

        root.addView(label("Add a computer: type its name on your Tailscale network (like nk-pc-asus) or its "
                + "100.x.y.z address. Tailscale must be on here, and the Start Claude program must be installed "
                + "on that computer. Nothing else to type: the app pairs with it by itself."));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("nk-pc-asus");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(address);

        Button add = new Button(this);
        add.setText("Add and pair");
        add.setOnClickListener(v -> addComputer());
        root.addView(add);

        Button test = new Button(this);
        test.setText("Check all computers");
        test.setOnClickListener(v -> checkAll());
        root.addView(test);

        CheckBox openClaude = new CheckBox(this);
        openClaude.setText("Open the Claude app when a computer is ready");
        openClaude.setChecked(prefs.openClaudeWhenReady());
        openClaude.setOnCheckedChangeListener((box, checked) -> prefs.setOpenClaudeWhenReady(checked));
        root.addView(openClaude);

        Button startNow = new Button(this);
        startNow.setText("Start Claude now");
        startNow.setOnClickListener(v -> startActivity(new Intent(this, WakeActivity.class)));
        root.addView(startNow);

        Button pin = new Button(this);
        pin.setText("Add a button to the home screen");
        pin.setOnClickListener(v -> addToHomeScreen());
        root.addView(pin);

        result = label("");
        root.addView(result);

        setContentView(scroll);
        renderComputers();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------- The list ----------

    private void renderComputers() {
        computersBox.removeAllViews();
        final List<Computer> list = prefs.computers();
        if (list.isEmpty()) {
            computersBox.addView(label("None yet."));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            Computer c = list.get(i);
            TextView text = new TextView(this);
            text.setText((i + 1) + ". " + c.name + "\n   Tailscale: " + c.tsBase
                    + "\n   Wake-up address: " + (c.mac.isEmpty() ? "not known" : c.mac)
                    + "\n   Home network: " + (c.lanBases.isEmpty() ? "not known" : String.join(", ", c.lanBases)));
            text.setPadding(0, dp(10), 0, dp(2));
            computersBox.addView(text);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(smallButton("Up", index > 0, v -> move(index, index - 1)));
            row.addView(smallButton("Down", index < list.size() - 1, v -> move(index, index + 1)));
            row.addView(smallButton("Remove", true, v -> remove(index)));
            computersBox.addView(row);
        }
    }

    private Button smallButton(String text, boolean enabled, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setEnabled(enabled);
        button.setOnClickListener(listener);
        return button;
    }

    private void move(int from, int to) {
        List<Computer> list = prefs.computers();
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size()) {
            return;
        }
        list.add(to, list.remove(from));
        prefs.saveComputers(list);
        renderComputers();
    }

    private void remove(int index) {
        List<Computer> list = prefs.computers();
        if (index < 0 || index >= list.size()) {
            return;
        }
        String name = list.remove(index).name;
        prefs.saveComputers(list);
        renderComputers();
        result.setText("Removed " + name + ".");
    }

    // ---------- Adding and checking ----------

    private void addComputer() {
        final String base = Address.normalize(address.getText().toString());
        if (base.isEmpty()) {
            result.setText("Type the computer's name or Tailscale address first.");
            return;
        }
        if (!Address.isAllowedBase(base)) {
            result.setText("Not allowed: use a computer name like nk-pc-asus, or a Tailscale address (100.x.x.x).");
            return;
        }
        result.setText("Pairing with " + base + "...");
        worker.execute(() -> {
            String message;
            Computer paired = null;
            try {
                JSONObject answer = BridgeClient.pair(base);
                List<String[]> cards = new ArrayList<>();
                JSONArray array = answer.optJSONArray("cards");
                for (int i = 0; array != null && i < array.length(); i++) {
                    JSONObject card = array.getJSONObject(i);
                    cards.add(new String[]{card.optString("mac"), card.optString("ip")});
                }
                paired = Computer.fromPairing(base, answer.getString("name"), answer.getString("token"),
                        answer.optInt("port", Address.DEFAULT_PORT), cards);
                message = "Paired with " + paired.name + "."
                        + (paired.mac.isEmpty() ? " It cannot be woken from here (no network card address came back)." : "")
                        + (paired.lanBases.isEmpty() ? " No home-network address came back, so there is no home-network fallback." : "");
            } catch (IOException | JSONException e) {
                message = "Could not pair: " + (e instanceof IOException ? e.getMessage()
                        : "the computer sent something unexpected.");
            }
            final String shown = message;
            final Computer found = paired;
            ui.post(() -> {
                if (found != null) {
                    saveOrReplace(found);
                    address.setText("");
                }
                result.setText(shown);
            });
        });
    }

    /** A computer that is already in the list (same name) is updated where it stands; a new one goes last. */
    private void saveOrReplace(Computer found) {
        List<Computer> list = prefs.computers();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equalsIgnoreCase(found.name)) {
                list.set(i, found);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(found);
        }
        prefs.saveComputers(list);
        renderComputers();
    }

    /** Asks each computer, without waking or starting anything, whether it would answer right now. */
    private void checkAll() {
        final List<Computer> list = prefs.computers();
        if (list.isEmpty()) {
            result.setText("Add a computer first.");
            return;
        }
        result.setText("Checking...");
        worker.execute(() -> {
            StringBuilder out = new StringBuilder();
            for (Computer c : list) {
                PcLink link = new PcLink(getApplicationContext(), c);
                int code = link.ping();
                out.append(c.name).append(": ");
                if (code == 200) {
                    out.append("ready, reached ").append(link.answeredOverTailscale() ? "over Tailscale" : "on the home network");
                } else if (code == -1) {
                    out.append("no answer (asleep, off, or out of reach)")
                            .append(link.canWake() ? ". Can be woken from here." : ". Cannot be woken from here right now.");
                } else {
                    out.append("answered ").append(code).append(" but refused this phone");
                }
                out.append('\n');
            }
            final String shown = out.toString().trim();
            ui.post(() -> result.setText(shown));
        });
    }

    /** Asks the home screen to add a one-tap "Start Claude" button. The home screen shows its own confirmation. */
    private void addToHomeScreen() {
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
