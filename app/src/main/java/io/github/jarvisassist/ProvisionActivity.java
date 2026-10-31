package io.github.jarvisassist;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Lets a computer fill in the PC address and token over ADB, so nothing long has to be typed on the phone.
 * Because any app could start this, nothing is saved until the owner taps Save on a dialog that shows where
 * questions would be sent. The token is never shown.
 */
public class ProvisionActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String rawUrl = getIntent().getStringExtra("bridge_url");
        String rawToken = getIntent().getStringExtra("token");
        final String url = rawUrl == null ? "" : rawUrl.trim();
        final String token = rawToken == null ? "" : rawToken.trim();
        if (!BridgeClient.isAllowedBase(url) || token.isEmpty()) {
            Toast.makeText(this, "That address is not allowed. Use your Tailscale address.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Connect Jarvis to this PC?")
                .setMessage(url + "\n\nYour questions will be sent to this address, over Tailscale, and answered by "
                        + "Claude on the PC. The access token is saved but not shown.")
                .setPositiveButton("Save", (dialog, which) -> {
                    new Prefs(this).saveBridge(url, token);
                    Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
