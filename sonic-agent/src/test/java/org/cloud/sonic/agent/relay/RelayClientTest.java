package org.cloud.sonic.agent.relay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.http.WebSocket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class RelayClientTest {
    private static final String TICKET = "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl";
    private static final String SECRET = "c2VjcmV0LXNlY3JldC1zZWNyZXQ";

    private final AtomicInteger tokenRequests = new AtomicInteger();
    private Runnable tokenRequesterBefore;
    private FakeServer relay;
    private FakeServer agent;

    @Before
    public void setUp() throws Exception {
        tokenRequesterBefore = RelayClient.tokenRequester;
        RelayClient.tokenRequester = tokenRequests::incrementAndGet;
        relay = FakeServer.startNew();
        agent = FakeServer.startNew();
    }

    @After
    public void tearDown() throws Exception {
        RelayClient.stop();
        RelayClient.tokenRequester = tokenRequesterBefore;
        relay.stop(1000);
        agent.stop(1000);
    }

    @Test
    public void connectsWithTheServersTokenAndAnswersTheRelaysRequests() throws Exception {
        RelayClient.start("http://127.0.0.1:" + relay.getPort() + "/", agent.getPort(), false);
        assertEquals(1, tokenRequests.get());

        RelayClient.onToken("relay-token");
        FakeServer.Connection control = relay.nextConnection();
        assertEquals("/agent/control?token=relay-token", control.resource);
        awaitConnected(true);
        RelayClient.onToken("another-token");
        String target = "/websockets/android/" + TICKET + "/serial-1";
        control.socket.send("{\"type\":\"open\",\"id\":\"conn-1\",\"secret\":\"" + SECRET + "\",\"path\":\"" + target + "\"}");

        assertEquals("/agent/data/conn-1?secret=" + SECRET, relay.nextConnection().resource);
        assertEquals(target, agent.nextConnection().resource);
    }

    @Test
    public void asksForANewTokenWhenTheRelayDropsTheConnection() throws Exception {
        RelayClient.start("ws://127.0.0.1:" + relay.getPort(), agent.getPort(), false);
        RelayClient.onToken("relay-token");
        FakeServer.Connection control = relay.nextConnection();
        awaitConnected(true);

        control.socket.close(1001);

        awaitConnected(false);
        long deadline = System.currentTimeMillis() + 5_000;
        while (tokenRequests.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(2, tokenRequests.get());
    }

    @Test
    public void doesNothingWithoutARelayUrl() {
        RelayClient.start("", agent.getPort(), false);
        RelayClient.start("ftp://relay.example.com", agent.getPort(), false);
        RelayClient.onToken("relay-token");

        assertEquals(0, tokenRequests.get());
        assertFalse(RelayClient.isConnected());
    }

    @Test
    public void refusesToRelayWhenTheAgentServesTls() {
        RelayClient.start("https://relay.example.com", agent.getPort(), true);

        assertEquals(0, tokenRequests.get());
    }

    @Test
    public void relayUrlsBecomeWebSocketUrls() {
        assertEquals("wss://relay.example.com", RelayClient.webSocketBase("https://relay.example.com/"));
        assertEquals("wss://relay.example.com:8443/relay", RelayClient.webSocketBase(" WSS://relay.example.com:8443/relay "));
        assertEquals("ws://10.0.0.5:8095", RelayClient.webSocketBase("http://10.0.0.5:8095"));
        assertNull(RelayClient.webSocketBase(null));
        assertNull(RelayClient.webSocketBase("relay.example.com"));
        assertNull(RelayClient.webSocketBase("https://relay.example.com/?x=1"));
    }

    @Test
    public void browsersUseThePublicUrlOrElseTheRelay() {
        assertEquals("https://phone-1.example.com", RelayClient.browserUrl("https://phone-1.example.com", "https://relay.example.com"));
        assertEquals("https://relay.example.com", RelayClient.browserUrl("", "https://relay.example.com"));
        assertEquals("", RelayClient.browserUrl(null, "not a url"));
        assertEquals("", RelayClient.browserUrl(null, null));
    }

    @Test
    public void keepaliveDropsConnectionsThatStopAnswering() {
        WebSocket answering = mock(WebSocket.class);
        WebSocket silent = mock(WebSocket.class);
        AtomicInteger dead = new AtomicInteger();
        Keepalive.watch(answering, dead::incrementAndGet);
        Keepalive.watch(silent, dead::incrementAndGet);
        long later = System.currentTimeMillis() + Keepalive.DEAD_AFTER_MILLIS + 1;

        Keepalive.pingAll();
        Keepalive.heard(answering);
        Keepalive.forget(answering);
        Keepalive.pingAll(later);

        verify(answering, times(1)).sendPing(any());
        verify(answering, never()).abort();
        verify(silent).abort();
        assertEquals(1, dead.get());
        Keepalive.pingAll(later);
        assertEquals(1, dead.get());
    }

    private static void awaitConnected(boolean connected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (RelayClient.isConnected() != connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("connected should be " + connected, RelayClient.isConnected() == connected);
    }
}
