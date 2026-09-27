package org.cloud.sonic.controller.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.cloud.sonic.controller.models.domain.Agents;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTicketToolTest {
    /**
     * Test-only key pair and a ticket signed with it for user alice, agent 7, device serial-1,
     * expiring in 2100. sonic-agent's RemoteTicketVerifierTest checks the same ticket with the same
     * public key, so the server and agents cannot drift apart on the ticket format.
     */
    static final String CONTRACT_PRIVATE_KEY = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBM+hPeXnHAC2K6qq/lHo14Lwe+p0HcS3RUHYc3cwAQHg==";
    static final String CONTRACT_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHq9/WfXCASs6sTZgQXQ7ZT9aMJ6kQntIF45qT/Rof66758dPsfdpogPnQVU9QjZFPcA5oVx8CfX1yo+N6AhHcQ==";
    static final String CONTRACT_TICKET = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzdWIiOiJhbGljZSIsImFpZCI6NywidWRJZCI6InNlcmlhbC0xIiwiZXhwIjo0MTAyNDQ0ODAwLCJqdGkiOiJjb250cmFjdC10aWNrZXQifQ."
            + "QpYFBm1AKbKNWszteNLOYnm3OQ6NelcFuM2nXvec4ZLu8lnwdrERPBhUDzEZOj5w0X7m3yUBZ76YoLQWe-S7SQ";

    private final KeyPair keyPair = RemoteTicketKeys.generate();
    private final RemoteTicketTool tool = toolWith(keyPair);
    private final Agents agent = new Agents().setId(7).setSecretKey("agent-7-key");

    @Test
    void ticketIsValidForTheDeviceItWasIssuedFor() {
        String ticket = tool.issue(agent, "serial-1", "alice");

        assertEquals("alice", tool.verify(agent, "serial-1", ticket));
        assertEquals("ES256", JWT.decode(ticket).getAlgorithm());
    }

    @Test
    void ticketDoesNotOpenAnotherDevice() {
        String ticket = tool.issue(agent, "serial-1", "alice");

        assertNull(tool.verify(agent, "serial-1-other", ticket));
    }

    @Test
    void ticketDoesNotWorkOnAnotherAgent() {
        String ticket = tool.issue(agent, "serial-1", "alice");

        assertNull(tool.verify(new Agents().setId(8), "serial-1", ticket));
    }

    @Test
    void agentsCannotIssueTickets() {
        // What an agent could sign on its own: HMAC with its key (the old scheme), or a key of its own.
        String hmac = JWT.create().withSubject("alice").withClaim("aid", 7).withClaim("udId", "serial-1")
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000)).withJWTId("x")
                .sign(Algorithm.HMAC256("agent-7-key"));
        String ownKey = toolWith(RemoteTicketKeys.generate()).issue(agent, "serial-1", "alice");

        assertNull(tool.verify(agent, "serial-1", hmac));
        assertNull(tool.verify(agent, "serial-1", ownKey));
    }

    @Test
    void expiredTicketIsRejectedOnceTheLeewayHasPassed() {
        long past = System.currentTimeMillis() - (RemoteTicketTool.LEEWAY_SECONDS + 5) * 1000;
        String expired = JWT.create().withSubject("alice")
                .withClaim(RemoteTicketTool.AGENT_CLAIM, 7)
                .withClaim(RemoteTicketTool.UDID_CLAIM, "serial-1")
                .withExpiresAt(new Date(past))
                .sign(Algorithm.ECDSA256((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate()));

        assertNull(tool.verify(agent, "serial-1", expired));
    }

    @Test
    void aTicketOpensOneSession() {
        String ticket = tool.issue(agent, "serial-1", "alice");

        assertTrue(tool.markUsedForSession(tool.decode(agent, "serial-1", ticket)));
        assertFalse(tool.markUsedForSession(tool.decode(agent, "serial-1", ticket)));
        assertTrue(tool.markUsedForSession(tool.decode(agent, "serial-1", tool.issue(agent, "serial-1", "alice"))));
    }

    @Test
    void agentsAndServerAgreeOnTheTicketFormat() {
        RemoteTicketTool contract = new RemoteTicketTool(new RemoteTicketKeys(
                RemoteTicketKeys.decodePublic(CONTRACT_PUBLIC_KEY), RemoteTicketKeys.decodePrivate(CONTRACT_PRIVATE_KEY)));

        assertEquals("alice", contract.verify(agent, "serial-1", CONTRACT_TICKET));
    }

    @Test
    void garbageAndMissingInputsAreRejected() {
        assertNull(tool.verify(agent, "serial-1", "not-a-ticket"));
        assertNull(tool.verify(agent, "serial-1", null));
        assertNull(tool.verify(null, "serial-1", tool.issue(agent, "serial-1", "alice")));
        assertNull(tool.verify(new Agents(), "serial-1", tool.issue(agent, "serial-1", "alice")));
    }

    private static RemoteTicketTool toolWith(KeyPair keyPair) {
        return new RemoteTicketTool(new RemoteTicketKeys((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate()));
    }
}
