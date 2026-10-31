package io.github.jarvisassist;

/**
 * Decides which engine answers: "claude" (through the PC bridge) or "local" (Gemini Nano on the phone).
 * "auto" still means Claude for now. Once the on-device engine has been measured, "auto" will try it first
 * and only reach for Claude when the question needs it or the owner asks for it.
 */
final class Router {
    private Router() {
    }

    /** Returns "claude" or "local", or null if the chosen engine is not set up yet. */
    static String pick(String selected) {
        switch (selected) {
            case "auto":
            case "claude":
                return "claude";
            case "local":
                return "local";
            default:
                return null;
        }
    }
}
