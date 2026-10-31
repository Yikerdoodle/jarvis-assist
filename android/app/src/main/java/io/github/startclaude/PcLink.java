package io.github.startclaude;

import android.content.Context;

/** Connects {@link PcStarter}'s steps to the real bridge, the real Wi-Fi and the real clock. */
final class PcLink implements PcStarter.Io {
    private final String base;
    private final String token;
    private final byte[] mac;          // null when no MAC address is set
    private final WakeSender sender;
    private volatile boolean cancelled;

    PcLink(Context context, String base, String token, byte[] mac) {
        this.base = base;
        this.token = token;
        this.mac = mac;
        this.sender = new WakeSender(context);
    }

    boolean haveMac() {
        return mac != null;
    }

    void cancel() {
        cancelled = true;
    }

    @Override
    public int ping() {
        return BridgeClient.ping(base);
    }

    @Override
    public PcStarter.Reply start() {
        return BridgeClient.startRemote(base, token);
    }

    @Override
    public boolean canWake() {
        return mac != null && sender.wifiAvailable();
    }

    @Override
    public boolean sendWake() {
        return mac != null && sender.send(mac);
    }

    @Override
    public boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !cancelled;
    }

    @Override
    public long now() {
        return System.nanoTime() / 1_000_000L; // milliseconds, and not thrown off by the clock changing
    }
}
