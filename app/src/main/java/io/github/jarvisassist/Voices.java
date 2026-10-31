package io.github.jarvisassist;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Connects the voice choice (see {@link VoicePick}) to Android's text-to-speech engine. */
final class Voices {
    /** British English, matching the owner's spelling; any other English is the fallback. */
    static final String WANTED_TAG = "en-GB";

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

    /** Usable voices for the settings list, best first. */
    static List<Voice> ranked(TextToSpeech tts, boolean allowOnline) {
        List<Voice> voices = installed(tts);
        List<VoicePick.Option> options = new ArrayList<>();
        for (Voice v : voices) {
            options.add(option(v));
        }
        List<Voice> out = new ArrayList<>();
        for (VoicePick.Option o : VoicePick.ranked(options, WANTED_TAG, allowOnline)) {
            for (Voice v : voices) {
                if (v.getName().equals(o.name)) {
                    out.add(v);
                    break;
                }
            }
        }
        return out;
    }

    /** Uses the owner's chosen voice if it is still available, otherwise the best one, and sets the speed. */
    static void apply(TextToSpeech tts, Prefs prefs) {
        tts.setSpeechRate(prefs.ttsRate());
        List<Voice> ranked = ranked(tts, prefs.ttsOnlineVoices());
        Voice chosen = null;
        String wanted = prefs.ttsVoice();
        if (!wanted.isEmpty()) {
            for (Voice v : installed(tts)) {
                if (v.getName().equals(wanted)) {
                    chosen = v;
                    break;
                }
            }
        }
        if (chosen == null && !ranked.isEmpty()) {
            chosen = ranked.get(0);
        }
        if (chosen != null) {
            tts.setVoice(chosen);
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
