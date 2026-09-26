package org.cloud.sonic.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the relay and plays both the browser and the agent. Each test uses its own agent id,
 * since the relay is shared between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sonic.relay.open-timeout=2s",
        "sonic.relay.max-connections-per-agent=3",
        "sonic.relay.ping-interval=300ms"})
class RelayIntegrationTest {
    private static final KeyPair PLATFORM = TestTokens.newKeyPair();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SHORT = Duration.ofMillis(500);

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void platformKey(DynamicPropertyRegistry registry) {
        registry.add("sonic.relay.ticket-public-key", () -> TestTokens.encodedPublicKey(PLATFORM));
    }

    @Test
    void relaysMessagesBothWaysOnTheBrowsersPath() throws Exception {
        TestSocket control = agent(1);
        String path = "/websockets/android/" + ticket(1, "serial-1") + "/serial-1";
        TestSocket browser = browser(path);

        JsonNode open = nextOpen(control);
        assertEquals(path, open.get("path").asText());
        TestSocket agentSide = dataConnection(open);
        browser.send("{\"type\":\"touch\"}");
        agentSide.send(new byte[]{1, 2, 3});

        assertEquals("{\"type\":\"touch\"}", agentSide.nextText());
        assertArrayEquals(new byte[]{1, 2, 3}, browser.nextBinary());
    }

    @Test
    void largeScreenFramesGetThrough() throws Exception {
        TestSocket control = agent(2);
        TestSocket browser = browser("/websockets/android/screen/" + ticket(2, "serial-2") + "/serial-2");
        TestSocket agentSide = dataConnection(nextOpen(control));
        byte[] frame = new byte[2 * 1024 * 1024];
        frame[frame.length - 1] = 42;

        agentSide.send(frame);

        assertArrayEquals(frame, browser.nextBinary());
    }

    @Test
    void closingEitherSideClosesTheOtherWithTheSameCode() throws Exception {
        TestSocket control = agent(3);
        TestSocket browser = browser("/websockets/android/" + ticket(3, "serial-3") + "/serial-3");
        TestSocket agentSide = dataConnection(nextOpen(control));
        agentSide.close(1008);
        assertEquals(1008, browser.closeCode());

        TestSocket secondBrowser = browser("/websockets/android/terminal/" + ticket(3, "serial-3") + "/serial-3");
        TestSocket secondAgentSide = dataConnection(nextOpen(control));
        secondBrowser.close(1000);
        assertEquals(1000, secondAgentSide.closeCode());
    }

    @Test
    void quietConnectionsArePingedSoProxiesKeepThemOpen() throws Exception {
        TestSocket control = agent(11);
        TestSocket browser = browser("/websockets/android/terminal/" + ticket(11, "serial-11") + "/serial-11");
        TestSocket agentSide = dataConnection(nextOpen(control));

        long deadline = System.currentTimeMillis() + 5_000;
        while (browser.pings() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        agentSide.send("still there");

        assertTrue(browser.pings() >= 2, "pings: " + browser.pings());
        assertEquals("still there", browser.nextText());
        agentSide.close(1000);
        assertEquals(1000, browser.closeCode());
    }

    @Test
    void browsersNeedAValidTicket() throws Exception {
        TestSocket control = agent(4);
        KeyPair other = TestTokens.newKeyPair();
        List<String> refused = List.of(
                "/websockets/android/serial-4",
                "/websockets/android/" + TestTokens.ticket(other, 4, "serial-4", "alice", Duration.ofMinutes(2)) + "/serial-4",
                "/websockets/android/" + TestTokens.ticket(PLATFORM, 4, "serial-4", "alice", Duration.ofMinutes(-5)) + "/serial-4",
                "/websockets/android/" + TestTokens.relayToken(PLATFORM, 4, Duration.ofMinutes(10)) + "/serial-4",
                "/websockets/%2e%2e/uia/" + ticket(4, "serial-4") + "/serial-4");

        for (String path : refused) {
            assertEquals(1008, browser(path).closeCode(), path);
        }
        control.assertNothingReceivedWithin(SHORT);
    }

    @Test
    void browsersAreToldToRetryWhenTheAgentIsNotConnected() {
        assertEquals(1013, browser("/websockets/android/" + ticket(5, "serial-5") + "/serial-5").closeCode());
    }

    @Test
    void agentsNeedARelayToken() {
        KeyPair other = TestTokens.newKeyPair();
        List<String> refused = List.of(
                "",
                "?token=garbage",
                "?token=" + ticket(6, "serial-6"),
                "?token=" + TestTokens.relayToken(other, 6, Duration.ofMinutes(10)),
                "?token=" + TestTokens.hmacRelayToken("guessable", 6));

        for (String query : refused) {
            assertEquals(1008, TestSocket.connect(url("/agent/control" + query)).closeCode(), query);
        }
    }

    @Test
    void dataConnectionsNeedTheSecretFromTheOpenRequest() throws Exception {
        TestSocket control = agent(7);
        TestSocket browser = browser("/websockets/ios/" + ticket(7, "udid-7") + "/udid-7");
        JsonNode open = nextOpen(control);

        TestSocket wrongSecret = TestSocket.connect(url("/agent/data/" + open.get("id").asText() + "?secret=guess"));
        assertEquals(1008, wrongSecret.closeCode());
        TestSocket agentSide = dataConnection(open);
        browser.send("still waiting");

        assertEquals("still waiting", agentSide.nextText());
        assertEquals(1008, dataConnection(open).closeCode(), "an open request is answered once");
    }

    @Test
    void browserGivesUpWhenTheAgentDoesNotConnectBack() throws Exception {
        TestSocket control = agent(8);
        TestSocket browser = browser("/websockets/android/" + ticket(8, "serial-8") + "/serial-8");
        JsonNode open = nextOpen(control);

        assertEquals(1013, browser.closeCode());
        assertEquals(1008, dataConnection(open).closeCode(), "too late");
    }

    @Test
    void anAgentCarriesALimitedNumberOfConnections() throws Exception {
        TestSocket control = agent(9);
        List<TestSocket> waiting = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            waiting.add(browser("/websockets/android/" + ticket(9, "serial-9") + "/serial-9"));
            nextOpen(control);
        }

        assertEquals(1013, browser("/websockets/android/" + ticket(9, "serial-9") + "/serial-9").closeCode());
        control.assertNothingReceivedWithin(SHORT);
        for (TestSocket socket : waiting) {
            assertEquals(1013, socket.closeCode());
        }
        TestSocket afterwards = browser("/websockets/android/" + ticket(9, "serial-9") + "/serial-9");
        TestSocket agentSide = dataConnection(nextOpen(control));
        afterwards.send("room again");
        assertEquals("room again", agentSide.nextText());
    }

    @Test
    void aNewerAgentConnectionReplacesTheOlderOne() throws Exception {
        TestSocket first = agent(10);
        TestSocket second = agent(10);
        assertEquals(1000, first.closeCode());

        TestSocket browser = browser("/websockets/android/" + ticket(10, "serial-10") + "/serial-10");
        TestSocket agentSide = dataConnection(nextOpen(second));
        browser.send("hello");

        assertEquals("hello", agentSide.nextText());
    }

    private TestSocket agent(int agentId) {
        return TestSocket.connect(url("/agent/control?token=" + TestTokens.relayToken(PLATFORM, agentId, Duration.ofMinutes(10))));
    }

    private TestSocket browser(String path) {
        return TestSocket.connect(url(path));
    }

    private TestSocket dataConnection(JsonNode open) {
        return TestSocket.connect(url("/agent/data/" + open.get("id").asText() + "?secret=" + open.get("secret").asText()));
    }

    private static JsonNode nextOpen(TestSocket control) throws Exception {
        JsonNode request = JSON.readTree(control.nextText());
        assertEquals("open", request.get("type").asText());
        assertTrue(request.hasNonNull("id") && request.hasNonNull("secret"));
        return request;
    }

    private static String ticket(int agentId, String udId) {
        return TestTokens.ticket(PLATFORM, agentId, udId, "alice", Duration.ofMinutes(2));
    }

    private String url(String path) {
        return "ws://localhost:" + port + path;
    }
}
