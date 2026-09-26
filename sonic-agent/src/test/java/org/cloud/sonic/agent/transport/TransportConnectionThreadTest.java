package org.cloud.sonic.agent.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TransportConnectionThreadTest {

    @Test
    public void connectsInPlainTextByDefault() {
        assertEquals("ws://10.0.0.2:3000/server/websockets/agent/key",
                TransportConnectionThread.serverUrl(false, "10.0.0.2", 3000, "key"));
        assertEquals("ws://sonic.example.com/server/websockets/agent/key",
                TransportConnectionThread.serverUrl(false, "sonic.example.com", 80, "key"));
    }

    @Test
    public void connectsOverTlsWhenTheServerIsBehindHttps() {
        assertEquals("wss://sonic.example.com/server/websockets/agent/key",
                TransportConnectionThread.serverUrl(true, "sonic.example.com", 443, "key"));
        assertEquals("wss://sonic.example.com:8443/server/websockets/agent/key",
                TransportConnectionThread.serverUrl(true, "sonic.example.com", 8443, "key"));
    }
}
