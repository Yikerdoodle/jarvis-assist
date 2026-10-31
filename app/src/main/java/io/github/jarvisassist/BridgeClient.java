package io.github.jarvisassist;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Talks to the bridge on the PC. All methods block, so call them off the main thread. */
final class BridgeClient {

    interface Listener {
        void onMeta(String engine, String model);

        void onDelta(String text);

        void onDone(String text);

        void onError(String message);
    }

    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    /** https is always fine; plain http only for tailnet (100.64.0.0/10), *.ts.net and loopback addresses. */
    static boolean isAllowedBase(String base) {
        try {
            URI uri = new URI(base.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            if (scheme.equals("https")) {
                return true;
            }
            if (!scheme.equals("http")) {
                return false;
            }
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.endsWith(".ts.net")) {
                return true;
            }
            String[] parts = host.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 100 && second >= 64 && second <= 127;
        } catch (Exception e) {
            return false;
        }
    }

    private static String trim(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    /** Returns null when Claude is ready through the bridge, otherwise a short reason. */
    static String health(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(trim(base) + "/v1/health").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            int code = c.getResponseCode();
            if (code == 403) {
                return "PC refused this device";
            }
            if (code != 200) {
                return "Bridge answered " + code;
            }
            JSONObject root = new JSONObject(readAll(c.getInputStream()));
            JSONObject claude = root.getJSONObject("engines").optJSONObject("claude");
            if (claude == null || !claude.optBoolean("available")) {
                return "Claude is not available on the PC";
            }
            return null;
        } catch (IOException | JSONException e) {
            return "PC unreachable (is Tailscale on?)";
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** The HTTP status of the bridge's health check, or -1 if the PC cannot be reached. Quick, so it can be polled. */
    static int ping(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(trim(base) + "/v1/health").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            return c.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Asks the bridge to start Claude's Remote Control on the PC. Never throws. */
    static PcStarter.Reply startRemote(String base, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(trim(base) + "/v1/remote/start").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(40000); // the PC checks what is running and waits a few seconds to see Claude stay up
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            if (code == 200) {
                return PcStarter.Reply.success(new JSONObject(readAll(c.getInputStream())).optString("state", "started"));
            }
            return PcStarter.Reply.failure(describe(code, c), false);
        } catch (IOException | JSONException e) {
            return PcStarter.Reply.failure("Lost the connection to the PC.", true);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** The PC's network cards, as {name, adapter, mac} objects with the likeliest wired one first. */
    static JSONArray wakeInfo(String base, String token) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(trim(base) + "/v1/wake-info").openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException(describe(code, c));
            }
            return new JSONObject(readAll(c.getInputStream())).getJSONArray("macs");
        } catch (JSONException e) {
            throw new IOException("The PC sent something unexpected. Is the bridge up to date?");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    void ask(String base, String token, JSONObject body, Listener listener) {
        cancelled = false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(trim(base) + "/v1/ask").openConnection();
            active = c;
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(200000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + token);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                listener.onError(describe(code, c));
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            boolean finished = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                JSONObject event = new JSONObject(line);
                switch (event.optString("type")) {
                    case "meta":
                        listener.onMeta(event.optString("engine"), event.optString("model"));
                        break;
                    case "delta":
                        listener.onDelta(event.optString("text"));
                        break;
                    case "done":
                        finished = true;
                        listener.onDone(event.optString("text"));
                        break;
                    case "error":
                        finished = true;
                        listener.onError(event.optString("message", "Something went wrong."));
                        break;
                    default:
                        break;
                }
            }
            if (!finished && !cancelled) {
                listener.onError("The reply stopped early.");
            }
        } catch (IOException | JSONException e) {
            if (!cancelled) {
                listener.onError("Lost the connection to the PC.");
            }
        } finally {
            active = null;
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Stops a running request. Safe to call from the main thread. */
    void cancel() {
        cancelled = true;
        final HttpURLConnection c = active;
        if (c != null) {
            new Thread(c::disconnect).start();
        }
    }

    private static String describe(int code, HttpURLConnection c) {
        if (code == 401) {
            return "The token is wrong. Check it in Settings.";
        }
        if (code == 403) {
            return "The PC does not recognise this device. Is Tailscale on?";
        }
        try {
            JSONObject o = new JSONObject(readAll(c.getErrorStream()));
            return "Bridge error: " + o.optString("error", String.valueOf(code));
        } catch (Exception e) {
            return "Bridge error " + code;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }
}
