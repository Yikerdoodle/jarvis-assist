package io.github.startclaude;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Talks to the program on a computer. All methods block, so call them off the main thread. */
final class BridgeClient {
    private BridgeClient() {
    }

    private static String trim(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    /** Over Tailscale the PC already knows this phone, so the signature is not needed there but does no harm. */
    private static HttpURLConnection open(String base, String path, String method, String token, int connectMs,
                                          int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(trim(base) + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        // No connection re-use: Android could otherwise quietly re-send a request on a stale connection, and
        // the PC refuses a signed request it has already seen.
        c.setRequestProperty("Connection", "close");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", Signer.header(token, method, path));
        }
        return c;
    }

    /** The HTTP status of the health check, or -1 if nothing answered. Quick, so it can be polled. */
    static int ping(String base, String token, int connectMs) {
        HttpURLConnection c = null;
        try {
            c = open(base, "/v1/health", "GET", token, connectMs, 3000);
            return c.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Asks the computer to start Claude's Remote Control. Never throws. */
    static PcStarter.Reply startRemote(String base, String token) {
        HttpURLConnection c = null;
        try {
            c = open(base, "/v1/remote/start", "POST", token, 5000, 40000); // the PC waits a few seconds to see Claude stay up
            c.setDoOutput(true);
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

    /**
     * Pairs with a computer over Tailscale: it answers with its name, the secret for the home network, and its
     * network cards. The computer only does this for one of your own Tailscale devices.
     */
    static JSONObject pair(String base) throws IOException {
        HttpURLConnection c = null;
        try {
            int code;
            try {
                c = open(base, "/v1/pair", "POST", null, 4000, 30000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
                code = c.getResponseCode();
            } catch (IOException e) {
                throw new IOException("Nothing answered at " + trim(base) + ". Is Tailscale on here, is the computer "
                        + "awake, and is the Start Claude program installed and running on it?");
            }
            if (code != 200) {
                throw new IOException(describe(code, c));
            }
            return new JSONObject(readAll(c.getInputStream()));
        } catch (JSONException e) {
            throw new IOException("The computer sent something unexpected. Is its Start Claude program up to date?");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String describe(int code, HttpURLConnection c) {
        String fromPc = "";
        try {
            fromPc = new JSONObject(readAll(c.getErrorStream())).optString("error", "");
        } catch (Exception e) {
            // no readable explanation: use the generic one below
        }
        if (code == 401) {
            return "The PC did not accept this phone's signature. Either the computer was set up again (remove it "
                    + "here and add it again), or the clocks of the phone and the PC differ by more than a minute.";
        }
        if (code == 403 && (fromPc.isEmpty() || fromPc.equals("device not allowed"))) {
            return "The PC does not recognise this phone. Is Tailscale on, and signed in to the same account as the PC?";
        }
        return fromPc.isEmpty() ? "The PC answered " + code : fromPc;
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
