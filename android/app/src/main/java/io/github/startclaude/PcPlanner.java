package io.github.startclaude;

import java.util.ArrayList;
import java.util.List;

/**
 * Goes down the owner's list of computers, first choice first, and starts Claude on the first one that works:
 * one that is awake is used at once, one that is asleep is woken (if it can be) and waited for, and one that
 * cannot be reached or woken is skipped for the next. No Android code, so the order of play can be tested.
 */
final class PcPlanner {
    private PcPlanner() {
    }

    static final class Candidate {
        final String name;
        final PcStarter.Io io;
        final boolean haveMac;

        Candidate(String name, PcStarter.Io io, boolean haveMac) {
            this.name = name;
            this.io = io;
            this.haveMac = haveMac;
        }
    }

    static final class Result {
        final boolean ok;
        final boolean cancelled;
        final String computer;  // where Claude is running, when ok
        final String state;     // "started" or "already-running", when ok
        final String message;   // what to tell the owner, when not ok

        private Result(boolean ok, boolean cancelled, String computer, String state, String message) {
            this.ok = ok;
            this.cancelled = cancelled;
            this.computer = computer;
            this.state = state;
            this.message = message;
        }
    }

    static Result run(List<Candidate> candidates, PcStarter.Listener listener) {
        if (candidates.isEmpty()) {
            return new Result(false, false, "", "", "No computers yet. Open the app and add one.");
        }
        boolean several = candidates.size() > 1;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            final Candidate candidate = candidates.get(i);
            PcStarter.Outcome outcome = PcStarter.run(candidate.io, candidate.haveMac,
                    text -> listener.onStep(several ? candidate.name + ": " + text : text));
            if (outcome.ok) {
                return new Result(true, false, candidate.name, outcome.state, "");
            }
            if (outcome.cancelled) {
                return new Result(false, true, "", "", "");
            }
            problems.add(several ? candidate.name + ": " + outcome.message : outcome.message);
            if (i + 1 < candidates.size()) {
                listener.onStep("Could not use " + candidate.name + ". Trying " + candidates.get(i + 1).name + "...");
            }
        }
        return new Result(false, false, "", "", String.join("\n\n", problems));
    }
}
