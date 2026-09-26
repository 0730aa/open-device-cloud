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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * /agent/control?token={relay token}: an agent registers here and stays connected, waiting to
 * be asked to connect back for browsers.
 */
@Component
class AgentControlHandler implements WebSocketHandler {
    static final String PATH = "/agent/control";
    private static final Logger log = LoggerFactory.getLogger(AgentControlHandler.class);

    private final TokenVerifier verifier;
    private final AgentRegistry agents;

    AgentControlHandler(TokenVerifier verifier, AgentRegistry agents) {
        this.verifier = verifier;
        this.agents = agents;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Integer agentId = verifier.agentOf(Sessions.queryParam(session, "token"));
        if (agentId == null) {
            log.info("Refused an agent connection from {}: no valid relay token.", Sessions.remote(session));
            return session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid relay token"));
        }
        AgentLink link = new AgentLink(agentId, session);
        AgentLink replaced = agents.register(link);
        log.info("Agent {} connected from {}.", agentId, Sessions.remote(session));
        Mono<Void> closeReplaced = replaced == null ? Mono.empty()
                : replaced.close(CloseStatus.NORMAL.withReason("replaced by a newer connection"))
                .onErrorResume(e -> Mono.empty());
        Mono<Void> outbound = session.send(link.outbound().map(session::textMessage));
        // Agents have nothing to say on this connection; reading only notices when it closes.
        Mono<Void> inbound = session.receive().then();
        return closeReplaced
                .then(Mono.firstWithSignal(inbound, outbound))
                .doFinally(signal -> {
                    agents.unregister(link);
                    log.info("Agent {} disconnected.", agentId);
                });
    }
}
