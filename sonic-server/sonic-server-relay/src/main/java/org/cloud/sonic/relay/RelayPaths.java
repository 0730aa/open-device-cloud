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

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Browsers use the same paths through the relay as they would on the agent itself, e.g.
 * /websockets/android/screen/{ticket}/{udId}; the relay hands the path to the agent unchanged.
 */
final class RelayPaths {
    static final String BROWSER_PREFIX = "/websockets/";
    static final int MAX_LENGTH = 2048;
    private static final Pattern JWT_SHAPE = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private RelayPaths() {
    }

    /**
     * Whether the path (and query) may be handed to an agent: under /websockets/, with no
     * segment the agent could resolve to somewhere else, such as "..".
     */
    static boolean isForwardable(String rawPath, String rawQuery) {
        if (rawPath == null || !rawPath.startsWith(BROWSER_PREFIX)
                || rawPath.length() + (rawQuery == null ? 0 : rawQuery.length()) > MAX_LENGTH) {
            return false;
        }
        String[] segments = rawPath.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment;
            try {
                segment = UriUtils.decode(segments[i], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.contains("/") || segment.contains("\\") || hasControlCharacter(segment)) {
                return false;
            }
        }
        return rawQuery == null || !hasControlCharacter(rawQuery);
    }

    /**
     * @return the first segment shaped like a JWT, which is where every remote-control path
     * carries its ticket, or null
     */
    static String ticketSegment(String rawPath) {
        for (String segment : rawPath.split("/")) {
            if (JWT_SHAPE.matcher(segment).matches()) {
                return segment;
            }
        }
        return null;
    }

    /**
     * @return the path with its ticket left out, for logs
     */
    static String withoutTicket(String rawPath) {
        String ticket = ticketSegment(rawPath);
        return ticket == null ? rawPath : rawPath.replace(ticket, "{ticket}");
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }
}
