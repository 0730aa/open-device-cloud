/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
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
package org.cloud.sonic.agent.tools;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Verification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks remote tickets: short-lived JWTs the server signs with this agent's key to let one
 * user connect to one device. Browsers never learn the agent key, so a ticket is the only way
 * in. The claims must match sonic-server's RemoteTicketTool.
 */
@Component
public class RemoteTicketVerifier {
    static final String AGENT_CLAIM = "aid";
    static final String UDID_CLAIM = "udId";
    /**
     * Tolerated clock difference between the server and this machine.
     */
    static final long LEEWAY_SECONDS = 60;

    private static volatile String key;

    @Value("${sonic.agent.key}")
    public void setKey(String key) {
        RemoteTicketVerifier.key = key;
    }

    /**
     * @return the user the ticket was issued to, or null unless it is a valid, unexpired
     * ticket for this device on this agent.
     */
    public static String verify(String ticket, String udId) {
        String agentKey = key;
        if (agentKey == null || agentKey.isEmpty() || ticket == null || udId == null) {
            return null;
        }
        try {
            Verification verification = JWT.require(Algorithm.HMAC256(agentKey))
                    .withClaim(UDID_CLAIM, udId)
                    .acceptLeeway(LEEWAY_SECONDS);
            if (BytesTool.agentId > 0) {
                verification = verification.withClaim(AGENT_CLAIM, BytesTool.agentId);
            }
            return verification.build().verify(ticket).getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
