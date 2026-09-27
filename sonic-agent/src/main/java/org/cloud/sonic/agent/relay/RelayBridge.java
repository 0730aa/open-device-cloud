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
package org.cloud.sonic.agent.relay;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * One browser connection through the relay: joins the data connection the relay asked for to
 * the endpoint on this agent that the browser would otherwise connect to directly, e.g.
 * ws://127.0.0.1:7777/websockets/android/{ticket}/{udId}. That endpoint checks the ticket and
 * claims the device exactly as it does for direct connections.
 */
@Slf4j
final class RelayBridge {
    static final String AGENT_PREFIX = "/websockets/";
    static final int MAX_TARGET_LENGTH = 2048;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private RelayBridge() {
    }

    /**
     * @param target path and query the browser used, which must be one of this agent's
     *               remote-control endpoints
     */
    static void open(HttpClient http, String relayBase, int localPort, String id, String secret, String target) {
        URI relayUri;
        URI localUri;
        try {
            if (relayBase == null || id == null || !ID.matcher(id).matches()
                    || secret == null || !SECRET.matcher(secret).matches() || !isForwardable(target)) {
                throw new IllegalArgumentException();
            }
            relayUri = URI.create(relayBase + RelayClient.DATA_PATH + id + "?secret=" + secret);
            localUri = URI.create("ws://127.0.0.1:" + localPort + target);
        } catch (IllegalArgumentException e) {
            log.warn("Ignored an invalid connection request from the relay.");
            return;
        }
        CompletableFuture<WebSocket> relaySide = new CompletableFuture<>();
        CompletableFuture<WebSocket> localSide = new CompletableFuture<>();
        connect(http, relayUri, new Forwarder(localSide, true), relaySide,
                // The browser gets nothing from this agent; end what was opened for it.
                () -> localSide.thenAccept(local -> close(local, WebSocket.NORMAL_CLOSURE, "")));
        connect(http, localUri, new Forwarder(relaySide, false), localSide,
                () -> relaySide.thenAccept(relay -> close(relay, 1011, "agent endpoint unavailable")));
    }

    /**
     * Whether the target may be opened on this agent: under /websockets/, with no segment that
     * could resolve to somewhere else, such as "..". The relay checks the same.
     */
    static boolean isForwardable(String target) {
        if (target == null || target.length() > MAX_TARGET_LENGTH || !target.startsWith(AGENT_PREFIX)) {
            return false;
        }
        int queryStart = target.indexOf('?');
        String path = queryStart < 0 ? target : target.substring(0, queryStart);
        String query = queryStart < 0 ? "" : target.substring(queryStart + 1);
        if (hasControlCharacter(query) || query.indexOf('#') >= 0) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment;
            try {
                // Percent-decoding only: "+" is a literal plus in a path.
                segment = URLDecoder.decode(segments[i].replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains("/")
                    || segment.contains("\\") || segment.contains("#") || hasControlCharacter(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Java's WebSocket client refuses to send codes meant for servers (e.g. 1013, try again later)
     * or describing local events (1006, closed without a close frame).
     */
    static int sendableCode(int code) {
        boolean sendable = code == WebSocket.NORMAL_CLOSURE || code == 1001 || code == 1008 || code == 1011
                || (code >= 3000 && code <= 4999);
        return sendable ? code : 1001;
    }

    private static void connect(HttpClient http, URI uri, Forwarder forwarder, CompletableFuture<WebSocket> side,
                                Runnable onFailure) {
        http.newWebSocketBuilder().connectTimeout(RelayClient.CONNECT_TIMEOUT).buildAsync(uri, forwarder)
                .whenComplete((ws, error) -> {
                    if (error == null) {
                        side.complete(ws);
                    } else {
                        log.info("Relay connection {} failed: {}", forwarder.relaySide ? "to the relay" : "to this agent", error.getMessage());
                        side.completeExceptionally(error);
                        onFailure.run();
                    }
                });
    }

    private static void close(WebSocket ws, int code, String reason) {
        String sendableReason = reason == null || reason.getBytes(StandardCharsets.UTF_8).length > 123 ? "" : reason;
        ws.sendClose(sendableCode(code), sendableReason).exceptionally(e -> {
            ws.abort();
            return null;
        });
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    /**
     * Passes what one side receives to the other. The next message is only asked for once the
     * previous one has been sent on, so a slow browser slows the device's stream down instead
     * of piling it up in memory here.
     */
    private static final class Forwarder implements WebSocket.Listener {
        private final CompletableFuture<WebSocket> peer;
        private final boolean relaySide;

        Forwarder(CompletableFuture<WebSocket> peer, boolean relaySide) {
            this.peer = peer;
            this.relaySide = relaySide;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (relaySide) {
                Keepalive.watch(webSocket, () -> peer.thenAccept(WebSocket::abort));
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String text = data.toString();
            return forwarded(webSocket, peer.thenCompose(p -> p.sendText(text, last)));
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return forwarded(webSocket, peer.thenCompose(p -> p.sendBinary(data, last)));
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            Keepalive.heard(webSocket);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            Keepalive.forget(webSocket);
            peer.thenAccept(p -> close(p, statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Keepalive.forget(webSocket);
            peer.thenAccept(WebSocket::abort);
        }

        private static CompletionStage<?> forwarded(WebSocket webSocket, CompletableFuture<WebSocket> sent) {
            return sent.whenComplete((p, error) -> {
                if (error == null) {
                    webSocket.request(1);
                } else {
                    webSocket.abort();
                }
            });
        }
    }
}
