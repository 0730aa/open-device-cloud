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
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser connections waiting for their agent to connect back. The relay sends the agent an id
 * and a secret over its control connection; the agent's data connection must present both.
 */
@Component
class PendingConnections {
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    Pending create(int agentId) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        Pending connection = new Pending(UUID.randomUUID().toString(), agentId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        pending.put(connection.id(), connection);
        return connection;
    }

    /**
     * @return the connection waiting for this id if the secret is right; it stops waiting
     */
    Pending claim(String id, String secret) {
        if (id == null || secret == null) {
            return null;
        }
        Pending connection = pending.get(id);
        if (connection == null || !MessageDigest.isEqual(
                connection.secret().getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return pending.remove(id, connection) ? connection : null;
    }

    void remove(Pending connection) {
        pending.remove(connection.id(), connection);
    }

    static final class Pending {
        private final String id;
        private final int agentId;
        private final String secret;
        private final Sinks.One<WebSocketSession> agentSide = Sinks.one();
        private final Sinks.Empty<Void> finished = Sinks.empty();

        private Pending(String id, int agentId, String secret) {
            this.id = id;
            this.agentId = agentId;
            this.secret = secret;
        }

        String id() {
            return id;
        }

        int agentId() {
            return agentId;
        }

        String secret() {
            return secret;
        }

        /**
         * Completes with the agent's data connection once it arrives.
         */
        Mono<WebSocketSession> agentSide() {
            return agentSide.asMono();
        }

        /**
         * @return false if the browser side is already gone
         */
        boolean accept(WebSocketSession agentConnection) {
            return agentSide.tryEmitValue(agentConnection).isSuccess();
        }

        /**
         * Completes when the browser connection ends, which ends the agent's data connection too.
         */
        Mono<Void> finished() {
            return finished.asMono();
        }

        void finish() {
            agentSide.tryEmitEmpty();
            finished.tryEmitEmpty();
        }
    }
}
