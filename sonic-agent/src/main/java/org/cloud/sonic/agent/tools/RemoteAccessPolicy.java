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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remote ADB (sas share), remote SIB, remote WDA, the /uia proxy and the packet-capture proxy
 * open ports on this machine that have no authentication: anyone who can reach them controls
 * the device or reads its traffic. They stay off unless the agent owner opts in with
 * sonic.agent.remote-access.enable.
 */
@Component
public class RemoteAccessPolicy {
    private static volatile boolean enabled = false;

    @Value("${sonic.agent.remote-access.enable:false}")
    public void setEnabled(boolean enabled) {
        RemoteAccessPolicy.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
