package io.github.startclaude;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a request, so the secret itself is never sent. This must produce exactly what the PC program checks
 * (pc/start_claude_pc/auth.py); SignerTest and test_security.py use the same fixed example to prove it.
 */
final class Signer {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Signer() {
    }

    static String sign(String token, String method, String path, long timestamp, String nonce) {
        String message = method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + timestamp + "\n" + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    /** The Authorization header for one request. The nonce makes two requests in the same second different. */
    static String header(String token, String method, String path, long timestamp, String nonce) {
        return "StartClaude t=" + timestamp + ",n=" + nonce + ",s=" + sign(token, method, path, timestamp, nonce);
    }

    static String header(String token, String method, String path) {
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        return header(token, method, path, System.currentTimeMillis() / 1000L, hex(random));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return out.toString();
    }
}
