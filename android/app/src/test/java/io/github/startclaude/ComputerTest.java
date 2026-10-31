package io.github.startclaude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ComputerTest {

    private static String[] card(String mac, String ip) {
        return new String[]{mac, ip};
    }

    @Test
    public void pairingTakesTheFirstMacAndEveryHomeNetworkAddress() {
        List<String[]> cards = Arrays.asList(
                card("A8:A1:59:12:34:56", "192.168.1.20"),   // the cable
                card("00:11:22:33:44:55", "192.168.1.31"));  // the Wi-Fi
        Computer c = Computer.fromPairing("http://nk-pc-asus:8788", "NK-PC-ASUS", "tok", 8788, cards);
        assertEquals("NK-PC-ASUS", c.name);
        assertEquals("http://nk-pc-asus:8788", c.tsBase);
        assertEquals("A8:A1:59:12:34:56", c.mac);
        assertEquals(Arrays.asList("http://192.168.1.20:8788", "http://192.168.1.31:8788"), c.lanBases);
        assertEquals("tok", c.token);
    }

    @Test
    public void aCardWithoutAnAddressStillGivesTheMac() {
        Computer c = Computer.fromPairing("http://x:8788", "X", "t", 8788,
                Collections.singletonList(card("a8-a1-59-12-34-56", "")));
        assertEquals("A8:A1:59:12:34:56", c.mac);
        assertTrue(c.lanBases.isEmpty());
    }

    @Test
    public void nonHomeAddressesAndBadMacsAreIgnored() {
        List<String[]> cards = Arrays.asList(
                card("not a mac", "8.8.8.8"),
                card("00:00:00:00:00:00", "100.100.2.2"),
                card("B8:AA:CC:DD:EE:FF", "10.0.0.7"),
                card("B8:AA:CC:DD:EE:00", "10.0.0.7"));
        Computer c = Computer.fromPairing("http://x:8788", "X", "t", 9000, cards);
        assertEquals("B8:AA:CC:DD:EE:FF", c.mac);
        assertEquals(Collections.singletonList("http://10.0.0.7:9000"), c.lanBases);
    }

    @Test
    public void noCardsAtAllStillWorksOverTailscale() {
        Computer c = Computer.fromPairing("http://x:8788", "X", "t", 8788, Collections.<String[]>emptyList());
        assertEquals("", c.mac);
        assertTrue(c.lanBases.isEmpty());
    }
}
