package io.github.startclaude;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home-screen button. One tap: go down the owner's list of computers, first choice first. Find one (waking it
 * if it is asleep), start Claude's Remote Control on it, then open the Claude app so sessions can be started from
 * the phone. A computer that cannot be reached or woken is skipped for the next one.
 *
 * <p>It stays on screen while it works, because waking a PC can take a minute and Android may stop background
 * work. It holds nothing and runs nothing after it closes.
 */
public class WakeActivity extends Activity {
    private static final String CLAUDE_PACKAGE = "com.anthropic.claude";
    private static final String CLAUDE_WEB = "https://claude.ai/code";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private final List<PcLink> links = new ArrayList<>();
    private TextView status;
    private ProgressBar spinner;
    private Button action;
    private Button settings;
    private Runnable onAction;
    private boolean running;
    private boolean visible;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // waking a PC can take a minute

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(24) + bars.left, dp(48) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
            return WindowInsets.CONSUMED;
        });

        TextView title = new TextView(this);
        title.setText("Start Claude on your PC");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spin = new LinearLayout.LayoutParams(dp(48), dp(48));
        spin.topMargin = dp(32);
        root.addView(spinner, spin);

        status = new TextView(this);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); // read changes out loud for TalkBack
        status.setPadding(0, dp(24), 0, dp(24));
        root.addView(status);

        action = new Button(this);
        action.setOnClickListener(v -> {
            if (onAction != null) {
                onAction.run();
            }
        });
        root.addView(action);

        settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(settings);

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
        startRun();
    }

    /** Tapping the button again while this screen is already open starts over, unless a run is going. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!running) {
            startRun();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        for (PcLink link : links) {
            link.cancel();
        }
        worker.shutdownNow(); // interrupts a wait in progress
        super.onDestroy();
    }

    private void startRun() {
        for (PcLink old : links) {
            old.cancel();
        }
        links.clear();
        final List<PcPlanner.Candidate> candidates = new ArrayList<>();
        for (Computer computer : prefs.computers()) {
            PcLink link = new PcLink(getApplicationContext(), computer);
            links.add(link);
            candidates.add(new PcPlanner.Candidate(computer.name, link, link.haveMac()));
        }
        if (candidates.isEmpty()) {
            showProblem("No computers yet. Open Settings and add one.");
            return;
        }
        running = true;
        showWorking("Looking for your PC...");
        worker.execute(() -> {
            PcPlanner.Result result = PcPlanner.run(candidates, step -> ui.post(() -> {
                if (!destroyed && running) {
                    status.setText(step);
                }
            }));
            ui.post(() -> finishRun(result));
        });
    }

    private void finishRun(PcPlanner.Result result) {
        running = false;
        if (destroyed || result.cancelled) {
            return;
        }
        if (!result.ok) {
            showProblem(result.message);
            return;
        }
        if (prefs.openClaudeWhenReady() && visible && openClaude()) {
            finish();
            return;
        }
        showDone(("already-running".equals(result.state) ? "Claude was already running on " : "Claude is running on ")
                + result.computer + ".");
    }

    // ---------- What the screen shows ----------

    private void showWorking(String text) {
        spinner.setVisibility(View.VISIBLE);
        status.setText(text);
        settings.setVisibility(View.GONE);
        action.setText("Cancel");
        onAction = this::finish;
    }

    private void showProblem(String text) {
        spinner.setVisibility(View.GONE);
        status.setText(text);
        settings.setVisibility(View.VISIBLE);
        action.setText("Try again");
        onAction = this::startRun;
    }

    private void showDone(String text) {
        spinner.setVisibility(View.GONE);
        status.setText(text + " Open the Claude app to carry on.");
        settings.setVisibility(View.GONE);
        action.setText("Open Claude");
        onAction = () -> {
            if (openClaude()) {
                finish();
            }
        };
    }

    /** Opens the Claude app, or claude.ai/code in the browser if the app is not installed. */
    private boolean openClaude() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(CLAUDE_PACKAGE);
        if (launch == null) {
            launch = new Intent(Intent.ACTION_VIEW, Uri.parse(CLAUDE_WEB));
        }
        try {
            startActivity(launch);
            return true;
        } catch (ActivityNotFoundException e) {
            status.setText("Claude is running on your PC, but this phone could not open Claude. Open it yourself.");
            return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
