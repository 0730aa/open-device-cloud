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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param ticketPublicKey        base64 X.509 encoding of the platform's ticket public key, the
 *                               TICKET_PUBLIC_KEY sonic-server-controller signs tickets for
 * @param openTimeout            how long a browser connection waits for its agent to connect back
 * @param maxConnectionsPerAgent browser connections one agent may have open or opening at a time
 * @param maxFrameBytes          largest message relayed, e.g. one screen frame
 * @param pingInterval           how often browsers are pinged, so that proxies in front of the
 *                               relay do not close connections that are quiet for a while
 */
@ConfigurationProperties("sonic.relay")
public record RelayProperties(
        String ticketPublicKey,
        @DefaultValue("10s") Duration openTimeout,
        @DefaultValue("64") int maxConnectionsPerAgent,
        @DefaultValue("16777216") int maxFrameBytes,
        @DefaultValue("30s") Duration pingInterval) {
}
