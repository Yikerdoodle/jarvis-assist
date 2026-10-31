package io.github.startclaude;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/** Talks to the listener on the PC. All methods block, so call them off the main thread. */
final class BridgeClient {
    private BridgeClient() {
    }

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

    /** Returns null when the PC is ready to start Claude, otherwise a short reason. */
    static String health(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(trim(base) + "/v1/health").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            int code = c.getResponseCode();
            if (code == 403) {
                return "The PC refused this phone. Is Tailscale signed in to the same account on both?";
            }
            if (code != 200) {
                return "The PC answered " + code;
            }
            JSONObject remote = new JSONObject(readAll(c.getInputStream())).optJSONObject("remote");
            if (remote == null || !remote.optBoolean("available")) {
                return "The program on the PC cannot find the claude command";
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

    /** The HTTP status of the health check, or -1 if the PC cannot be reached. Quick, so it can be polled. */
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

    /** Asks the PC to start Claude's Remote Control. Never throws. */
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
            throw new IOException("The PC sent something unexpected. Is the program on the PC up to date?");
        } finally {
            if (c != null) {
                c.disconnect();
            }
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
            return o.optString("error", "The PC answered " + code);
        } catch (Exception e) {
            return "The PC answered " + code;
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
