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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.transport.TransportClient;
import org.cloud.sonic.agent.transport.TransportWorker;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects this agent to sonic-server-relay when sonic.agent.relay-url is set, so that browsers
 * can reach it although it accepts no connections from the internet, e.g. behind a home router.
 * <p>
 * The agent keeps a control connection to the relay, opened with a short-lived relay token the
 * server hands out over the agent's authenticated connection. For each browser connection the
 * relay asks for a data connection back, which {@link RelayBridge} joins to the endpoint on this
 * agent the browser would otherwise have connected to directly, ticket check included.
 */
@Slf4j
public class RelayClient {
    static final String CONTROL_PATH = "/agent/control";
    static final String DATA_PATH = "/agent/data/";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * How often to ping the relay (keeping routers from dropping idle connections) and to
     * reconnect if the control connection is down.
     */
    static final Duration TICK = Duration.ofSeconds(30);
    private static final int MAX_CONTROL_MESSAGE = 64 * 1024;
    private static final Pattern RELAY_URL = Pattern.compile("(?i)(https?|wss?)(://[^/?#\\s]+(/[^?#\\s]*)?)");

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "relay-client");
        thread.setDaemon(true);
        return thread;
    });

    // Guarded by RelayClient.class.
    private static String relayBase;
    private static int localPort;
    private static WebSocket control;
    private static boolean connecting;
    private static ScheduledFuture<?> ticker;
    static Runnable tokenRequester = RelayClient::requestTokenFromServer;

    /**
     * Starts relaying, if sonic.agent.relay-url is set; called when the server accepts this agent.
     *
     * @param relayUrl  sonic.agent.relay-url
     * @param agentPort the port of this agent's own endpoints
     * @param agentTls  whether those endpoints are served over TLS (server.ssl.enabled)
     */
    public static void start(String relayUrl, int agentPort, boolean agentTls) {
        String base = webSocketBase(relayUrl);
        if (base == null) {
            return;
        }
        if (agentTls) {
            log.error("sonic.agent.relay-url is ignored while server.ssl.enabled is on: the relay reaches this agent's "
                    + "endpoints over plain ws on 127.0.0.1. Browsers connect to the relay over TLS, so turn it off.");
            return;
        }
        boolean askForToken;
        synchronized (RelayClient.class) {
            relayBase = base;
            localPort = agentPort;
            if (ticker == null) {
                ticker = SCHEDULER.scheduleWithFixedDelay(RelayClient::tick, TICK.toSeconds(), TICK.toSeconds(), TimeUnit.SECONDS);
            }
            askForToken = control == null && !connecting;
        }
        if (askForToken) {
            tokenRequester.run();
        }
    }

    /**
     * The URL browsers should use for this agent: sonic.agent.public-url if set, else the relay's.
     */
    public static String browserUrl(String publicUrl, String relayUrl) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl;
        }
        return webSocketBase(relayUrl) == null ? "" : relayUrl.trim();
    }

    /**
     * The server's answer to a relayToken request: connect with it, unless already connected.
     */
    public static void onToken(String token) {
        URI uri;
        synchronized (RelayClient.class) {
            if (relayBase == null || token == null || control != null || connecting) {
                return;
            }
            connecting = true;
            uri = URI.create(relayBase + CONTROL_PATH + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        }
        HTTP.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT).buildAsync(uri, new ControlListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        synchronized (RelayClient.class) {
                            connecting = false;
                        }
                        log.warn("Cannot connect to the relay: {}", error.getMessage());
                    }
                });
    }

    /**
     * @return the WebSocket URL of the relay, without a trailing slash, or null if the setting is
     * empty or not an http(s)/ws(s) URL
     */
    static String webSocketBase(String relayUrl) {
        if (relayUrl == null || relayUrl.isBlank()) {
            return null;
        }
        Matcher matcher = RELAY_URL.matcher(relayUrl.trim().replaceAll("/+$", ""));
        if (!matcher.matches()) {
            log.error("Ignoring sonic.agent.relay-url {}: expected an http(s) or ws(s) URL.", relayUrl);
            return null;
        }
        String scheme = matcher.group(1).toLowerCase();
        return (scheme.equals("https") || scheme.equals("wss") ? "wss" : "ws") + matcher.group(2);
    }

    static synchronized boolean isConnected() {
        return control != null;
    }

    /**
     * Disconnects from the relay and stops reconnecting.
     */
    static void stop() {
        WebSocket ws;
        synchronized (RelayClient.class) {
            ws = control;
            control = null;
            connecting = false;
            relayBase = null;
            if (ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
        }
        if (ws != null) {
            Keepalive.forget(ws);
            ws.abort();
        }
    }

    private static void tick() {
        boolean askForToken;
        synchronized (RelayClient.class) {
            askForToken = relayBase != null && control == null && !connecting;
        }
        if (askForToken) {
            tokenRequester.run();
        }
        Keepalive.pingAll();
    }

    private static void requestTokenFromServer() {
        TransportClient client = TransportWorker.client;
        if (client == null || !client.isOpen()) {
            // Asked again when the server accepts this agent.
            return;
        }
        JSONObject request = new JSONObject();
        request.put("msg", "relayToken");
        TransportWorker.send(request);
    }

    private static synchronized void opened(WebSocket ws) {
        connecting = false;
        control = ws;
        log.info("Connected to the relay.");
    }

    private static void closed(WebSocket ws, String why) {
        Keepalive.forget(ws);
        synchronized (RelayClient.class) {
            if (control != ws) {
                return;
            }
            control = null;
        }
        log.info("Relay connection closed ({}), reconnecting.", why);
        SCHEDULER.schedule(() -> {
            boolean askForToken;
            synchronized (RelayClient.class) {
                askForToken = relayBase != null && control == null && !connecting;
            }
            if (askForToken) {
                tokenRequester.run();
            }
        }, 2, TimeUnit.SECONDS);
    }

    private static void handle(String message) {
        JSONObject request;
        try {
            request = JSON.parseObject(message);
        } catch (Exception e) {
            log.warn("Ignored a malformed message from the relay.");
            return;
        }
        if (request == null || !"open".equals(request.getString("type"))) {
            return;
        }
        String base;
        int port;
        synchronized (RelayClient.class) {
            base = relayBase;
            port = localPort;
        }
        RelayBridge.open(HTTP, base, port, request.getString("id"), request.getString("secret"), request.getString("path"));
    }

    private static final class ControlListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            opened(webSocket);
            Keepalive.watch(webSocket, () -> closed(webSocket, "no answer to pings"));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > MAX_CONTROL_MESSAGE) {
                webSocket.abort();
                closed(webSocket, "message too large");
                return null;
            }
            if (last) {
                String message = text.toString();
                text.setLength(0);
                handle(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            Keepalive.heard(webSocket);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed(webSocket, statusCode + (reason == null || reason.isEmpty() ? "" : " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed(webSocket, String.valueOf(error.getMessage()));
        }
    }
}
