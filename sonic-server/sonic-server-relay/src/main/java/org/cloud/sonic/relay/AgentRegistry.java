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

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The agents connected to this relay, and how many browser connections each one carries.
 */
@Component
class AgentRegistry {
    private final Map<Integer, AgentLink> links = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> connections = new ConcurrentHashMap<>();

    /**
     * @return the agent's previous link, which this one replaces (e.g. after a reconnect)
     */
    AgentLink register(AgentLink link) {
        return links.put(link.agentId(), link);
    }

    void unregister(AgentLink link) {
        links.remove(link.agentId(), link);
    }

    AgentLink find(int agentId) {
        return links.get(agentId);
    }

    /**
     * Counts a browser connection against the agent's limit, unless it is reached.
     */
    boolean tryAddConnection(int agentId, int max) {
        boolean[] added = {false};
        connections.compute(agentId, (id, open) -> {
            int count = open == null ? 0 : open;
            if (count >= max) {
                return open;
            }
            added[0] = true;
            return count + 1;
        });
        return added[0];
    }

    void removeConnection(int agentId) {
        connections.computeIfPresent(agentId, (id, open) -> open <= 1 ? null : open - 1);
    }
}
