package io.github.jarvisassist;

import java.util.Locale;

/** The maths for Wake-on-LAN: reading a MAC address, building the "magic packet", and finding the broadcast address. */
final class WakeOnLan {
    private WakeOnLan() {
    }

    /** Reads AA:BB:CC:DD:EE:FF, AA-BB-CC-DD-EE-FF or AABBCCDDEEFF. Returns the 6 bytes, or null if it is not a MAC. */
    static byte[] parseMac(String text) {
        if (text == null) {
            return null;
        }
        String hex = text.trim().replace(":", "").replace("-", "");
        if (hex.length() != 12) {
            return null;
        }
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            mac[i] = (byte) (high * 16 + low);
        }
        // All zeros or all ones is never a real network card, and the second would also be a broadcast address.
        boolean allZero = true;
        boolean allOnes = true;
        for (byte b : mac) {
            allZero &= b == 0;
            allOnes &= b == (byte) 0xFF;
        }
        return allZero || allOnes ? null : mac;
    }

    static String format(byte[] mac) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(String.format(Locale.ROOT, "%02X", mac[i] & 0xFF));
        }
        return out.toString();
    }

    /** The magic packet: six bytes of 0xFF, then the MAC address repeated sixteen times (102 bytes). */
    static byte[] magicPacket(byte[] mac) {
        if (mac == null || mac.length != 6) {
            throw new IllegalArgumentException("a MAC address has 6 bytes");
        }
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int copy = 0; copy < 16; copy++) {
            System.arraycopy(mac, 0, packet, 6 + copy * 6, 6);
        }
        return packet;
    }

    /** The "everyone on this network" address for an IPv4 address and its prefix length (24 means 255.255.255.0). */
    static byte[] broadcast(byte[] ip, int prefixLength) {
        if (ip == null || ip.length != 4 || prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("need an IPv4 address and a prefix from 0 to 32");
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int bitsInThisByte = Math.max(0, Math.min(8, prefixLength - 8 * i));
            int hostMask = 0xFF >> bitsInThisByte; // the part of this byte that belongs to the host
            out[i] = (byte) ((ip[i] & 0xFF) | hostMask);
        }
        return out;
    }

    /** True for 100.64.0.0/10, the range Tailscale hands out. A Wake-on-LAN packet must not be aimed at it. */
    static boolean isTailnetAddress(byte[] ip) {
        return ip != null && ip.length == 4 && (ip[0] & 0xFF) == 100 && (ip[1] & 0xFF) >= 64 && (ip[1] & 0xFF) <= 127;
    }
}
