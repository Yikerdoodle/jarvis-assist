package io.github.jarvisassist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Chooses which text-to-speech voice sounds best. Plain Java so it can be unit-tested: the Android voice list
 * is turned into {@link Option}s first.
 *
 * Order of preference: the wanted language and region (British English by default), then any English, then
 * higher quality over lower, then lower latency. Online voices are skipped unless the owner allows them,
 * because they send the text of each reply to Google.
 */
final class VoicePick {
    private VoicePick() {
    }

    static final class Option {
        final String name;
        final String languageTag;
        final int quality;   // Android: 100 very low .. 500 very high
        final int latency;   // Android: 100 very low .. 500 very high (lower is better)
        final boolean needsNetwork;

        Option(String name, String languageTag, int quality, int latency, boolean needsNetwork) {
            this.name = name;
            this.languageTag = languageTag;
            this.quality = quality;
            this.latency = latency;
            this.needsNetwork = needsNetwork;
        }
    }

    /** Higher is better; -1 means "never pick this one". */
    static int score(Option o, String wantedTag, boolean allowNetwork) {
        if (o.needsNetwork && !allowNetwork) {
            return -1;
        }
        String wantedLanguage = new Locale.Builder().setLanguageTag(wantedTag).build().getLanguage();
        Locale voice = Locale.forLanguageTag(o.languageTag.replace('_', '-'));
        int languageScore;
        if (voice.toLanguageTag().equalsIgnoreCase(wantedTag)) {
            languageScore = 2000;
        } else if (voice.getLanguage().equalsIgnoreCase(wantedLanguage)) {
            languageScore = 1000;
        } else {
            return -1;
        }
        return languageScore + o.quality - o.latency / 10;
    }

    /** All usable options, best first. */
    static List<Option> ranked(List<Option> options, String wantedTag, boolean allowNetwork) {
        List<Option> usable = new ArrayList<>();
        for (Option o : options) {
            if (score(o, wantedTag, allowNetwork) >= 0) {
                usable.add(o);
            }
        }
        Collections.sort(usable, new Comparator<Option>() {
            @Override
            public int compare(Option a, Option b) {
                int byScore = score(b, wantedTag, allowNetwork) - score(a, wantedTag, allowNetwork);
                return byScore != 0 ? byScore : a.name.compareTo(b.name);
            }
        });
        return usable;
    }

    static Option best(List<Option> options, String wantedTag, boolean allowNetwork) {
        List<Option> usable = ranked(options, wantedTag, allowNetwork);
        return usable.isEmpty() ? null : usable.get(0);
    }
}
