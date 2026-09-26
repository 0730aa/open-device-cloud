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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /websockets/**: a browser's remote-control connection, on the same path it would use on the
 * agent. The ticket in the path says which agent to route to; the agent checks it again.
 */
@Component
class BrowserHandler implements WebSocketHandler {
    static final String PATH_PATTERN = RelayPaths.BROWSER_PREFIX + "**";
    private static final Logger log = LoggerFactory.getLogger(BrowserHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TokenVerifier verifier;
    private final AgentRegistry agents;
    private final PendingConnections pending;
    private final RelayProperties properties;

    BrowserHandler(TokenVerifier verifier, AgentRegistry agents, PendingConnections pending, RelayProperties properties) {
        this.verifier = verifier;
        this.agents = agents;
        this.pending = pending;
        this.properties = properties;
    }

    @Override
    public Mono<Void> handle(WebSocketSession browser) {
        URI uri = browser.getHandshakeInfo().getUri();
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        if (!RelayPaths.isForwardable(path, query)) {
            return browser.close(CloseStatus.POLICY_VIOLATION.withReason("not a remote-control path"));
        }
        TokenVerifier.Ticket ticket = verifier.ticket(RelayPaths.ticketSegment(path));
        if (ticket == null) {
            return browser.close(CloseStatus.POLICY_VIOLATION.withReason("invalid ticket"));
        }
        AgentLink agent = agents.find(ticket.agentId());
        if (agent == null) {
            return browser.close(CloseStatus.SERVICE_OVERLOAD.withReason("agent not connected"));
        }
        if (!agents.tryAddConnection(ticket.agentId(), properties.maxConnectionsPerAgent())) {
            return browser.close(CloseStatus.SERVICE_OVERLOAD.withReason("too many connections"));
        }
        PendingConnections.Pending connection = pending.create(ticket.agentId());
        AtomicBoolean released = new AtomicBoolean();
        // Before telling the browser, so that it can connect again right away.
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                pending.remove(connection);
                connection.finish();
                agents.removeConnection(ticket.agentId());
            }
        };
        Bridge.Traffic traffic = new Bridge.Traffic();
        AtomicReference<String> outcome = new AtomicReference<>("relayed");
        long started = System.currentTimeMillis();
        Mono<Void> relay;
        if (agent.send(openRequest(connection, query == null ? path : path + "?" + query))) {
            relay = connection.agentSide()
                    .timeout(properties.openTimeout())
                    .flatMap(agentSide -> Bridge.between(browser, agentSide, traffic, properties.pingInterval()))
                    .onErrorResume(TimeoutException.class, e -> {
                        release.run();
                        outcome.set("agent did not connect back");
                        return browser.close(CloseStatus.SERVICE_OVERLOAD.withReason("agent did not answer"));
                    });
        } else {
            release.run();
            outcome.set("agent disconnecting");
            relay = browser.close(CloseStatus.SERVICE_OVERLOAD.withReason("agent not connected"));
        }
        return relay.doFinally(signal -> {
            release.run();
            // One line per connection, to check usage records against what was actually relayed.
            log.info("Relay usage: agent={} user={} device={} path={} outcome={} durationMs={} bytesFromBrowser={} bytesFromAgent={}",
                    ticket.agentId(), ticket.user(), ticket.udId(), RelayPaths.withoutTicket(path), outcome.get(),
                    System.currentTimeMillis() - started, traffic.fromBrowser.get(), traffic.fromAgent.get());
        });
    }

    private static String openRequest(PendingConnections.Pending connection, String target) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "open");
        request.put("id", connection.id());
        request.put("secret", connection.secret());
        request.put("path", target);
        try {
            return JSON.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
