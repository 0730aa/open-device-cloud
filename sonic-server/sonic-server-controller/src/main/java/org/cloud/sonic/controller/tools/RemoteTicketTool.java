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
package org.cloud.sonic.controller.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.cloud.sonic.controller.models.domain.Agents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote tickets let one user open connections to one device on one agent for a short time,
 * replacing the agent's secret key, which browsers used to receive. A ticket is an ES256 JWT
 * signed with the platform key (see {@link RemoteTicketKeys}); agents verify it with the public
 * key, so an agent run by a third party cannot issue tickets, e.g. to bill someone for a session
 * that never happened. sonic-agent's RemoteTicketVerifier checks the same claims.
 */
@Component
public class RemoteTicketTool {
    /**
     * How long a ticket can be used to open a connection. Connections already open are not
     * cut when it expires; the browser fetches a new ticket for every connection it opens.
     */
    public static final long TTL_SECONDS = 120;
    /**
     * Tolerated clock difference between the server and agent machines.
     */
    public static final long LEEWAY_SECONDS = 60;
    public static final String AGENT_CLAIM = "aid";
    public static final String UDID_CLAIM = "udId";

    @Autowired
    private RemoteTicketKeys keys;

    /**
     * Ticket id -> when it stops being valid, for tickets that already opened a usage session.
     */
    private final Map<String, Long> usedForSession = new ConcurrentHashMap<>();

    public RemoteTicketTool() {
    }

    public RemoteTicketTool(RemoteTicketKeys keys) {
        this.keys = keys;
    }

    public String issue(Agents agent, String udId, String userName) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withSubject(userName)
                .withClaim(AGENT_CLAIM, agent.getId())
                .withClaim(UDID_CLAIM, udId)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + TTL_SECONDS * 1000))
                .withJWTId(UUID.randomUUID().toString())
                .sign(Algorithm.ECDSA256(keys.publicKey(), keys.privateKey()));
    }

    /**
     * @return the user the ticket was issued to, or null unless it is a valid, unexpired
     * ticket for this device on this agent.
     */
    public String verify(Agents agent, String udId, String ticket) {
        DecodedJWT decoded = decode(agent, udId, ticket);
        return decoded == null ? null : decoded.getSubject();
    }

    /**
     * @return the verified ticket (subject is the user, id identifies the ticket), or null
     * unless it is a valid, unexpired ticket for this device on this agent.
     */
    public DecodedJWT decode(Agents agent, String udId, String ticket) {
        if (agent == null || agent.getId() == null || ticket == null || udId == null) {
            return null;
        }
        try {
            return JWT.require(Algorithm.ECDSA256(keys.publicKey(), null))
                    .withClaim(AGENT_CLAIM, agent.getId())
                    .withClaim(UDID_CLAIM, udId)
                    .acceptLeeway(LEEWAY_SECONDS)
                    .build()
                    .verify(ticket);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A ticket may open one usage session. Agents send the ticket back when a session starts, so
     * without this an agent could replay a real ticket to open sessions its user never had.
     *
     * @return false if the ticket already opened a session (or has no id)
     */
    public boolean markUsedForSession(DecodedJWT ticket) {
        if (ticket.getId() == null || ticket.getExpiresAt() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        usedForSession.values().removeIf(validUntil -> validUntil < now);
        long validUntil = ticket.getExpiresAt().getTime() + LEEWAY_SECONDS * 1000;
        return usedForSession.putIfAbsent(ticket.getId(), validUntil) == null;
    }
}
