package org.cloud.sonic.agent.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RemoteTicketVerifierTest {
    /**
     * Test-only public key and a ticket signed with its private key for user alice, agent 7,
     * device serial-1, expiring in 2100. sonic-server's RemoteTicketToolTest checks the same
     * ticket, so the server and agents cannot drift apart on the ticket format.
     */
    static final String CONTRACT_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHq9/WfXCASs6sTZgQXQ7ZT9aMJ6kQntIF45qT/Rof66758dPsfdpogPnQVU9QjZFPcA5oVx8CfX1yo+N6AhHcQ==";
    static final String CONTRACT_TICKET = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzdWIiOiJhbGljZSIsImFpZCI6NywidWRJZCI6InNlcmlhbC0xIiwiZXhwIjo0MTAyNDQ0ODAwLCJqdGkiOiJjb250cmFjdC10aWNrZXQifQ."
            + "QpYFBm1AKbKNWszteNLOYnm3OQ6NelcFuM2nXvec4ZLu8lnwdrERPBhUDzEZOj5w0X7m3yUBZ76YoLQWe-S7SQ";

    private int agentIdBefore;

    @Before
    public void setUp() {
        agentIdBefore = BytesTool.agentId;
        BytesTool.agentId = 7;
        RemoteTicketVerifier.setPublicKey(CONTRACT_PUBLIC_KEY);
    }

    @After
    public void tearDown() {
        BytesTool.agentId = agentIdBefore;
        RemoteTicketVerifier.setPublicKey(null);
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
    public void rejectsTicketsNotSignedWithThePlatformKey() throws Exception {
        String hmac = JWT.create().withSubject("alice").withClaim("aid", 7).withClaim("udId", "serial-1")
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                .sign(Algorithm.HMAC256("the-agent's-own-key"));
        KeyPair other = newKeyPair();
        String otherKey = sign(other, new Date(System.currentTimeMillis() + 60_000));

        assertNull(RemoteTicketVerifier.verify(hmac, "serial-1"));
        assertNull(RemoteTicketVerifier.verify(otherKey, "serial-1"));
    }

    @Test
    public void rejectsExpiredTicket() throws Exception {
        KeyPair pair = newKeyPair();
        RemoteTicketVerifier.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        long past = System.currentTimeMillis() - (RemoteTicketVerifier.LEEWAY_SECONDS + 5) * 1000;

        assertNull(RemoteTicketVerifier.verify(sign(pair, new Date(past)), "serial-1"));
        assertEquals("alice", RemoteTicketVerifier.verify(sign(pair, new Date(System.currentTimeMillis() + 60_000)), "serial-1"));
    }

    @Test
    public void refusesEverythingUntilTheServerSentAKey() {
        RemoteTicketVerifier.setPublicKey(null);
        assertNull(RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-1"));
        RemoteTicketVerifier.setPublicKey("not-a-key");
        assertNull(RemoteTicketVerifier.verify(CONTRACT_TICKET, "serial-1"));
        RemoteTicketVerifier.setPublicKey(CONTRACT_PUBLIC_KEY);
        assertNull(RemoteTicketVerifier.verify("not-a-ticket", "serial-1"));
        assertNull(RemoteTicketVerifier.verify(null, "serial-1"));
    }

    static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair pair, Date expiresAt) {
        return JWT.create().withSubject("alice").withClaim("aid", 7).withClaim("udId", "serial-1")
                .withExpiresAt(expiresAt)
                .sign(Algorithm.ECDSA256((ECPublicKey) pair.getPublic(), (ECPrivateKey) pair.getPrivate()));
    }
}
