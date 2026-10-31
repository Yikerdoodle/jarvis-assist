package io.github.startclaude;

/**
 * The steps behind the home-screen button: find the PC, wake it if it is asleep, then ask it to start Claude.
 *
 * <p>This class has no Android code in it. Everything that touches the network or the clock comes in through
 * {@link Io}, so the steps can be tested on a plain computer.
 */
final class PcStarter {
    static final long WAKE_TIMEOUT_MS = 150_000;   // a PC waking from sleep takes seconds; from off, up to a minute or two
    static final long POLL_MS = 3_000;
    static final long RESEND_WAKE_MS = 10_000;     // one lost packet should not ruin the wait
    static final int START_ATTEMPTS = 3;           // right after waking, the network may need a moment
    static final long START_RETRY_MS = 2_000;

    /** What the bridge said when asked to start Claude. */
    static final class Reply {
        final boolean ok;
        final boolean retryable;   // true only when the network failed, not when the PC said no
        final String state;        // "started" or "already-running" when ok
        final String message;      // the reason, when not ok

        private Reply(boolean ok, boolean retryable, String state, String message) {
            this.ok = ok;
            this.retryable = retryable;
            this.state = state;
            this.message = message;
        }

        static Reply success(String state) {
            return new Reply(true, false, state, "");
        }

        static Reply failure(String message, boolean retryable) {
            return new Reply(false, retryable, "", message);
        }
    }

    interface Io {
        /** HTTP status of the bridge's health check, or -1 when the PC cannot be reached at all. */
        int ping();

        /** Asks the bridge to start Claude. Never throws. */
        Reply start();

        /** Whether this phone is on a Wi-Fi network a wake-up packet can be sent on. */
        boolean canWake();

        /** Sends the wake-up packets. False if none could be sent. */
        boolean sendWake();

        /** Waits. Returns false if the owner cancelled meanwhile. */
        boolean sleep(long millis);

        long now();
    }

    interface Listener {
        void onStep(String text);
    }

    static final class Outcome {
        final boolean ok;
        final boolean cancelled;
        final String state;    // "started" or "already-running" when ok
        final String message;  // what to tell the owner when not ok

        private Outcome(boolean ok, boolean cancelled, String state, String message) {
            this.ok = ok;
            this.cancelled = cancelled;
            this.state = state;
            this.message = message;
        }

        static Outcome success(String state) {
            return new Outcome(true, false, state, "");
        }

        static Outcome failure(String message) {
            return new Outcome(false, false, "", message);
        }

        static Outcome cancelled() {
            return new Outcome(false, true, "", "");
        }
    }

    private PcStarter() {
    }

    /** Runs all the steps. Blocks, so call it off the main thread. */
    static Outcome run(Io io, boolean haveMac, Listener listener) {
        listener.onStep("Looking for your PC...");
        int code = io.ping();
        if (code == 403) {
            return Outcome.failure("The PC does not recognise this phone. Is Tailscale signed in to the same account?");
        }
        if (code == -1) {
            Outcome woke = wake(io, haveMac, listener);
            if (woke != null) {
                return woke;
            }
        } else if (code != 200) {
            return Outcome.failure("The bridge on the PC answered " + code + ". Is it up to date?");
        }

        Reply reply = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            listener.onStep("Starting Claude on your PC...");
            reply = io.start();
            if (reply.ok || !reply.retryable) {
                break;
            }
            if (attempt < START_ATTEMPTS && !io.sleep(START_RETRY_MS)) {
                return Outcome.cancelled();
            }
        }
        return reply.ok ? Outcome.success(reply.state) : Outcome.failure(reply.message);
    }

    /** Returns null once the PC answers, otherwise the outcome that ends the run. */
    private static Outcome wake(Io io, boolean haveMac, Listener listener) {
        if (!haveMac) {
            return Outcome.failure("Cannot reach the PC. If it is asleep, add its MAC address in Settings so this "
                    + "button can wake it. If it is awake, check that Tailscale is on.");
        }
        if (!io.canWake()) {
            return Outcome.failure("Cannot reach the PC. Waking it only works from your home Wi-Fi, and this phone "
                    + "is not on Wi-Fi. If the PC is awake, check that Tailscale is on.");
        }
        long started = io.now();
        long lastSent = Long.MIN_VALUE;
        while (true) {
            long waited = io.now() - started;
            if (waited >= WAKE_TIMEOUT_MS) {
                return Outcome.failure("The PC did not answer within " + (WAKE_TIMEOUT_MS / 60_000.0) + " minutes. "
                        + "Check that Wake-on-LAN is on for it (see the README), and that it is plugged into the router with a cable.");
            }
            if (lastSent == Long.MIN_VALUE || waited - lastSent >= RESEND_WAKE_MS) {
                boolean first = lastSent == Long.MIN_VALUE;
                // Only the first send must work. If a later one fails the PC may already be waking, so keep waiting.
                if (!io.sendWake() && first) {
                    return Outcome.failure("Could not send the wake-up signal. Is this phone on your home Wi-Fi?");
                }
                lastSent = waited;
            }
            listener.onStep("Waking your PC... " + (waited / 1000) + " s");
            if (!io.sleep(POLL_MS)) {
                return Outcome.cancelled();
            }
            int code = io.ping();
            if (code == 200) {
                return null;
            }
            if (code == 403) {
                return Outcome.failure("The PC does not recognise this phone. Is Tailscale signed in to the same account?");
            }
        }
    }
}
