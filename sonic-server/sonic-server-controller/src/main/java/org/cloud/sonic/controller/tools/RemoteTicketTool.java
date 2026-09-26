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
import org.cloud.sonic.controller.models.domain.Agents;

import java.util.Date;
import java.util.UUID;

/**
 * Remote tickets let one user open connections to one device on one agent for a short time,
 * replacing the agent's secret key, which browsers used to receive. A ticket is a JWT signed
 * with that agent's key, so only the server and that agent can check it and it is useless
 * on any other agent. The agent (sonic-agent RemoteTicketVerifier) verifies the same claims.
 */
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

    public static String issue(Agents agent, String udId, String userName) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withSubject(userName)
                .withClaim(AGENT_CLAIM, agent.getId())
                .withClaim(UDID_CLAIM, udId)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + TTL_SECONDS * 1000))
                .withJWTId(UUID.randomUUID().toString())
                .sign(Algorithm.HMAC256(agent.getSecretKey()));
    }

    /**
     * @return the user the ticket was issued to, or null unless it is a valid, unexpired
     * ticket for this device on this agent.
     */
    public static String verify(Agents agent, String udId, String ticket) {
        if (agent == null || ticket == null || udId == null
                || agent.getSecretKey() == null || agent.getSecretKey().isEmpty()) {
            return null;
        }
        try {
            return JWT.require(Algorithm.HMAC256(agent.getSecretKey()))
                    .withClaim(AGENT_CLAIM, agent.getId())
                    .withClaim(UDID_CLAIM, udId)
                    .acceptLeeway(LEEWAY_SECONDS)
                    .build()
                    .verify(ticket)
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
