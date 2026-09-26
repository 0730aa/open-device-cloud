package org.cloud.sonic.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayPathsTest {
    private static final String TICKET = "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl";

    @Test
    void forwardsTheAgentsRemoteControlPaths() {
        assertTrue(RelayPaths.isForwardable("/websockets/android/screen/" + TICKET + "/serial-1", null));
        assertTrue(RelayPaths.isForwardable("/websockets/webView/" + TICKET + "/serial-1/9222/page-1", null));
        assertTrue(RelayPaths.isForwardable("/websockets/android/" + TICKET + "/192.168.1.5%3A5555", null));
        assertTrue(RelayPaths.isForwardable("/websockets/audio/" + TICKET + "/serial-1", "codec=opus"));
    }

    @Test
    void refusesPathsThatCouldResolveOutsideTheRemoteControlEndpoints() {
        assertFalse(RelayPaths.isForwardable("/uia/7777/status", null));
        assertFalse(RelayPaths.isForwardable("/websockets/../uia/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/%2e%2e/uia/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/./android/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/a%2Fb/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/a%5Cb/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets//android/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/android%0A/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/android/%zz/" + TICKET, null));
        assertFalse(RelayPaths.isForwardable("/websockets/" + "a".repeat(RelayPaths.MAX_LENGTH), null));
        assertFalse(RelayPaths.isForwardable(null, null));
    }

    @Test
    void findsTheTicketAndKeepsItOutOfLogs() {
        String path = "/websockets/android/terminal/" + TICKET + "/serial-1";

        assertEquals(TICKET, RelayPaths.ticketSegment(path));
        assertEquals("/websockets/android/terminal/{ticket}/serial-1", RelayPaths.withoutTicket(path));
        assertNull(RelayPaths.ticketSegment("/websockets/android/serial-1"));
    }
}
