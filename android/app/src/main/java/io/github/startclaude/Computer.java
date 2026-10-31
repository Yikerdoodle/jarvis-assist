package io.github.startclaude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One computer the button can start Claude on. The list of them, in order, is the priority order. */
final class Computer {
    final String name;           // as the computer calls itself, e.g. NK-PC-ASUS
    final String tsBase;         // how to reach it over Tailscale, e.g. http://nk-pc-asus:8788
    final List<String> lanBases; // home-network addresses to try when Tailscale is not available
    final String mac;            // for waking it, AA:BB:CC:DD:EE:FF, or "" if unknown
    final String token;          // the secret for signing requests on the home network

    Computer(String name, String tsBase, List<String> lanBases, String mac, String token) {
        this.name = name;
        this.tsBase = tsBase;
        this.lanBases = Collections.unmodifiableList(new ArrayList<>(lanBases));
        this.mac = mac;
        this.token = token;
    }

    /**
     * Builds a computer from what it said when pairing. {@code cards} are its network cards, wired first, each as
     * {mac, ip} (either may be empty). The first card with a MAC is the one to wake; every home-network address
     * becomes a fallback address.
     */
    static Computer fromPairing(String tsBase, String name, String token, int port, List<String[]> cards) {
        String mac = "";
        List<String> lan = new ArrayList<>();
        for (String[] card : cards) {
            String cardMac = card.length > 0 && card[0] != null ? card[0] : "";
            String cardIp = card.length > 1 && card[1] != null ? card[1] : "";
            if (mac.isEmpty() && WakeOnLan.parseMac(cardMac) != null) {
                mac = WakeOnLan.format(WakeOnLan.parseMac(cardMac));
            }
            String base = "http://" + cardIp + ":" + port;
            if (Address.isHomeNetworkIp(cardIp) && !lan.contains(base)) {
                lan.add(base);
            }
        }
        return new Computer(name, tsBase, lan, mac, token);
    }
}
