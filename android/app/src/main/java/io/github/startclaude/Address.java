package io.github.startclaude;

import java.net.URI;
import java.util.regex.Pattern;

/** Which addresses the app will talk to, and turning what the owner types into one. */
final class Address {
    static final int DEFAULT_PORT = 8788;
    private static final Pattern SINGLE_LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private Address() {
    }

    /**
     * https is always fine. Plain http only for places where it cannot be read by strangers or where nothing
     * secret is sent: this phone, Tailscale (encrypted by Tailscale; the address is a 100.64.0.0/10 address, a
     * *.ts.net name, or a short MagicDNS name such as nk-pc-asus), and the home network (where each request is
     * signed and the secret itself is never sent).
     */
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
            if (host.equals("localhost") || host.endsWith(".ts.net")) {
                return true;
            }
            if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return isLoopbackIp(host) || isTailnetIp(host) || isHomeNetworkIp(host);
            }
            return SINGLE_LABEL.matcher(host).matches();
        } catch (Exception e) {
            return false;
        }
    }

    /** "nk-pc-asus" becomes "http://nk-pc-asus:8788". An address that already has a scheme or port keeps it. */
    static String normalize(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return "";
        }
        String withScheme = text.contains("://") ? text : "http://" + text;
        try {
            URI uri = new URI(withScheme);
            if (uri.getHost() == null) {
                return withScheme;
            }
            int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + ":" + port;
        } catch (Exception e) {
            return withScheme;
        }
    }

    private static int[] octets(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                out[i] = Integer.parseInt(parts[i]);
                if (out[i] < 0 || out[i] > 255) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    static boolean isLoopbackIp(String ip) {
        int[] o = octets(ip);
        return o != null && o[0] == 127;
    }

    /** 100.64.0.0/10, the range Tailscale hands out. */
    static boolean isTailnetIp(String ip) {
        int[] o = octets(ip);
        return o != null && o[0] == 100 && o[1] >= 64 && o[1] <= 127;
    }

    /** 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16: the addresses home networks use. */
    static boolean isHomeNetworkIp(String ip) {
        int[] o = octets(ip);
        return o != null && (o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168));
    }
}
