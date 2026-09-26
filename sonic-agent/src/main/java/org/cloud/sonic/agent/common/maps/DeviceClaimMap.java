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
package org.cloud.sonic.agent.common.maps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which user each device currently belongs to. Every remote connection (control, screen,
 * terminal, audio, webview) and every remote occupation holds a claim, so a device serves a
 * single user at a time and nobody can watch or type into someone else's session. A user
 * may hold several connections to the same device; the claim ends with the last one.
 */
public class DeviceClaimMap {
    private record Claim(String user, int holds) {
    }

    private static final Map<String, Claim> claims = new ConcurrentHashMap<>();

    /**
     * @return true if the device was free or already held by this user.
     */
    public static boolean acquire(String udId, String user) {
        boolean[] acquired = {false};
        claims.compute(udId, (k, claim) -> {
            if (claim == null) {
                acquired[0] = true;
                return new Claim(user, 1);
            }
            if (claim.user().equals(user)) {
                acquired[0] = true;
                return new Claim(user, claim.holds() + 1);
            }
            return claim;
        });
        return acquired[0];
    }

    public static void release(String udId, String user) {
        claims.computeIfPresent(udId, (k, claim) -> {
            if (!claim.user().equals(user)) {
                return claim;
            }
            return claim.holds() <= 1 ? null : new Claim(user, claim.holds() - 1);
        });
    }

    /**
     * @return the user holding the device, or null if it is free.
     */
    public static String holder(String udId) {
        Claim claim = claims.get(udId);
        return claim == null ? null : claim.user();
    }
}
