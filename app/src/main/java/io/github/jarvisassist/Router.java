package io.github.jarvisassist;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides which engine answers: "claude" (through the PC bridge) or "local" (Gemini Nano on the phone).
 *
 * In Auto mode the phone answers by default, because it is instant, free, private and works offline.
 * Claude is used only when the question needs it (the on-device model measured weaker at exact formats,
 * code, careful reasoning and high-stakes topics) or when the owner asks for it by name. If one engine is
 * not available the other answers. The rules are plain and listed here so the choice is always explainable.
 */
final class Router {
    private Router() {
    }

    static final class Choice {
        final String engine;
        final String reason;

        Choice(String engine, String reason) {
            this.engine = engine;
            this.reason = reason;
        }
    }

    private static final int LONG_INPUT = 500;
    private static final String NUMBER_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|twenty|thirty|fifty|hundred";

    private static final Pattern ASK_CLAUDE = Pattern.compile("\\b(ask|use|with|via|try|from)\\s+claude\\b|^\\s*claude\\b");
    private static final Pattern ASK_PHONE = Pattern.compile("\\b(offline|on[- ]device|on the phone|locally|use (gemini )?nano)\\b");
    private static final Pattern EXACT_FORMAT = Pattern.compile("\\bexactly\\b"
            + "|\\b(at least|at most|no more than|no fewer than|no less than|fewer than|more than)\\s+(\\d+|" + NUMBER_WORDS + ")\\b"
            + "|\\b(\\d+|" + NUMBER_WORDS + ")[- ]words?\\b");
    private static final Pattern CODE = Pattern.compile("\\b(code|script|function|regex|sql|python|javascript|java|kotlin|bash"
            + "|powershell|debug|compile|stack ?trace|algorithm|api|json)\\b");
    private static final Pattern HIGH_STAKES = Pattern.compile("\\b(medication|medicine|dosage|dose|overdose|diagnos\\w*|symptoms?"
            + "|legal|lawyer|lawsuit|contract|tax(es)?|invest\\w*|mortgage|loan|insurance)\\b");
    private static final Pattern REASONING = Pattern.compile("\\b(analy[sz]e|analysis|compare|pros and cons|essay|step[- ]by[- ]step"
            + "|in detail|proofread|critique|evaluate|strategy)\\b");

    /** An explicit choice from the engine switch: "claude" or "local", or null if it is not available yet. */
    static String pick(String selected) {
        switch (selected) {
            case "claude":
                return "claude";
            case "local":
                return "local";
            default:
                return null;
        }
    }

    /** Auto mode: which engine, and a short human reason to show next to the answer. */
    static Choice decideAuto(String text, boolean phoneReady, boolean pcReady) {
        String t = text.toLowerCase(Locale.ROOT);
        if (ASK_CLAUDE.matcher(t).find()) {
            return pcReady || !phoneReady ? new Choice("claude", "you asked for Claude")
                    : new Choice("local", "you asked for Claude, but the PC is not reachable");
        }
        if (ASK_PHONE.matcher(t).find()) {
            return phoneReady ? new Choice("local", "you asked for the phone")
                    : new Choice("claude", "the on-device model is not ready");
        }
        if (!phoneReady) {
            return new Choice("claude", "the on-device model is not ready");
        }
        if (!pcReady) {
            return new Choice("local", "the PC is not reachable");
        }
        String why = whyClaude(t, text.length());
        return why != null ? new Choice("claude", why) : new Choice("local", "short, simple question");
    }

    /** The reason this question should go to Claude, or null if the phone can answer it. */
    static String whyClaude(String lowerText, int length) {
        if (length > LONG_INPUT) {
            return "long input";
        }
        if (EXACT_FORMAT.matcher(lowerText).find()) {
            return "exact format wanted";
        }
        if (CODE.matcher(lowerText).find()) {
            return "code or technical";
        }
        if (HIGH_STAKES.matcher(lowerText).find()) {
            return "health, legal or money";
        }
        if (REASONING.matcher(lowerText).find()) {
            return "needs careful reasoning";
        }
        return null;
    }
}
