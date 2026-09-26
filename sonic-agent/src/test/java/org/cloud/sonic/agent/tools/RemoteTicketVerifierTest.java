package org.cloud.sonic.agent.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RemoteTicketVerifierTest {
    /**
     * Signed by hand with key "contract-test-agent-key" for user alice, agent 7, device serial-1,
     * expiring in 2100. sonic-server's RemoteTicketToolTest checks the same ticket, so the two
     * sides cannot drift apart on the claim format.
     */
    static final String CONTRACT_TICKET = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzdWIiOiJhbGljZSIsImFpZCI6NywidWRJZCI6InNlcmlhbC0xIiwiZXhwIjo0MTAyNDQ0ODAwfQ."
            + "_NC2LBtFzuK2KwBbAuKHAwPqIBG58lGLrt7o7To1FXA";
    private static final String KEY = "contract-test-agent-key";

    private int agentIdBefore;

    @Before
    public void setUp() {
        agentIdBefore = BytesTool.agentId;
        BytesTool.agentId = 7;
        new RemoteTicketVerifier().setKey(KEY);
    }

    @After
    public void tearDown() {
        BytesTool.agentId = agentIdBefore;
        new RemoteTicketVerifier().setKey(null);
    }

    @Test
    public void acceptsTicketsInTheServersFormat() {
        assertEquals("alice", RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-1"));
    }

    @Test
    public void rejectsTicketForAnotherDevice() {
        assertNull(RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-2"));
    }

    @Test
    public void rejectsTicketForAnotherAgent() {
        BytesTool.agentId = 8;

        assertNull(RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-1"));
    }

    @Test
    public void rejectsTicketSignedWithAnotherKey() {
        new RemoteTicketVerifier().setKey("some-other-agent-key");

        assertNull(RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-1"));
    }

    @Test
    public void rejectsExpiredTicket() {
        String expired = JWT.create().withSubject("alice")
                .withClaim("aid", 7)
                .withClaim("udId", "serial-1")
                .withExpiresAt(new Date(System.currentTimeMillis() - (RemoteTicketVerifier.LEEWAY_SECONDS + 5) * 1000))
                .sign(Algorithm.HMAC256(KEY));

        assertNull(RemoteTicketVerifier.verify(expired, "serial-1"));
    }

    @Test
    public void rejectsGarbageAndRefusesWithoutAKey() {
        assertNull(RemoteTicketVerifier.verify("not-a-ticket", "serial-1"));
        assertNull(RemoteTicketVerifier.verify(null, "serial-1"));
        new RemoteTicketVerifier().setKey("");
        assertNull(RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-1"));
    }
}
