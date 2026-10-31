package io.github.jarvisassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VoicePickTest {
    private static VoicePick.Option v(String name, String tag, int quality, int latency, boolean network) {
        return new VoicePick.Option(name, tag, quality, latency, network);
    }

    private final List<VoicePick.Option> voices = Arrays.asList(
            v("us-low", "en-US", 200, 200, false),
            v("us-high", "en-US", 400, 300, false),
            v("gb-normal", "en-GB", 300, 200, false),
            v("gb-high", "en-GB", 400, 300, false),
            v("gb-online", "en-GB", 500, 400, true),
            v("fr-high", "fr-FR", 500, 200, false));

    @Test
    public void prefersTheWantedRegionOverHigherQualityElsewhere() {
        assertEquals("gb-high", VoicePick.best(voices, "en-GB", false).name);
    }

    @Test
    public void fallsBackToOtherEnglishWhenTheRegionIsMissing() {
        List<VoicePick.Option> noGb = Arrays.asList(voices.get(0), voices.get(1), voices.get(5));
        assertEquals("us-high", VoicePick.best(noGb, "en-GB", false).name);
    }

    @Test
    public void onlineVoicesAreSkippedUnlessAllowed() {
        assertEquals("gb-high", VoicePick.best(voices, "en-GB", false).name);
        assertEquals("gb-online", VoicePick.best(voices, "en-GB", true).name);
    }

    @Test
    public void otherLanguagesAreNeverChosen() {
        List<VoicePick.Option> onlyFrench = Collections.singletonList(voices.get(5));
        assertNull(VoicePick.best(onlyFrench, "en-GB", true));
    }

    @Test
    public void rankedListIsBestFirstAndExcludesUnusableVoices() {
        List<VoicePick.Option> ranked = VoicePick.ranked(voices, "en-GB", false);
        assertEquals(Arrays.asList("gb-high", "gb-normal", "us-high", "us-low"), names(ranked));
        assertTrue(!names(ranked).contains("fr-high") && !names(ranked).contains("gb-online"));
    }

    @Test
    public void lowerLatencyBreaksAQualityTie() {
        List<VoicePick.Option> tie = Arrays.asList(v("slow", "en-GB", 400, 400, false), v("quick", "en-GB", 400, 200, false));
        assertEquals("quick", VoicePick.best(tie, "en-GB", false).name);
    }

    @Test
    public void underscoreTagsFromOlderEnginesStillMatch() {
        List<VoicePick.Option> old = Arrays.asList(v("old-gb", "en_GB", 300, 200, false), v("us", "en_US", 500, 200, false));
        assertEquals("old-gb", VoicePick.best(old, "en-GB", false).name);
    }

    @Test
    public void emptyListGivesNothing() {
        assertNull(VoicePick.best(Collections.<VoicePick.Option>emptyList(), "en-GB", true));
    }

    private static List<String> names(List<VoicePick.Option> options) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (VoicePick.Option o : options) {
            out.add(o.name);
        }
        return out;
    }
}
