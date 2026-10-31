package io.github.jarvisassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PcStarterTest {

    /** A PC and a clock we control. The clock only moves when the code under test sleeps. */
    private static final class FakePc implements PcStarter.Io {
        long clock = 0;
        int pings = 0;
        int wakes = 0;
        int starts = 0;
        boolean onWifi = true;
        boolean wakeWorks = true;
        boolean cancelAfterSleeps = false;
        int sleeps = 0;
        /** The PC answers 200 once the clock reaches this time. -1 = never. */
        long awakeAt = 0;
        int codeWhenAwake = 200;
        int codeWhenAsleep = -1;
        final List<PcStarter.Reply> startReplies = new ArrayList<>();

        @Override
        public int ping() {
            pings++;
            return clock >= awakeAt && awakeAt >= 0 ? codeWhenAwake : codeWhenAsleep;
        }

        @Override
        public PcStarter.Reply start() {
            starts++;
            return startReplies.isEmpty() ? PcStarter.Reply.success("started") : startReplies.remove(0);
        }

        @Override
        public boolean canWake() {
            return onWifi;
        }

        @Override
        public boolean sendWake() {
            wakes++;
            return wakeWorks;
        }

        @Override
        public boolean sleep(long millis) {
            sleeps++;
            if (cancelAfterSleeps) {
                return false;
            }
            clock += millis;
            return true;
        }

        @Override
        public long now() {
            return clock;
        }
    }

    private static final class Steps implements PcStarter.Listener {
        final List<String> seen = new ArrayList<>();

        @Override
        public void onStep(String text) {
            seen.add(text);
        }
    }

    @Test
    public void awakePcIsJustStarted() {
        FakePc pc = new FakePc();
        PcStarter.Outcome out = PcStarter.run(pc, false, new Steps());
        assertTrue(out.ok);
        assertEquals("started", out.state);
        assertEquals(0, pc.wakes);
        assertEquals(1, pc.starts);
    }

    @Test
    public void alreadyRunningIsPassedOn() {
        FakePc pc = new FakePc();
        pc.startReplies.add(PcStarter.Reply.success("already-running"));
        assertEquals("already-running", PcStarter.run(pc, false, new Steps()).state);
    }

    @Test
    public void sleepingPcIsWokenThenStarted() {
        FakePc pc = new FakePc();
        pc.awakeAt = 20_000;
        pc.codeWhenAsleep = -1;
        Steps steps = new Steps();
        PcStarter.Outcome out = PcStarter.run(pc, true, steps);
        assertTrue(out.ok);
        assertEquals(1, pc.starts);
        assertTrue("sent the wake-up signal at least once", pc.wakes >= 1);
        assertTrue(steps.seen.get(0).startsWith("Looking"));
        assertTrue(steps.seen.contains("Starting Claude on your PC..."));
        boolean sawWaiting = false;
        for (String s : steps.seen) {
            sawWaiting |= s.startsWith("Waking your PC");
        }
        assertTrue(sawWaiting);
    }

    @Test
    public void wakeSignalIsRepeatedWhileWaiting() {
        FakePc pc = new FakePc();
        pc.awakeAt = 40_000;
        PcStarter.run(pc, true, new Steps());
        // 40 s of waiting with a re-send every 10 s: sends at about 0, 10, 20 and 30 s.
        assertTrue("wakes=" + pc.wakes, pc.wakes >= 3 && pc.wakes <= 5);
    }

    @Test
    public void asleepWithNoMacExplainsWhatToDo() {
        FakePc pc = new FakePc();
        pc.awakeAt = -1;
        PcStarter.Outcome out = PcStarter.run(pc, false, new Steps());
        assertFalse(out.ok);
        assertTrue(out.message, out.message.contains("MAC address"));
        assertEquals(0, pc.wakes);
        assertEquals(0, pc.starts);
    }

    @Test
    public void asleepAwayFromHomeWifiExplainsWhy() {
        FakePc pc = new FakePc();
        pc.awakeAt = -1;
        pc.onWifi = false;
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertTrue(out.message, out.message.contains("home Wi-Fi"));
        assertEquals(0, pc.wakes);
    }

    @Test
    public void givesUpAfterTheTimeout() {
        FakePc pc = new FakePc();
        pc.awakeAt = -1;
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertTrue(out.message, out.message.contains("did not answer"));
        assertTrue(pc.clock >= PcStarter.WAKE_TIMEOUT_MS);
        assertTrue("stopped soon after the timeout", pc.clock <= PcStarter.WAKE_TIMEOUT_MS + PcStarter.POLL_MS);
        assertEquals(0, pc.starts);
    }

    @Test
    public void firstWakeSendFailingStopsAtOnce() {
        FakePc pc = new FakePc();
        pc.awakeAt = -1;
        pc.wakeWorks = false;
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertTrue(out.message, out.message.contains("wake-up signal"));
        assertEquals(1, pc.wakes);
    }

    @Test
    public void cancellingWhileWaitingEndsQuietly() {
        FakePc pc = new FakePc();
        pc.awakeAt = -1;
        pc.cancelAfterSleeps = true;
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertTrue(out.cancelled);
        assertFalse(out.ok);
        assertEquals(0, pc.starts);
    }

    @Test
    public void aPcThatRefusesThisPhoneIsReportedAtOnce() {
        FakePc pc = new FakePc();
        pc.codeWhenAwake = 403;
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertTrue(out.message, out.message.contains("Tailscale"));
        assertEquals(0, pc.wakes);
        assertEquals(0, pc.starts);
    }

    @Test
    public void anUnexpectedHealthCodeIsReported() {
        FakePc pc = new FakePc();
        pc.codeWhenAwake = 500;
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertTrue(out.message, out.message.contains("500"));
    }

    @Test
    public void networkHiccupJustAfterWakingIsRetried() {
        FakePc pc = new FakePc();
        pc.startReplies.addAll(Arrays.asList(
                PcStarter.Reply.failure("Lost the connection to the PC.", true),
                PcStarter.Reply.failure("Lost the connection to the PC.", true),
                PcStarter.Reply.success("started")));
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertTrue(out.ok);
        assertEquals(3, pc.starts);
    }

    @Test
    public void retriesStopAfterTheLimit() {
        FakePc pc = new FakePc();
        for (int i = 0; i < 5; i++) {
            pc.startReplies.add(PcStarter.Reply.failure("Lost the connection to the PC.", true));
        }
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertEquals(PcStarter.START_ATTEMPTS, pc.starts);
        assertEquals("Lost the connection to the PC.", out.message);
    }

    @Test
    public void aRealRefusalIsNotRetried() {
        FakePc pc = new FakePc();
        pc.startReplies.add(PcStarter.Reply.failure("The claude command was not found on the PC.", false));
        PcStarter.Outcome out = PcStarter.run(pc, true, new Steps());
        assertFalse(out.ok);
        assertEquals("The claude command was not found on the PC.", out.message);
        assertEquals(1, pc.starts);
    }
}
