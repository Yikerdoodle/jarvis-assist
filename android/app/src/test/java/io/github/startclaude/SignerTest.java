package io.github.startclaude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignerTest {

    @Test
    public void producesExactlyWhatThePcProgramChecks() {
        // The same fixed example is in pc/tests/test_security.py (VECTOR). If these two ever disagree, the
        // phone and the PC would no longer understand each other.
        assertEquals("23a2eea30d3f3030c4c972cba3728aa7d3323894b353f0eedbd2de68db9993bc",
                Signer.sign("test-token", "POST", "/v1/remote/start", 1730000000L, "00112233aabbccdd"));
        assertEquals("StartClaude t=1730000000,n=0011223344556677,"
                        + "s=ba80930b0d2e919a3772651b719638757b6d61df1a17ad10afe568973c41e9fa",
                Signer.header("test-token", "GET", "/v1/health", 1730000000L, "0011223344556677"));
    }

    @Test
    public void methodIsCaseInsensitiveButPathAndTokenAreNot() {
        String base = Signer.sign("tok", "POST", "/v1/health", 1L, "aa");
        assertEquals(base, Signer.sign("tok", "post", "/v1/health", 1L, "aa"));
        assertNotEquals(base, Signer.sign("tok", "POST", "/v1/Health", 1L, "aa"));
        assertNotEquals(base, Signer.sign("tok2", "POST", "/v1/health", 1L, "aa"));
        assertNotEquals(base, Signer.sign("tok", "POST", "/v1/health", 2L, "aa"));
        assertNotEquals(base, Signer.sign("tok", "POST", "/v1/health", 1L, "ab"));
    }

    @Test
    public void headerNeverContainsTheSecretAndHasANewNonceEachTime() {
        String first = Signer.header("super-secret-token", "POST", "/v1/remote/start");
        String second = Signer.header("super-secret-token", "POST", "/v1/remote/start");
        assertFalse(first.contains("super-secret-token"));
        assertTrue(first.matches("StartClaude t=\\d{10},n=[0-9a-f]{16},s=[0-9a-f]{64}"));
        assertNotEquals(first, second);
    }
}
