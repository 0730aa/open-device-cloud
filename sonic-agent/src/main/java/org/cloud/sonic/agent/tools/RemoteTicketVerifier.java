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
import lombok.extern.slf4j.Slf4j;

import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks remote tickets: short-lived ES256 JWTs the server signs with the platform key to let one
 * user connect to one device on this agent. The agent only holds the public key, received when it
 * authenticates to the server, so it can check tickets but cannot issue any. The claims must
 * match sonic-server's RemoteTicketTool.
 */
@Slf4j
public class RemoteTicketVerifier {
    static final String AGENT_CLAIM = "aid";
    static final String UDID_CLAIM = "udId";
    /**
     * Tolerated clock difference between the server and this machine.
     */
    static final long LEEWAY_SECONDS = 60;

    private static volatile ECPublicKey publicKey;

    /**
     * @param encoded base64 X.509 encoding of the platform's public key, as sent by the server
     */
    public static void setPublicKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            publicKey = null;
            return;
        }
        try {
            publicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
        } catch (Exception e) {
            publicKey = null;
            log.error("Invalid remote ticket key from the server, remote connections will be refused.");
        }
    }

    /**
     * @return the user the ticket was issued to, or null unless it is a valid, unexpired
     * ticket for this device on this agent.
     */
    public static String verify(String ticket, String udId) {
        ECPublicKey key = publicKey;
        if (key == null || ticket == null || udId == null) {
            return null;
        }
        try {
            Verification verification = JWT.require(Algorithm.ECDSA256(key, null))
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
