package io.github.startclaude;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeOnLanTest {

    private static final byte[] MAC = {(byte) 0xA8, (byte) 0xA1, 0x59, 0x12, 0x34, 0x56};

    @Test
    public void readsTheCommonWaysOfWritingAMac() {
        assertArrayEquals(MAC, WakeOnLan.parseMac("A8:A1:59:12:34:56"));
        assertArrayEquals(MAC, WakeOnLan.parseMac("a8-a1-59-12-34-56"));
        assertArrayEquals(MAC, WakeOnLan.parseMac("  A8A159123456 \n"));
    }

    @Test
    public void refusesThingsThatAreNotAMac() {
        assertNull(WakeOnLan.parseMac(null));
        assertNull(WakeOnLan.parseMac(""));
        assertNull(WakeOnLan.parseMac("A8:A1:59:12:34"));        // too short
        assertNull(WakeOnLan.parseMac("A8:A1:59:12:34:56:78"));  // too long
        assertNull(WakeOnLan.parseMac("G8:A1:59:12:34:56"));     // not hex
        assertNull(WakeOnLan.parseMac("00:00:00:00:00:00"));
        assertNull(WakeOnLan.parseMac("FF:FF:FF:FF:FF:FF"));
        assertNull(WakeOnLan.parseMac("192.168.1.20"));
    }

    @Test
    public void formatsWithColonsAndCapitals() {
        assertEquals("A8:A1:59:12:34:56", WakeOnLan.format(MAC));
        assertEquals("00:0A:FF:01:02:03", WakeOnLan.format(new byte[]{0, 10, (byte) 0xFF, 1, 2, 3}));
    }

    @Test
    public void magicPacketIsSixFFsThenSixteenCopiesOfTheMac() {
        byte[] packet = WakeOnLan.magicPacket(MAC);
        assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) {
            assertEquals((byte) 0xFF, packet[i]);
        }
        for (int copy = 0; copy < 16; copy++) {
            for (int i = 0; i < 6; i++) {
                assertEquals(MAC[i], packet[6 + copy * 6 + i]);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void magicPacketNeedsASixByteMac() {
        WakeOnLan.magicPacket(new byte[5]);
    }

    @Test
    public void broadcastAddressForCommonHomeNetworks() {
        assertArrayEquals(ip(192, 168, 1, 255), WakeOnLan.broadcast(ip(192, 168, 1, 37), 24));
        assertArrayEquals(ip(192, 168, 3, 255), WakeOnLan.broadcast(ip(192, 168, 2, 37), 23));
        assertArrayEquals(ip(10, 255, 255, 255), WakeOnLan.broadcast(ip(10, 0, 4, 9), 8));
        assertArrayEquals(ip(192, 168, 1, 127), WakeOnLan.broadcast(ip(192, 168, 1, 37), 25));
        assertArrayEquals(ip(192, 168, 1, 255), WakeOnLan.broadcast(ip(192, 168, 1, 200), 25));
        assertArrayEquals(ip(255, 255, 255, 255), WakeOnLan.broadcast(ip(1, 2, 3, 4), 0));
        assertArrayEquals(ip(1, 2, 3, 4), WakeOnLan.broadcast(ip(1, 2, 3, 4), 32));
    }

    @Test(expected = IllegalArgumentException.class)
    public void broadcastRejectsAnImpossiblePrefix() {
        WakeOnLan.broadcast(ip(192, 168, 1, 1), 33);
    }

    @Test
    public void recognisesTailscaleAddresses() {
        assertTrue(WakeOnLan.isTailnetAddress(ip(100, 64, 0, 1)));
        assertTrue(WakeOnLan.isTailnetAddress(ip(100, 101, 102, 103)));
        assertTrue(WakeOnLan.isTailnetAddress(ip(100, 127, 255, 255)));
        assertFalse(WakeOnLan.isTailnetAddress(ip(100, 63, 0, 1)));
        assertFalse(WakeOnLan.isTailnetAddress(ip(100, 128, 0, 1)));
        assertFalse(WakeOnLan.isTailnetAddress(ip(192, 168, 1, 5)));
    }

    private static byte[] ip(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }
}
