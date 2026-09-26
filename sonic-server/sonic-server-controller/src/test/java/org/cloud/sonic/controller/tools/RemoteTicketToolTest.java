package org.cloud.sonic.controller.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.cloud.sonic.controller.models.domain.Agents;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteTicketToolTest {

    private final Agents agent = new Agents().setId(7).setSecretKey("agent-7-key");

    @Test
    void ticketIsValidForTheDeviceItWasIssuedFor() {
        String ticket = RemoteTicketTool.issue(agent, "serial-1", "alice");

        assertEquals("alice", RemoteTicketTool.verify(agent, "serial-1", ticket));
    }

    @Test
    void ticketDoesNotOpenAnotherDevice() {
        String ticket = RemoteTicketTool.issue(agent, "serial-1", "alice");

        assertNull(RemoteTicketTool.verify(agent, "serial-2", ticket));
    }

    @Test
    void ticketDoesNotWorkOnAnotherAgent() {
        String ticket = RemoteTicketTool.issue(agent, "serial-1", "alice");
        Agents otherKey = new Agents().setId(7).setSecretKey("other-key");
        Agents otherId = new Agents().setId(8).setSecretKey("agent-7-key");

        assertNull(RemoteTicketTool.verify(otherKey, "serial-1", ticket));
        assertNull(RemoteTicketTool.verify(otherId, "serial-1", ticket));
    }

    @Test
    void expiredTicketIsRejectedOnceTheLeewayHasPassed() {
        long past = System.currentTimeMillis() - (RemoteTicketTool.LEEWAY_SECONDS + 5) * 1000;
        String expired = JWT.create().withSubject("alice")
                .withClaim(RemoteTicketTool.AGENT_CLAIM, 7)
                .withClaim(RemoteTicketTool.UDID_CLAIM, "serial-1")
                .withExpiresAt(new Date(past))
                .sign(Algorithm.HMAC256("agent-7-key"));

        assertNull(RemoteTicketTool.verify(agent, "serial-1", expired));
    }

    @Test
    void agentsAndServerAgreeOnTheTicketFormat() {
        // Same hand-signed ticket as sonic-agent's RemoteTicketVerifierTest (expires in 2100).
        String contractTicket = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                + "eyJzdWIiOiJhbGljZSIsImFpZCI6NywidWRJZCI6InNlcmlhbC0xIiwiZXhwIjo0MTAyNDQ0ODAwfQ."
                + "_NC2LBtFzuK2KwBbAuKHAwPqIBG58lGLrt7o7To1FXA";
        Agents contractAgent = new Agents().setId(7).setSecretKey("contract-test-agent-key");

        assertEquals("alice", RemoteTicketTool.verify(contractAgent, "serial-1", contractTicket));
    }

    @Test
    void garbageAndMissingInputsAreRejected() {
        assertNull(RemoteTicketTool.verify(agent, "serial-1", "not-a-ticket"));
        assertNull(RemoteTicketTool.verify(agent, "serial-1", null));
        assertNull(RemoteTicketTool.verify(null, "serial-1", RemoteTicketTool.issue(agent, "serial-1", "alice")));
        assertNull(RemoteTicketTool.verify(new Agents().setId(7).setSecretKey(""), "serial-1", "x.y.z"));
    }
}
