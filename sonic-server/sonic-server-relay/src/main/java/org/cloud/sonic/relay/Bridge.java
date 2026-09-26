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

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Copies messages between a browser connection and the agent's data connection for it, until
 * either side closes; the other side is then closed with the same status. A slow reader slows
 * the sender down instead of making the relay buffer.
 */
final class Bridge {
    /**
     * How long to wait for the status of a connection that just ended.
     */
    private static final Duration CLOSE_STATUS_WAIT = Duration.ofSeconds(2);

    private Bridge() {
    }

    /**
     * @param pingInterval how often to ping the browser. Proxies and load balancers in front of the
     *                     relay close connections that are quiet for about a minute, and a
     *                     remote-control connection can be quiet for longer (e.g. a still screen).
     */
    static Mono<Void> between(WebSocketSession browser, WebSocketSession agent, Traffic traffic, Duration pingInterval) {
        Mono<Void> fromBrowser = agent.send(browser.receive()
                        .filter(Bridge::isData)
                        .map(message -> copy(message, agent, traffic.fromBrowser)))
                .onErrorResume(e -> Mono.empty())
                .then(Mono.defer(() -> closeWithStatusOf(browser, agent)));
        Flux<WebSocketMessage> pings = Flux.interval(pingInterval)
                .onBackpressureDrop()
                .map(tick -> browser.pingMessage(factory -> factory.wrap(new byte[0])));
        Flux<WebSocketMessage> toBrowser = agent.receive()
                .filter(Bridge::isData)
                .map(message -> copy(message, browser, traffic.fromAgent))
                // Pings stop when the agent's messages do.
                .publish(messages -> Flux.merge(messages, pings.takeUntilOther(messages.ignoreElements())));
        Mono<Void> fromAgent = browser.send(toBrowser)
                .onErrorResume(e -> Mono.empty())
                .then(Mono.defer(() -> closeWithStatusOf(agent, browser)));
        return Mono.firstWithSignal(fromBrowser, fromAgent);
    }

    /**
     * Ping and pong are between neighbours: each connection answers its own peer's pings.
     */
    private static boolean isData(WebSocketMessage message) {
        return message.getType() == WebSocketMessage.Type.TEXT || message.getType() == WebSocketMessage.Type.BINARY;
    }

    /**
     * The inbound buffer is released once this returns, so the payload is copied for the other side.
     */
    private static WebSocketMessage copy(WebSocketMessage message, WebSocketSession target, AtomicLong counter) {
        DataBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.readableByteCount()];
        payload.read(bytes);
        counter.addAndGet(bytes.length);
        return new WebSocketMessage(message.getType(), target.bufferFactory().wrap(bytes));
    }

    private static Mono<Void> closeWithStatusOf(WebSocketSession ended, WebSocketSession other) {
        return ended.closeStatus()
                .timeout(CLOSE_STATUS_WAIT, Mono.empty())
                .onErrorResume(e -> Mono.empty())
                .defaultIfEmpty(CloseStatus.GOING_AWAY)
                .flatMap(status -> other.close(sendable(status)))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Some codes only describe what happened locally (e.g. 1006, closed without a close frame)
     * and must not be sent; reasons are limited to 123 bytes.
     */
    static CloseStatus sendable(CloseStatus status) {
        int code = status.getCode();
        boolean sendable = (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014) || (code >= 3000 && code <= 4999);
        if (!sendable) {
            return CloseStatus.GOING_AWAY;
        }
        String reason = status.getReason();
        return reason == null || reason.getBytes(StandardCharsets.UTF_8).length <= 123 ? status : new CloseStatus(code);
    }

    /**
     * Bytes relayed for one browser connection, for the usage log.
     */
    static final class Traffic {
        final AtomicLong fromBrowser = new AtomicLong();
        final AtomicLong fromAgent = new AtomicLong();
    }
}
