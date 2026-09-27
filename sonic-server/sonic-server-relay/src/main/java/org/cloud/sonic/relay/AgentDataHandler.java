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
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * /agent/data/{id}?secret={secret}: the connection an agent opens back for one browser
 * connection, after the relay asked for it on the agent's control connection.
 */
@Component
class AgentDataHandler implements WebSocketHandler {
    static final String PATH_PREFIX = "/agent/data/";

    private final PendingConnections pending;

    AgentDataHandler(PendingConnections pending) {
        this.pending = pending;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        PendingConnections.Pending connection = pending.claim(id, Sessions.queryParam(session, "secret"));
        if (connection == null) {
            return session.close(CloseStatus.POLICY_VIOLATION.withReason("unknown connection"));
        }
        if (!connection.accept(session)) {
            return session.close(CloseStatus.GOING_AWAY.withReason("browser left"));
        }
        // The browser side copies the messages; this connection stays open until it is done.
        return connection.finished();
    }
}
