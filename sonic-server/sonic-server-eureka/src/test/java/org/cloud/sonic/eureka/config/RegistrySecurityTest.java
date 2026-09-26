package org.cloud.sonic.eureka.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the registry and tries it the way an attacker who can reach its port would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.user.name=sonic",
        "spring.security.user.password=" + RegistrySecurityTest.PASSWORD,
        "logging.file.name="})
class RegistrySecurityTest {
    static final String PASSWORD = "0123456789abcdef0123456789abcdef";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String FAKE_CONTROLLER = """
            {"instance": {
              "instanceId": "fake-controller",
              "hostName": "attacker.example",
              "app": "SONIC-SERVER-CONTROLLER",
              "ipAddr": "203.0.113.5",
              "status": "UP",
              "port": {"$": 8080, "@enabled": "true"},
              "securePort": {"$": 443, "@enabled": "false"},
              "vipAddress": "sonic-server-controller",
              "dataCenterInfo": {"@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo", "name": "MyOwn"}
            }}""";

    @LocalServerPort
    private int port;

    @Test
    void nobodyCanRegisterWithoutTheCredentials() throws Exception {
        assertEquals(401, register(null).statusCode());
        assertEquals(401, register("sonic:sonic").statusCode());
        assertFalse(send(get("/eureka/apps", "sonic:" + PASSWORD)).body().contains("fake-controller"));
    }

    @Test
    void serverComponentsStillRegisterWithTheirCredentials() throws Exception {
        assertEquals(204, register("sonic:" + PASSWORD).statusCode());

        assertTrue(send(get("/eureka/apps", "sonic:" + PASSWORD)).body().contains("fake-controller"));
    }

    @Test
    void theRegistryCannotBeReadWithoutTheCredentials() throws Exception {
        assertEquals(401, send(get("/eureka/apps", null)).statusCode());
    }

    @Test
    void theHealthCheckStaysOpen() throws Exception {
        assertEquals(200, send(get("/actuator/health", null)).statusCode());
    }

    private HttpResponse<String> register(String credentials) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/eureka/apps/SONIC-SERVER-CONTROLLER"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(FAKE_CONTROLLER));
        return send(withCredentials(request, credentials).build());
    }

    private HttpRequest get(String path, String credentials) {
        return withCredentials(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json"), credentials).build();
    }

    private static HttpRequest.Builder withCredentials(HttpRequest.Builder request, String credentials) {
        return credentials == null ? request : request.header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
