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

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pings the connections to the relay, so that routers keep idle ones open, and drops those that
 * stop answering: a connection that silently died would otherwise hold its device until TCP
 * gives up, which can take many minutes.
 */
final class Keepalive {
    /**
     * A connection that has not answered for this long (three missed pings) is considered dead.
     */
    static final long DEAD_AFTER_MILLIS = RelayClient.TICK.toMillis() * 3 + 10_000;

    private static final Map<WebSocket, Watch> WATCHED = new ConcurrentHashMap<>();

    private Keepalive() {
    }

    /**
     * @param onDead run after the connection has been dropped for not answering
     */
    static void watch(WebSocket ws, Runnable onDead) {
        WATCHED.put(ws, new Watch(onDead, new AtomicLong(System.currentTimeMillis())));
    }

    static void heard(WebSocket ws) {
        Watch watch = WATCHED.get(ws);
        if (watch != null) {
            watch.lastHeard().set(System.currentTimeMillis());
        }
    }

    static void forget(WebSocket ws) {
        WATCHED.remove(ws);
    }

    static void pingAll() {
        pingAll(System.currentTimeMillis());
    }

    static void pingAll(long now) {
        WATCHED.forEach((ws, watch) -> {
            if (now - watch.lastHeard().get() > DEAD_AFTER_MILLIS) {
                if (WATCHED.remove(ws, watch)) {
                    ws.abort();
                    watch.onDead().run();
                }
                return;
            }
            try {
                // A ping that cannot be sent shows up as a missing pong.
                ws.sendPing(ByteBuffer.allocate(0));
            } catch (RuntimeException ignored) {
            }
        });
    }

    private record Watch(Runnable onDead, AtomicLong lastHeard) {
    }
}
