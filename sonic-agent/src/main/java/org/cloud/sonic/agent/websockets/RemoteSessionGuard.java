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
package org.cloud.sonic.agent.websockets;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.common.maps.DeviceClaimMap;
import org.cloud.sonic.agent.tools.RemoteTicketVerifier;

import java.io.IOException;

/**
 * Admission control for the remote-control WebSockets: a connection needs a valid ticket for
 * the device and must not collide with another user's claim on it.
 */
@Slf4j
public class RemoteSessionGuard {
    private static final String CLAIM_UDID = "claimUdId";
    private static final String CLAIM_USER = "claimUser";

    /**
     * Verify the ticket and claim the device for its user. A rejected session is closed.
     *
     * @return the ticket's user, or null if the session was rejected
     */
    public static String admit(Session session, String ticket, String udId) {
        String user = RemoteTicketVerifier.verify(ticket, udId);
        if (user == null) {
            log.info("Rejected connection to {}: invalid or expired ticket.", udId);
            close(session, CloseReason.CloseCodes.VIOLATED_POLICY, "invalid ticket");
            return null;
        }
        if (!DeviceClaimMap.acquire(udId, user)) {
            log.info("Rejected {} on {}: the device is in use by someone else.", user, udId);
            close(session, CloseReason.CloseCodes.TRY_AGAIN_LATER, "device busy");
            return null;
        }
        session.getUserProperties().put(CLAIM_UDID, udId);
        session.getUserProperties().put(CLAIM_USER, user);
        return user;
    }

    /**
     * Give up an admitted session that cannot be served after all, and close it.
     */
    public static void reject(Session session, String reason) {
        release(session);
        close(session, CloseReason.CloseCodes.TRY_AGAIN_LATER, reason);
    }

    /**
     * Release the claim taken by {@link #admit}. Call it from @OnClose.
     *
     * @return true if the session had been admitted and not rejected since
     */
    public static boolean release(Session session) {
        String udId = (String) session.getUserProperties().remove(CLAIM_UDID);
        String user = (String) session.getUserProperties().remove(CLAIM_USER);
        if (udId == null || user == null) {
            return false;
        }
        DeviceClaimMap.release(udId, user);
        return true;
    }

    private static void close(Session session, CloseReason.CloseCode code, String reason) {
        try {
            session.close(new CloseReason(code, reason));
        } catch (IOException e) {
            log.info("Close rejected session failed: {}", e.getMessage());
        }
    }
}
