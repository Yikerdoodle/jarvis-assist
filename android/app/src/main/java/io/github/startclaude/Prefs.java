package io.github.startclaude;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The few settings the app keeps, in the app's private storage. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("start_claude", Context.MODE_PRIVATE);
    }

    /** The owner's computers, first choice first. A damaged entry is skipped rather than breaking the rest. */
    List<Computer> computers() {
        List<Computer> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp.getString("computers", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null || o.optString("name").isEmpty() || o.optString("ts").isEmpty()) {
                    continue;
                }
                List<String> lan = new ArrayList<>();
                JSONArray lanArray = o.optJSONArray("lan");
                for (int j = 0; lanArray != null && j < lanArray.length(); j++) {
                    lan.add(lanArray.optString(j));
                }
                out.add(new Computer(o.optString("name"), o.optString("ts"), lan, o.optString("mac"),
                        o.optString("token")));
            }
        } catch (JSONException e) {
            // unreadable: start with an empty list
        }
        return out;
    }

    void saveComputers(List<Computer> computers) {
        JSONArray array = new JSONArray();
        try {
            for (Computer c : computers) {
                array.put(new JSONObject()
                        .put("name", c.name)
                        .put("ts", c.tsBase)
                        .put("lan", new JSONArray(c.lanBases))
                        .put("mac", c.mac)
                        .put("token", c.token));
            }
        } catch (JSONException e) {
            return; // cannot happen: only strings are stored
        }
        sp.edit().putString("computers", array.toString()).apply();
    }

    /** After a computer is ready, open the Claude app so sessions can be started right away. */
    boolean openClaudeWhenReady() {
        return sp.getBoolean("open_claude", true);
    }

    void setOpenClaudeWhenReady(boolean value) {
        sp.edit().putBoolean("open_claude", value).apply();
    }
}
