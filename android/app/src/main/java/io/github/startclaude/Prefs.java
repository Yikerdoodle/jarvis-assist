package io.github.startclaude;

import android.content.Context;
import android.content.SharedPreferences;

/** The few settings the app keeps, in the app's private storage. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("start_claude", Context.MODE_PRIVATE);
    }

    String bridgeUrl() {
        return sp.getString("pc_url", "").trim();
    }

    String token() {
        return sp.getString("token", "").trim();
    }

    void savePc(String url, String token) {
        sp.edit().putString("pc_url", url.trim()).putString("token", token.trim()).apply();
    }

    /** The PC's network card address, as AA:BB:CC:DD:EE:FF, used to wake it. "" when not set. */
    String pcMac() {
        return sp.getString("pc_mac", "").trim();
    }

    void setPcMac(String mac) {
        sp.edit().putString("pc_mac", mac.trim()).apply();
    }

    /** After the PC is ready, open the Claude app so sessions can be started right away. */
    boolean openClaudeWhenReady() {
        return sp.getBoolean("open_claude", true);
    }

    void setOpenClaudeWhenReady(boolean value) {
        sp.edit().putBoolean("open_claude", value).apply();
    }
}
