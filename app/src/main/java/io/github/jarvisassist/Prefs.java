package io.github.jarvisassist;

import android.content.Context;
import android.content.SharedPreferences;

/** The few settings the app keeps, in the app's private storage. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    String bridgeUrl() {
        return sp.getString("bridge_url", "").trim();
    }

    String token() {
        return sp.getString("token", "").trim();
    }

    void saveBridge(String url, String token) {
        sp.edit().putString("bridge_url", url.trim()).putString("token", token.trim()).apply();
    }

    boolean speak() {
        return sp.getBoolean("speak", true);
    }

    void setSpeak(boolean value) {
        sp.edit().putBoolean("speak", value).apply();
    }

    String engine() {
        return sp.getString("engine", "auto");
    }

    void setEngine(String value) {
        sp.edit().putString("engine", value).apply();
    }
}
