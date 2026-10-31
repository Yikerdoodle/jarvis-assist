package io.github.jarvisassist;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Connects the voice choice (see {@link VoicePick}) to Android's text-to-speech engine. */
final class Voices {
    private Voices() {
    }

    private static VoicePick.Option option(Voice v) {
        return new VoicePick.Option(v.getName(), v.getLocale().toLanguageTag(), v.getQuality(), v.getLatency(),
                v.isNetworkConnectionRequired());
    }

    private static List<Voice> installed(TextToSpeech tts) {
        Set<Voice> all = tts.getVoices();
        return all == null ? new ArrayList<Voice>() : new ArrayList<>(all);
    }

    /** The English language tags the engine has voices for, US English first, then British, then the rest. */
    static List<String> englishTags(TextToSpeech tts) {
        Set<String> tags = new TreeSet<>();
        for (Voice v : installed(tts)) {
            if (v.getLocale().getLanguage().equals("en")) {
                tags.add(v.getLocale().toLanguageTag());
            }
        }
        List<String> sorted = new ArrayList<>(tags);
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int byRank = rank(a) - rank(b);
                return byRank != 0 ? byRank : a.compareTo(b);
            }

            private int rank(String tag) {
                return tag.equals("en-US") ? 0 : tag.equals("en-GB") ? 1 : 2;
            }
        });
        return sorted;
    }

    static String languageLabel(String tag) {
        return Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH);
    }

    /** Usable voices for the settings list, best first. */
    static List<Voice> ranked(TextToSpeech tts, boolean allowOnline, String wantedTag) {
        List<Voice> voices = installed(tts);
        List<VoicePick.Option> options = new ArrayList<>();
        for (Voice v : voices) {
            options.add(option(v));
        }
        List<Voice> out = new ArrayList<>();
        for (VoicePick.Option o : VoicePick.ranked(options, wantedTag, allowOnline)) {
            for (Voice v : voices) {
                if (v.getName().equals(o.name)) {
                    out.add(v);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Sets the speed and the voice. A voice chosen in Settings wins. Otherwise the engine's own default voice for the
     * chosen language is used, which is the one picked in the engine's own settings (for Google, its voice list).
     * If that default would need the internet and the owner has not allowed online voices, the best offline one is
     * used instead.
     */
    static void apply(TextToSpeech tts, Prefs prefs) {
        tts.setSpeechRate(prefs.ttsRate());
        String wanted = prefs.ttsVoice();
        if (!wanted.isEmpty()) {
            for (Voice v : installed(tts)) {
                if (v.getName().equals(wanted)) {
                    tts.setVoice(v);
                    return;
                }
            }
        }
        tts.setLanguage(Locale.forLanguageTag(prefs.ttsLanguage()));
        Voice current = tts.getVoice();
        if (current != null && current.isNetworkConnectionRequired() && !prefs.ttsOnlineVoices()) {
            List<Voice> offline = ranked(tts, false, prefs.ttsLanguage());
            if (!offline.isEmpty()) {
                tts.setVoice(offline.get(0));
            }
        }
    }

    static String quality(int q) {
        if (q >= Voice.QUALITY_VERY_HIGH) {
            return "very high";
        } else if (q >= Voice.QUALITY_HIGH) {
            return "high";
        } else if (q >= Voice.QUALITY_NORMAL) {
            return "normal";
        } else if (q >= Voice.QUALITY_LOW) {
            return "low";
        }
        return "very low";
    }

    static String label(Voice v) {
        return v.getLocale().toLanguageTag() + " · " + quality(v.getQuality())
                + (v.isNetworkConnectionRequired() ? " · online" : " · offline") + " · " + v.getName();
    }
}
