package io.github.startclaude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PcPlannerTest {

    /** One computer and its own clock. The clock moves only when the code under test waits. */
    private static final class FakePc implements PcStarter.Io {
        long clock = 0;
        long awakeAt = 0;          // answers from this time on; -1 = never
        boolean wakeable = true;
        int wakes = 0;
        int starts = 0;
        int pings = 0;
        boolean cancel = false;
        PcStarter.Reply reply = PcStarter.Reply.success("started");

        @Override
        public int ping() {
            pings++;
            return awakeAt >= 0 && clock >= awakeAt ? 200 : -1;
        }

        @Override
        public PcStarter.Reply start() {
            starts++;
            return reply;
        }

        @Override
        public boolean canWake() {
            return wakeable;
        }

        @Override
        public boolean sendWake() {
            wakes++;
            return true;
        }

        @Override
        public boolean sleep(long millis) {
            if (cancel) {
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

    private static PcPlanner.Candidate c(String name, FakePc pc, boolean haveMac) {
        return new PcPlanner.Candidate(name, pc, haveMac);
    }

    @Test
    public void theFirstChoiceIsUsedWhenItIsAwake() {
        FakePc asus = new FakePc();
        FakePc laptop = new FakePc();
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, true), c("laptop", laptop, true)), new Steps());
        assertTrue(r.ok);
        assertEquals("nk-pc-asus", r.computer);
        assertEquals("started", r.state);
        assertEquals(1, asus.starts);
        assertEquals("the second one is not even asked", 0, laptop.pings);
    }

    @Test
    public void aSleepingFirstChoiceIsWokenAndUsedEvenIfASecondOneIsAwake() {
        FakePc asus = new FakePc();
        asus.awakeAt = 20_000;
        FakePc laptop = new FakePc();
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, true), c("laptop", laptop, true)), new Steps());
        assertTrue(r.ok);
        assertEquals("nk-pc-asus", r.computer);
        assertTrue(asus.wakes >= 1);
        assertEquals(0, laptop.starts);
    }

    @Test
    public void aFirstChoiceThatCannotBeWokenIsSkippedAtOnce() {
        FakePc asus = new FakePc();
        asus.awakeAt = -1;
        asus.wakeable = false;        // for example: away from home Wi-Fi
        FakePc laptop = new FakePc();
        Steps steps = new Steps();
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, true), c("laptop", laptop, true)), steps);
        assertTrue(r.ok);
        assertEquals("laptop", r.computer);
        assertEquals(0, asus.wakes);
        assertTrue("it said what it was doing", steps.seen.contains("Could not use nk-pc-asus. Trying laptop..."));
        assertTrue(steps.seen.get(0).startsWith("nk-pc-asus: "));
        assertTrue(steps.seen.get(steps.seen.size() - 1).startsWith("laptop: "));
    }

    @Test
    public void aFirstChoiceThatNeverWakesFallsBackToTheNextAfterTheTimeout() {
        FakePc asus = new FakePc();
        asus.awakeAt = -1;
        FakePc laptop = new FakePc();
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, true), c("laptop", laptop, true)), new Steps());
        assertTrue(r.ok);
        assertEquals("laptop", r.computer);
        assertTrue(asus.clock >= PcStarter.WAKE_TIMEOUT_MS);
    }

    @Test
    public void aComputerThatIsAwakeButCannotStartClaudeFallsBackToo() {
        FakePc asus = new FakePc();
        asus.reply = PcStarter.Reply.failure("The claude command was not found on the PC.", false);
        FakePc laptop = new FakePc();
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, true), c("laptop", laptop, true)), new Steps());
        assertTrue(r.ok);
        assertEquals("laptop", r.computer);
    }

    @Test
    public void whenNothingWorksEveryReasonIsListed() {
        FakePc asus = new FakePc();
        asus.awakeAt = -1;
        asus.wakeable = false;
        FakePc laptop = new FakePc();
        laptop.reply = PcStarter.Reply.failure("The claude command was not found on the PC.", false);
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, false), c("laptop", laptop, true)), new Steps());
        assertFalse(r.ok);
        assertFalse(r.cancelled);
        assertTrue(r.message, r.message.contains("nk-pc-asus: Cannot reach the PC"));
        assertTrue(r.message, r.message.contains("laptop: The claude command was not found on the PC."));
    }

    @Test
    public void cancellingStopsEverythingAndDoesNotTryTheNextComputer() {
        FakePc asus = new FakePc();
        asus.awakeAt = -1;
        asus.cancel = true;
        FakePc laptop = new FakePc();
        PcPlanner.Result r = PcPlanner.run(Arrays.asList(c("nk-pc-asus", asus, true), c("laptop", laptop, true)), new Steps());
        assertTrue(r.cancelled);
        assertFalse(r.ok);
        assertEquals(0, laptop.pings);
    }

    @Test
    public void aSingleComputerGetsPlainMessagesWithoutItsNameInFront() {
        FakePc asus = new FakePc();
        Steps steps = new Steps();
        PcPlanner.run(Collections.singletonList(c("nk-pc-asus", asus, true)), steps);
        assertEquals("Looking for your PC...", steps.seen.get(0));
    }

    @Test
    public void noComputersMeansTellTheOwnerToAddOne() {
        PcPlanner.Result r = PcPlanner.run(Collections.<PcPlanner.Candidate>emptyList(), new Steps());
        assertFalse(r.ok);
        assertTrue(r.message, r.message.contains("add one"));
    }
}
