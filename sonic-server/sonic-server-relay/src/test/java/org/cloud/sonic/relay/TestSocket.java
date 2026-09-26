package org.cloud.sonic.relay;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A WebSocket client for tests that records what it receives.
 */
final class TestSocket implements WebSocket.Listener {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final long WAIT_SECONDS = 5;

    private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final AtomicInteger pings = new AtomicInteger();
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private WebSocket socket;

    static TestSocket connect(String url) {
        TestSocket client = new TestSocket();
        client.socket = HTTP.newWebSocketBuilder().buildAsync(URI.create(url), client).orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join();
        return client;
    }

    void send(String message) {
        socket.sendText(message, true).join();
    }

    void send(byte[] message) {
        socket.sendBinary(ByteBuffer.wrap(message), true).join();
    }

    void close(int code) {
        socket.sendClose(code, "").join();
    }

    String nextText() throws InterruptedException {
        return assertInstanceOf(String.class, next());
    }

    byte[] nextBinary() throws InterruptedException {
        return assertInstanceOf(byte[].class, next());
    }

    /**
     * @return the code of the Close message the other side sent
     */
    int closeCode() {
        return closeCode.orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).join();
    }

    int pings() {
        return pings.get();
    }

    void assertNothingReceivedWithin(Duration duration) throws InterruptedException {
        assertNull(received.poll(duration.toMillis(), TimeUnit.MILLISECONDS));
    }

    private Object next() throws InterruptedException {
        Object message = received.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(message, "nothing received");
        return message;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            received.add(text.toString());
            text.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        binary.writeBytes(bytes);
        if (last) {
            received.add(binary.toByteArray());
            binary.reset();
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        pings.incrementAndGet();
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closeCode.completeExceptionally(error);
    }
}
