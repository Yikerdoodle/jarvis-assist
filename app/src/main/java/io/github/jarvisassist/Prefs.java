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

    /** The language whose voice is used, as a language tag. US English by default. */
    String ttsLanguage() {
        return sp.getString("tts_language", "en-US");
    }

    void setTtsLanguage(String tag) {
        sp.edit().putString("tts_language", tag).apply();
    }

    /** The text-to-speech engine's package name, or "" for the phone's default engine. */
    String ttsEngine() {
        return sp.getString("tts_engine", "");
    }

    void setTtsEngine(String packageName) {
        sp.edit().putString("tts_engine", packageName).apply();
    }

    /** The chosen text-to-speech voice, or "" to pick the best one automatically. */
    String ttsVoice() {
        return sp.getString("tts_voice", "");
    }

    void setTtsVoice(String name) {
        sp.edit().putString("tts_voice", name).apply();
    }

    /** Speech speed, 1.0 = normal. */
    float ttsRate() {
        return sp.getFloat("tts_rate", 1.0f);
    }

    void setTtsRate(float rate) {
        sp.edit().putFloat("tts_rate", rate).apply();
    }

    /** Online voices sound more natural but send the text of each reply to Google, so they are off by default. */
    boolean ttsOnlineVoices() {
        return sp.getBoolean("tts_online", false);
    }

    void setTtsOnlineVoices(boolean allowed) {
        sp.edit().putBoolean("tts_online", allowed).apply();
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
