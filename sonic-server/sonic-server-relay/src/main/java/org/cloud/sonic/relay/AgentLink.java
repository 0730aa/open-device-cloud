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

import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * An agent's control connection, over which the relay asks it to connect back for a browser.
 */
final class AgentLink {
    private final int agentId;
    private final WebSocketSession session;
    private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
    private boolean closing;

    AgentLink(int agentId, WebSocketSession session) {
        this.agentId = agentId;
        this.session = session;
    }

    int agentId() {
        return agentId;
    }

    Flux<String> outbound() {
        return outbound.asFlux();
    }

    /**
     * @return false if the connection is closing and the message will not be sent
     */
    synchronized boolean send(String message) {
        return !closing && outbound.tryEmitNext(message).isSuccess();
    }

    /**
     * Sends the close frame; the connection's handler ends when the agent answers it. (Ending
     * the outbound stream instead would end the handler first, closing without a status.)
     */
    Mono<Void> close(CloseStatus status) {
        synchronized (this) {
            closing = true;
        }
        return session.close(status);
    }
}
