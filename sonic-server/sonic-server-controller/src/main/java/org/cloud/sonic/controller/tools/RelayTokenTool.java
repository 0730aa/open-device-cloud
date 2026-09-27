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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

/**
 * Relay tokens let an agent register with sonic-server-relay, which forwards browsers' remote
 * connections to agents that cannot accept connections themselves (e.g. behind NAT). A token is
 * an ES256 JWT signed with the platform key, like remote tickets, so the relay only needs the
 * public key. The scope claim keeps relay tokens and tickets from standing in for each other;
 * sonic-server-relay's TokenVerifier checks the same claims.
 */
@Component
public class RelayTokenTool {
    /**
     * How long a token can be used to open a relay connection. Agents ask for a new one every
     * time they connect; an open connection is not cut when its token expires.
     */
    public static final long TTL_SECONDS = 600;
    public static final String SCOPE_CLAIM = "scope";
    public static final String RELAY_AGENT_SCOPE = "relay-agent";

    @Autowired
    private RemoteTicketKeys keys;

    public RelayTokenTool() {
    }

    public RelayTokenTool(RemoteTicketKeys keys) {
        this.keys = keys;
    }

    /**
     * @param agentId the agent whose authenticated connection asked for the token
     */
    public String issue(int agentId) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withClaim(SCOPE_CLAIM, RELAY_AGENT_SCOPE)
                .withClaim(RemoteTicketTool.AGENT_CLAIM, agentId)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + TTL_SECONDS * 1000))
                .withJWTId(UUID.randomUUID().toString())
                .sign(Algorithm.ECDSA256(keys.publicKey(), keys.privateKey()));
    }
}
