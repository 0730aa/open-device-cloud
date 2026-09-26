package org.cloud.sonic.agent.relay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ServerSocket;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RelayBridgeTest {
    private static final String TICKET = "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl";
    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQ";

    private FakeServer relay;
    private FakeServer agent;

    @Before
    public void setUp() throws Exception {
        relay = FakeServer.startNew();
        agent = FakeServer.startNew();
    }

    @After
    public void tearDown() throws Exception {
        relay.stop(1000);
        agent.stop(1000);
    }

    @Test
    public void joinsTheRelaysConnectionToTheAgentsOwnEndpoint() throws Exception {
        String target = "/websockets/android/screen/" + TICKET + "/serial-1";

        open("conn-1", target);
        FakeServer.Connection relaySide = relay.nextConnection();
        FakeServer.Connection agentSide = agent.nextConnection();
        relaySide.socket.send("{\"type\":\"touch\"}");
        agentSide.socket.send(new byte[]{1, 2, 3});

        assertEquals("/agent/data/conn-1?secret=" + SECRET, relaySide.resource);
        assertEquals(target, agentSide.resource);
        assertEquals("{\"type\":\"touch\"}", agentSide.nextText());
        assertArrayEquals(new byte[]{1, 2, 3}, relaySide.nextBinary());
    }

    @Test
    public void closingEitherSideClosesTheOther() throws Exception {
        open("conn-2", "/websockets/android/" + TICKET + "/serial-1");
        FakeServer.Connection relaySide = relay.nextConnection();
        FakeServer.Connection agentSide = agent.nextConnection();
        agentSide.socket.close(1008, "invalid ticket");
        assertEquals(1008, relaySide.closeCode());

        open("conn-3", "/websockets/android/terminal/" + TICKET + "/serial-1");
        FakeServer.Connection secondRelaySide = relay.nextConnection();
        FakeServer.Connection secondAgentSide = agent.nextConnection();
        secondRelaySide.socket.close(1000);
        assertEquals(1000, secondAgentSide.closeCode());
    }

    @Test
    public void codesOnlyServersMaySendArePassedOnAsGoingAway() throws Exception {
        open("conn-4", "/websockets/ios/" + TICKET + "/udid-1");
        FakeServer.Connection relaySide = relay.nextConnection();

        agent.nextConnection().socket.close(1013, "device busy");

        assertEquals(1001, relaySide.closeCode());
    }

    @Test
    public void relayConnectionEndsWhenTheAgentEndpointIsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RelayBridge.open(RelayClient.HTTP, "ws://127.0.0.1:" + relay.getPort(), closedPort, "conn-5", SECRET,
                "/websockets/android/" + TICKET + "/serial-1");

        assertEquals(1011, relay.nextConnection().closeCode());
    }

    @Test
    public void ignoresRequestsForAnythingButTheRemoteControlEndpoints() throws Exception {
        open("conn-6", "/uia/7777/status");
        open("conn-7", "/websockets/../uia/7777");
        open("../evil", "/websockets/android/" + TICKET + "/serial-1");
        RelayBridge.open(RelayClient.HTTP, "ws://127.0.0.1:" + relay.getPort(), agent.getPort(), "conn-8", "short",
                "/websockets/android/" + TICKET + "/serial-1");

        agent.assertNoConnectionWithin(500);
        relay.assertNoConnectionWithin(100);
    }

    @Test
    public void onlyRemoteControlPathsAreForwardable() {
        assertTrue(RelayBridge.isForwardable("/websockets/android/" + TICKET + "/serial-1"));
        assertTrue(RelayBridge.isForwardable("/websockets/webView/" + TICKET + "/serial-1/9222/page-1"));
        assertTrue(RelayBridge.isForwardable("/websockets/android/" + TICKET + "/192.168.1.5%3A5555"));
        assertTrue(RelayBridge.isForwardable("/websockets/audio/" + TICKET + "/serial-1?codec=opus"));

        assertFalse(RelayBridge.isForwardable(null));
        assertFalse(RelayBridge.isForwardable("/uia/7777/status"));
        assertFalse(RelayBridge.isForwardable("/websockets/../uia"));
        assertFalse(RelayBridge.isForwardable("/websockets/%2e%2e/uia"));
        assertFalse(RelayBridge.isForwardable("/websockets/./android"));
        assertFalse(RelayBridge.isForwardable("/websockets//android"));
        assertFalse(RelayBridge.isForwardable("/websockets/a%2Fb"));
        assertFalse(RelayBridge.isForwardable("/websockets/a%5Cb"));
        assertFalse(RelayBridge.isForwardable("/websockets/a%0Ab"));
        assertFalse(RelayBridge.isForwardable("/websockets/%zz"));
        assertFalse(RelayBridge.isForwardable("/websockets/a?b#c"));
        assertFalse(RelayBridge.isForwardable("/websockets/" + "a".repeat(RelayBridge.MAX_TARGET_LENGTH)));
    }

    @Test
    public void closeCodesJavaCannotSendBecomeGoingAway() {
        assertEquals(1000, RelayBridge.sendableCode(1000));
        assertEquals(1008, RelayBridge.sendableCode(1008));
        assertEquals(4001, RelayBridge.sendableCode(4001));
        assertEquals(1001, RelayBridge.sendableCode(1013));
        assertEquals(1001, RelayBridge.sendableCode(1006));
        assertEquals(1001, RelayBridge.sendableCode(1005));
    }

    private void open(String id, String target) {
        RelayBridge.open(RelayClient.HTTP, "ws://127.0.0.1:" + relay.getPort(), agent.getPort(), id, SECRET, target);
    }
}
