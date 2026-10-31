package io.github.startclaude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AddressTest {

    @Test
    public void allowedAddresses() {
        for (String ok : new String[]{
                "http://100.100.2.2:8788", "http://100.64.0.1:8788", "http://100.127.255.255:8788",
                "http://nk-pc-asus:8788", "http://NK-PC-ASUS:8788", "http://nk-pc-asus.tail1234.ts.net:8788",
                "http://192.168.1.20:8788", "http://10.0.0.5:8788", "http://172.16.0.9:8788",
                "http://172.31.255.1:8788", "http://127.0.0.1:8788", "http://localhost:8788",
                "https://anything.example.com"}) {
            assertTrue(ok, Address.isAllowedBase(ok));
        }
    }

    @Test
    public void refusedAddresses() {
        for (String bad : new String[]{
                "http://8.8.8.8:8788", "http://100.63.0.1:8788", "http://100.128.0.1:8788",
                "http://172.15.0.1:8788", "http://172.32.0.1:8788", "http://192.169.1.1:8788",
                "http://example.com:8788",                // a real internet name, in plain http
                "http://my.pc.local:8788", "ftp://100.100.2.2", "nk-pc-asus", "", "http://", "http://-bad-:8788"}) {
            assertFalse(bad, Address.isAllowedBase(bad));
        }
    }

    @Test
    public void normalisingWhatTheOwnerTypes() {
        assertEquals("http://nk-pc-asus:8788", Address.normalize("nk-pc-asus"));
        assertEquals("http://nk-pc-asus:8788", Address.normalize("  nk-pc-asus  "));
        assertEquals("http://100.100.2.2:8788", Address.normalize("100.100.2.2"));
        assertEquals("http://100.100.2.2:9000", Address.normalize("100.100.2.2:9000"));
        assertEquals("http://nk-pc-asus:8788", Address.normalize("http://nk-pc-asus"));
        assertEquals("http://nk-pc-asus:8788", Address.normalize("http://nk-pc-asus:8788/"));
        assertEquals("", Address.normalize("   "));
        assertEquals("", Address.normalize(null));
    }

    @Test
    public void whatTheOwnerTypesIsUsuallyAcceptedAfterNormalising() {
        assertTrue(Address.isAllowedBase(Address.normalize("nk-pc-asus")));
        assertTrue(Address.isAllowedBase(Address.normalize("100.100.2.2")));
        assertFalse(Address.isAllowedBase(Address.normalize("8.8.8.8")));
    }

    @Test
    public void networkRanges() {
        assertTrue(Address.isHomeNetworkIp("192.168.0.1"));
        assertTrue(Address.isHomeNetworkIp("172.20.1.1"));
        assertFalse(Address.isHomeNetworkIp("100.100.2.2"));
        assertFalse(Address.isHomeNetworkIp("not an ip"));
        assertFalse(Address.isHomeNetworkIp("192.168.1"));
        assertFalse(Address.isHomeNetworkIp("192.168.1.300"));
        assertTrue(Address.isTailnetIp("100.100.2.2"));
        assertFalse(Address.isTailnetIp("192.168.1.1"));
    }
}
