/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
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
package org.cloud.sonic.agent.websockets;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.AndroidWebViewMap;
import org.cloud.sonic.agent.tools.BytesTool;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ZhouYiXun
 * @des
 * @date 2021/10/25 23:03
 */
@Component
@ServerEndpoint(value = "/websockets/webView/{ticket}/{udId}/{port}/{id}", configurator = WsEndpointConfigure.class)
public class WebViewWSServer {
    private final Logger logger = LoggerFactory.getLogger(WebViewWSServer.class);
    private Map<Session, WebSocketClient> sessionWebSocketClientMap = new HashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("ticket") String ticket, @PathParam("udId") String udId,
                       @PathParam("port") int port, @PathParam("id") String id) throws Exception {
        if (RemoteSessionGuard.admit(session, ticket, udId) == null) {
            return;
        }
        // The port picks a local socket on this machine, so only allow the device's own webviews.
        if (!isWebViewPortOf(udId, port)) {
            logger.info("Rejected webview connection: port {} is not a webview of {}.", port, udId);
            RemoteSessionGuard.reject(session, "unknown webview");
            return;
        }
        URI uri = new URI("ws://localhost:" + port + "/devtools/page/" + id);
        WebSocketClient webSocketClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                logger.info("Connected!");
            }

            @Override
            public void onMessage(String s) {
                BytesTool.sendText(session, s);
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                logger.info("Disconnected!");
            }

            @Override
            public void onError(Exception e) {

            }
        };
        webSocketClient.connect();
        sessionWebSocketClientMap.put(session, webSocketClient);
    }

    @OnMessage
    public void onMessage(String message, Session session) throws InterruptedException {
        if (sessionWebSocketClientMap.get(session) != null) {
            try {
                sessionWebSocketClientMap.get(session).send(message);
            } catch (Exception e) {

            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        if (!RemoteSessionGuard.release(session)) {
            return;
        }
        WebSocketClient webSocketClient = sessionWebSocketClientMap.remove(session);
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    static boolean isWebViewPortOf(String udId, int port) {
        Integer inspectorPort = SibTool.getWebViewPort(udId);
        if (inspectorPort != null && inspectorPort == port) {
            return true;
        }
        return AndroidWebViewMap.getMap().entrySet().stream()
                .filter(e -> udId.equals(e.getKey().getSerialNumber()))
                .flatMap(e -> e.getValue().stream())
                .anyMatch(forward -> Integer.valueOf(port).equals(forward.getInteger("port")));
    }

    @OnError
    public void onError(Session session, Throwable error) {
        logger.error(error.getMessage());
    }
}
