package org.cloud.sonic.controller.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.cloud.sonic.controller.models.domain.Agents;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayTokenToolTest {
    /**
     * Relay token for agent 7, expiring in 2100, signed with RemoteTicketToolTest's test-only key.
     * sonic-server-relay's TokenVerifierTest accepts the same token, so the server and the relay
     * cannot drift apart on the token format.
     */
    static final String CONTRACT_RELAY_TOKEN = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzY29wZSI6InJlbGF5LWFnZW50IiwiYWlkIjo3LCJleHAiOjQxMDI0NDQ4MDAsImp0aSI6ImNvbnRyYWN0LXJlbGF5LXRva2VuIn0."
            + "WTWQ7B0oqzAbI8LbxgW7zJTi35FfHTqbe_5eBDMa_2KJBKkS7cq6ps3mw7Jl5x-O-rMo4MMlvMGo7EkRmyQPOw";

    private final KeyPair keyPair = RemoteTicketKeys.generate();
    private final RemoteTicketKeys keys = new RemoteTicketKeys((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate());
    private final RelayTokenTool tool = new RelayTokenTool(keys);

    @Test
    void tokenIsSignedWithThePlatformKeyForTheGivenAgent() {
        long before = System.currentTimeMillis();
        String issued = tool.issue(7);
        long after = System.currentTimeMillis();

        DecodedJWT token = verifyAsRelayToken(keys.publicKey(), issued);

        assertEquals("ES256", token.getAlgorithm());
        assertEquals(7, token.getClaim(RemoteTicketTool.AGENT_CLAIM).asInt());
        assertNotNull(token.getId());
        long expires = token.getExpiresAt().getTime();
        assertTrue(expires > before + (RelayTokenTool.TTL_SECONDS - 5) * 1000
                && expires <= after + RelayTokenTool.TTL_SECONDS * 1000, "expires " + (expires - before) + " ms after issuing");
    }

    @Test
    void relayTokenDoesNotOpenAnyDevice() {
        RemoteTicketTool tickets = new RemoteTicketTool(keys);

        assertNull(tickets.verify(new Agents().setId(7), "serial-1", tool.issue(7)));
    }

    @Test
    void serverAndRelayAgreeOnTheTokenFormat() {
        DecodedJWT token = verifyAsRelayToken(
                RemoteTicketKeys.decodePublic(RemoteTicketToolTest.CONTRACT_PUBLIC_KEY), CONTRACT_RELAY_TOKEN);

        assertEquals(7, token.getClaim(RemoteTicketTool.AGENT_CLAIM).asInt());
    }

    /**
     * The checks sonic-server-relay applies before letting an agent register.
     */
    private static DecodedJWT verifyAsRelayToken(ECPublicKey publicKey, String token) {
        return JWT.require(Algorithm.ECDSA256(publicKey, null))
                .withClaim(RelayTokenTool.SCOPE_CLAIM, RelayTokenTool.RELAY_AGENT_SCOPE)
                .withClaimPresence(RemoteTicketTool.AGENT_CLAIM)
                .build()
                .verify(token);
    }
}
