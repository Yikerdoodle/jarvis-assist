package io.github.startclaude;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * One computer, as {@link PcStarter} sees it: tries Tailscale first and the home network second, and wakes the
 * computer with its MAC address if the owner gave one.
 */
final class PcLink implements PcStarter.Io {
    private static final int TAILSCALE_CONNECT_MS = 3000;
    private static final int HOME_NETWORK_CONNECT_MS = 1500; // a computer on the same network answers quickly

    private final Computer computer;
    private final byte[] mac;          // null when no MAC address is known
    private final WakeSender sender;
    private volatile boolean cancelled;
    private volatile String activeBase; // the address that last answered

    PcLink(Context context, Computer computer) {
        this.computer = computer;
        this.mac = WakeOnLan.parseMac(computer.mac);
        this.sender = new WakeSender(context);
    }

    boolean haveMac() {
        return mac != null;
    }

    void cancel() {
        cancelled = true;
    }

    /** The address that answered last, or null. Lets the app say whether it got through by Tailscale or at home. */
    String activeBase() {
        return activeBase;
    }

    boolean answeredOverTailscale() {
        return computer.tsBase.equals(activeBase);
    }

    /** Tailscale first; the home-network addresses only while this phone is on Wi-Fi. */
    private List<String> bases() {
        List<String> out = new ArrayList<>();
        out.add(computer.tsBase);
        if (!computer.token.isEmpty() && sender.wifiAvailable()) {
            out.addAll(computer.lanBases);
        }
        List<String> allowed = new ArrayList<>();
        for (String base : out) {
            if (Address.isAllowedBase(base)) {
                allowed.add(base);
            }
        }
        return allowed;
    }

    @Override
    public int ping() {
        int best = -1;
        for (String base : bases()) {
            int code = BridgeClient.ping(base, computer.token,
                    base.equals(computer.tsBase) ? TAILSCALE_CONNECT_MS : HOME_NETWORK_CONNECT_MS);
            if (code == 200) {
                activeBase = base;
                return 200;
            }
            if (code != -1) {
                best = code; // it answered, but said no (for example 403 over Tailscale): the other address may work
            }
        }
        return best;
    }

    @Override
    public PcStarter.Reply start() {
        List<String> order = bases();
        String first = activeBase;
        if (first != null && order.remove(first)) {
            order.add(0, first);
        }
        PcStarter.Reply last = PcStarter.Reply.failure("Lost the connection to the PC.", true);
        for (String base : order) {
            last = BridgeClient.startRemote(base, computer.token);
            if (last.ok || !last.retryable) {
                return last;
            }
        }
        return last;
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
