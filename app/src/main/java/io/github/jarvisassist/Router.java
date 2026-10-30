package io.github.jarvisassist;

/**
 * Decides which engine answers. Today Claude (through the PC bridge) is the only engine that exists,
 * so "auto" and "claude" both use it. When the on-device model lands, "auto" will try that first and
 * only reach for Claude when the question needs it or the owner asks for it.
 */
final class Router {
    private Router() {
    }

    /** Returns the bridge engine name to use, or null if the chosen engine is not set up yet. */
    static String pick(String selected) {
        switch (selected) {
            case "auto":
            case "claude":
                return "claude";
            default:
                return null;
        }
    }
}
