package org.cloud.sonic.relay;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenVerifierTest {
    /**
     * Test-only platform public key, and a ticket (alice, agent 7, serial-1) and a relay token
     * (agent 7) signed with its private key, both expiring in 2100. sonic-server-controller's
     * RemoteTicketToolTest and RelayTokenToolTest, and sonic-agent's RemoteTicketVerifierTest,
     * check the same vectors, so the four components cannot drift apart on the token formats.
     */
    static final String CONTRACT_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHq9/WfXCASs6sTZgQXQ7ZT9aMJ6kQntIF45qT/Rof66758dPsfdpogPnQVU9QjZFPcA5oVx8CfX1yo+N6AhHcQ==";
    static final String CONTRACT_TICKET = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzdWIiOiJhbGljZSIsImFpZCI6NywidWRJZCI6InNlcmlhbC0xIiwiZXhwIjo0MTAyNDQ0ODAwLCJqdGkiOiJjb250cmFjdC10aWNrZXQifQ."
            + "QpYFBm1AKbKNWszteNLOYnm3OQ6NelcFuM2nXvec4ZLu8lnwdrERPBhUDzEZOj5w0X7m3yUBZ76YoLQWe-S7SQ";
    static final String CONTRACT_RELAY_TOKEN = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzY29wZSI6InJlbGF5LWFnZW50IiwiYWlkIjo3LCJleHAiOjQxMDI0NDQ4MDAsImp0aSI6ImNvbnRyYWN0LXJlbGF5LXRva2VuIn0."
            + "WTWQ7B0oqzAbI8LbxgW7zJTi35FfHTqbe_5eBDMa_2KJBKkS7cq6ps3mw7Jl5x-O-rMo4MMlvMGo7EkRmyQPOw";

    private final TokenVerifier contract = new TokenVerifier(TokenVerifier.publicKey(CONTRACT_PUBLIC_KEY));

    @Test
    void acceptsTicketsAndRelayTokensInTheServersFormat() {
        assertEquals(new TokenVerifier.Ticket(7, "alice", "serial-1"), contract.ticket(CONTRACT_TICKET));
        assertEquals(7, contract.agentOf(CONTRACT_RELAY_TOKEN));
    }

    @Test
    void ticketsAndRelayTokensCannotStandInForEachOther() {
        assertNull(contract.agentOf(CONTRACT_TICKET));
        assertNull(contract.ticket(CONTRACT_RELAY_TOKEN));
    }

    @Test
    void rejectsTokensNotSignedWithThePlatformKey() {
        KeyPair other = TestTokens.newKeyPair();

        assertNull(contract.ticket(TestTokens.ticket(other, 7, "serial-1", "alice", Duration.ofMinutes(2))));
        assertNull(contract.agentOf(TestTokens.relayToken(other, 7, Duration.ofMinutes(10))));
        assertNull(contract.agentOf(TestTokens.hmacRelayToken("guessable", 7)));
    }

    @Test
    void rejectsExpiredTokensOnceTheLeewayHasPassed() {
        KeyPair platform = TestTokens.newKeyPair();
        TokenVerifier verifier = new TokenVerifier(TestTokens.publicKey(platform));
        Duration expired = Duration.ofSeconds(-(TokenVerifier.LEEWAY_SECONDS + 5));

        assertNull(verifier.ticket(TestTokens.ticket(platform, 7, "serial-1", "alice", expired)));
        assertNull(verifier.agentOf(TestTokens.relayToken(platform, 7, expired)));
        assertEquals(7, verifier.agentOf(TestTokens.relayToken(platform, 7, Duration.ofSeconds(-5))));
    }

    @Test
    void rejectsGarbage() {
        assertNull(contract.ticket(null));
        assertNull(contract.ticket("not-a-ticket"));
        assertNull(contract.agentOf(null));
        assertNull(contract.agentOf("a.b.c"));
    }

    @Test
    void refusesToStartWithoutThePlatformKey() {
        assertThrows(IllegalStateException.class, () -> TokenVerifier.publicKey(null));
        assertThrows(IllegalStateException.class, () -> TokenVerifier.publicKey(" "));
        assertThrows(IllegalStateException.class, () -> TokenVerifier.publicKey("bm90IGEga2V5"));
    }
}
