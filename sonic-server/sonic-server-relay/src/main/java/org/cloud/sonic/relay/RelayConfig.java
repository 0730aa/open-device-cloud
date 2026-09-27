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

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(RelayProperties.class)
public class RelayConfig implements WebFluxConfigurer {
    private final RelayProperties properties;

    RelayConfig(RelayProperties properties) {
        this.properties = properties;
    }

    /**
     * Screen frames are larger than Reactor Netty's default 64 KB message limit.
     */
    @Override
    public WebSocketService getWebSocketService() {
        return new HandshakeWebSocketService(new ReactorNettyRequestUpgradeStrategy(
                () -> WebsocketServerSpec.builder().maxFramePayloadLength(properties.maxFrameBytes())));
    }

    @Bean
    public HandlerMapping relayHandlerMapping(AgentControlHandler control, AgentDataHandler data, BrowserHandler browser) {
        Map<String, WebSocketHandler> handlers = Map.of(
                AgentControlHandler.PATH, control,
                AgentDataHandler.PATH_PREFIX + "*", data,
                BrowserHandler.PATH_PATTERN, browser);
        return new SimpleUrlHandlerMapping(handlers, -1);
    }
}
