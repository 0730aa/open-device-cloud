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

import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;

final class Sessions {
    private Sessions() {
    }

    static String queryParam(WebSocketSession session, String name) {
        return UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri()).build()
                .getQueryParams().getFirst(name);
    }

    static String remote(WebSocketSession session) {
        InetSocketAddress address = session.getHandshakeInfo().getRemoteAddress();
        return address == null ? "unknown" : address.getHostString();
    }
}
