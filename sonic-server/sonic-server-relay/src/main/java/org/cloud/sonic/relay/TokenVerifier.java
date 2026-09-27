/*
 *   sonic-server  Sonic Cloud Real Machine Platform.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.relay;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks the two kinds of token the relay sees. Both are ES256 JWTs signed with the platform
 * key, and the relay only holds its public key:
 * <ul>
 *     <li>relay tokens, which sonic-server-controller's RelayTokenTool gives an agent over its
 *     authenticated connection so it can register here;</li>
 *     <li>remote tickets (RemoteTicketTool), which browsers put in the path of every
 *     remote-control connection. The agent checks them again; the relay only needs to know
 *     which agent to route to.</li>
 * </ul>
 */
@Component
public class TokenVerifier {
    static final String AGENT_CLAIM = "aid";
    static final String UDID_CLAIM = "udId";
    static final String SCOPE_CLAIM = "scope";
    static final String RELAY_AGENT_SCOPE = "relay-agent";
    /**
     * Tolerated clock difference between the server and this machine.
     */
    static final long LEEWAY_SECONDS = 60;

    private final JWTVerifier relayTokens;
    private final JWTVerifier tickets;

    @Autowired
    public TokenVerifier(RelayProperties properties) {
        this(publicKey(properties.ticketPublicKey()));
    }

    TokenVerifier(ECPublicKey platformKey) {
        Algorithm es256 = Algorithm.ECDSA256(platformKey, null);
        relayTokens = JWT.require(es256)
                .withClaim(SCOPE_CLAIM, RELAY_AGENT_SCOPE)
                .withClaimPresence(AGENT_CLAIM)
                .acceptLeeway(LEEWAY_SECONDS)
                .build();
        tickets = JWT.require(es256)
                .withClaimPresence(AGENT_CLAIM)
                .withClaimPresence(UDID_CLAIM)
                .acceptLeeway(LEEWAY_SECONDS)
                .build();
    }

    /**
     * @return the agent a valid, unexpired relay token was issued to, or null
     */
    public Integer agentOf(String relayToken) {
        if (relayToken == null) {
            return null;
        }
        try {
            return validAgentId(relayTokens.verify(relayToken).getClaim(AGENT_CLAIM).asInt());
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    /**
     * @return the ticket if it is valid and unexpired, or null. Relay tokens are not tickets.
     */
    public Ticket ticket(String ticket) {
        if (ticket == null) {
            return null;
        }
        try {
            DecodedJWT decoded = tickets.verify(ticket);
            Integer agentId = validAgentId(decoded.getClaim(AGENT_CLAIM).asInt());
            if (agentId == null || !decoded.getClaim(SCOPE_CLAIM).isMissing()) {
                return null;
            }
            return new Ticket(agentId, decoded.getSubject(), decoded.getClaim(UDID_CLAIM).asString());
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    private static Integer validAgentId(Integer agentId) {
        return agentId != null && agentId > 0 ? agentId : null;
    }

    static ECPublicKey publicKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Set sonic.relay.ticket-public-key (TICKET_PUBLIC_KEY) to the platform's "
                    + "ticket public key, the same one sonic-server-controller is configured with.");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
        } catch (Exception e) {
            throw new IllegalStateException("sonic.relay.ticket-public-key is not a base64 X.509 EC public key", e);
        }
    }

    /**
     * @param agentId the agent the ticket lets its user connect to
     * @param user    who the ticket was issued to
     * @param udId    the device on that agent
     */
    public record Ticket(int agentId, String user, String udId) {
    }
}
