package org.cloud.sonic.agent.relay;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stands in for the relay or for this agent's own endpoints, recording what it receives.
 */
class FakeServer extends WebSocketServer {
    static final long WAIT_SECONDS = 5;

    private final BlockingQueue<Connection> connections = new LinkedBlockingQueue<>();
    private final Map<WebSocket, Connection> bySocket = new ConcurrentHashMap<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private FakeServer() {
        super(new InetSocketAddress("127.0.0.1", 0));
        setReuseAddr(true);
    }

    static FakeServer startNew() throws InterruptedException {
        FakeServer server = new FakeServer();
        server.start();
        assertTrue("server did not start", server.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        return server;
    }

    Connection nextConnection() throws InterruptedException {
        Connection connection = connections.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("no connection", connection);
        return connection;
    }

    void assertNoConnectionWithin(long millis) throws InterruptedException {
        assertNull(connections.poll(millis, TimeUnit.MILLISECONDS));
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public void onOpen(WebSocket socket, ClientHandshake handshake) {
        Connection connection = new Connection(socket, handshake.getResourceDescriptor());
        bySocket.put(socket, connection);
        connections.add(connection);
    }

    @Override
    public void onMessage(WebSocket socket, String message) {
        bySocket.get(socket).received.add(message);
    }

    @Override
    public void onMessage(WebSocket socket, ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        bySocket.get(socket).received.add(bytes);
    }

    @Override
    public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        Connection connection = bySocket.get(socket);
        if (connection != null) {
            connection.closeCode.complete(code);
        }
    }

    @Override
    public void onError(WebSocket socket, Exception ex) {
    }

    static final class Connection {
        final WebSocket socket;
        /**
         * Path and query the client connected to.
         */
        final String resource;
        private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

        private Connection(WebSocket socket, String resource) {
            this.socket = socket;
            this.resource = resource;
        }

        String nextText() throws InterruptedException {
            return (String) next();
        }

        byte[] nextBinary() throws InterruptedException {
            return (byte[]) next();
        }

        int closeCode() throws Exception {
            return closeCode.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private Object next() throws InterruptedException {
            Object message = received.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("nothing received", message);
            return message;
        }
    }
}
