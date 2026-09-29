package com.mindcraft.bridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A10 (bearer auth on every non-liveness endpoint) + A12 (loopback-only CORS,
 * preflight, non-destructive reads by default, companion peek vs drain).
 */
class BridgeAuthDrainTest {
    private BridgeHttpServer bridge;
    private HttpClient client;
    private int port;
    private String previousToken;

    @BeforeEach
    void setUp() throws Exception {
        previousToken = BridgeConfig.get().bridgeToken;
        BridgeConfig.get().bridgeToken = "test-token-abcdef123456";
        TaskQueue.getInstance().cancelAll();
        bridge = new BridgeHttpServer(0);
        Field serverField = BridgeHttpServer.class.getDeclaredField("server");
        serverField.setAccessible(true);
        port = ((HttpServer) serverField.get(bridge)).getAddress().getPort();
        bridge.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        bridge.stop();
        client.close();
        TaskQueue.getInstance().cancelAll();
        BridgeConfig.get().bridgeToken = previousToken;
    }

    private HttpResponse<String> request(String method, String path, String auth, String origin, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(3));
        if (auth != null) builder.header("Authorization", auth);
        if (origin != null) builder.header("Origin", origin);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("OPTIONS".equals(method)) {
            builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String bearer() {
        return "Bearer test-token-abcdef123456";
    }

    @Test
    void pingStaysOpenAsLivenessOnly() throws Exception {
        HttpResponse<String> response = request("GET", "/ping", null, null, null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ok\":true"));
    }

    @Test
    void queueStateRequiresAuth() throws Exception {
        HttpResponse<String> response = request("GET", "/queue/state", null, null, null);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("unauthorized_bridge_token"));
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        assertEquals(401, request("GET", "/queue/state", "Bearer wrong-token", null, null).statusCode());
        assertEquals(401, request("GET", "/queue/state", "Basic dGVzdA==", null, null).statusCode());
        assertEquals(401, request("GET", "/queue/state", "Bearer ", null, null).statusCode());
    }

    @Test
    void correctTokenIsAccepted() throws Exception {
        HttpResponse<String> response = request("GET", "/queue/state", bearer(), null, null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\""));
    }

    @Test
    void unauthenticatedMutationHasNoSideEffect() throws Exception {
        long id = pauseFixtureTask();
        HttpResponse<String> response = request("POST", "/queue/cancel", null, null, "");
        assertEquals(401, response.statusCode());
        assertTrue(TaskQueue.getInstance().getQueueState().paused());
        assertEquals(id, TaskQueue.getInstance().getQueueState().activeId());
    }

    @Test
    void stateRequiresAuthBeforeAnyStateAccess() throws Exception {
        // Auth precedes StateCollector access, so this holds without a client.
        HttpResponse<String> response = request("GET", "/state", null, null, null);
        assertEquals(401, response.statusCode());
    }

    @Test
    void blankServerTokenFailsClosed() throws Exception {
        BridgeConfig.get().bridgeToken = "   ";
        assertEquals(401, request("GET", "/queue/state", bearer(), null, null).statusCode());
    }

    @Test
    void evilOriginGetsNoCorsRead() throws Exception {
        HttpResponse<String> response = request("GET", "/queue/state", bearer(), "https://evil.example.com", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("access-control-allow-origin").isEmpty());
    }

    @Test
    void loopbackOriginIsEchoedAndOnlyLoopback() throws Exception {
        HttpResponse<String> loopback = request("GET", "/queue/state", bearer(), "http://localhost:8080", null);
        assertEquals("http://localhost:8080",
                loopback.headers().firstValue("access-control-allow-origin").orElse(null));
        HttpResponse<String> noOrigin = request("GET", "/queue/state", bearer(), null, null);
        assertTrue(noOrigin.headers().firstValue("access-control-allow-origin").isEmpty());
        HttpResponse<String> lookalike = request("GET", "/queue/state", bearer(), "http://localhost.example.com", null);
        assertTrue(lookalike.headers().firstValue("access-control-allow-origin").isEmpty());
    }

    @Test
    void preflightAcceptedOnlyForLoopbackOrigins() throws Exception {
        HttpResponse<String> loopback = request("OPTIONS", "/state", null, "http://127.0.0.1:8080", null);
        assertEquals(204, loopback.statusCode());
        assertEquals("http://127.0.0.1:8080",
                loopback.headers().firstValue("access-control-allow-origin").orElse(null));
        assertTrue(loopback.headers().firstValue("access-control-allow-headers").orElse("").contains("Authorization"));
        HttpResponse<String> evil = request("OPTIONS", "/state", null, "https://evil.example.com", null);
        assertEquals(204, evil.statusCode());
        assertTrue(evil.headers().firstValue("access-control-allow-origin").isEmpty());
    }

    @Test
    void companionPeekDoesNotDrain() {
        CompanionState companion = CompanionState.get();
        companion.reset();
        companion.addEvent("{\"kind\":\"a\"}");
        companion.addEvent("{\"kind\":\"b\"}");
        List<String> first = companion.peekEvents();
        List<String> second = companion.peekEvents();
        assertEquals(first, second);
        assertEquals(2, first.size());
        assertEquals(2, companion.drainEvents().size());
        assertTrue(companion.peekEvents().isEmpty());
        companion.reset();
    }

    private long pauseFixtureTask() {
        TaskQueue queue = TaskQueue.getInstance();
        long id = queue.createActiveTrackingTask("#fixture", "fixture");
        TaskQueue.TaskHandle handle = queue.captureActiveHandle("#fixture");
        assertTrue(queue.fail(handle, "#fixture", "fixture failure"));
        assertTrue(queue.getQueueState().paused());
        return id;
    }
}
