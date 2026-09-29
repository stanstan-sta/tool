package com.mindcraft.bridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class QueueHttpGenerationTest {
    private final TaskQueue queue = TaskQueue.getInstance();
    private BridgeHttpServer bridge;
    private HttpClient client;
    private int port;
    private long previousGeneration;

    @BeforeEach
    void setUp() throws Exception {
        queue.cancelAll();
        Field generation = TaskQueue.class.getDeclaredField("clientGeneration");
        generation.setAccessible(true);
        previousGeneration = generation.getLong(queue);
        generation.setLong(queue, 100L);
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
        queue.cancelAll();
        Field generation = TaskQueue.class.getDeclaredField("clientGeneration");
        generation.setAccessible(true);
        generation.setLong(queue, previousGeneration);
    }

    private HttpResponse<String> post(String action, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/queue/" + action))
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private long pausedTask() {
        long id = queue.createActiveTrackingTask("#fixture", "fixture");
        TaskQueue.TaskHandle handle = queue.captureActiveHandle("#fixture");
        assertTrue(queue.fail(handle, "#fixture", "fixture failure"));
        assertTrue(queue.getQueueState().paused());
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"skip", "resume"})
    void staleRequestDoesNotMutateCurrentTask(String action) throws Exception {
        long id = pausedTask();
        HttpResponse<String> response = post(action, "{\"generation\":99}");
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("stale_generation"));
        assertTrue(queue.getQueueState().paused());
        assertEquals(id, queue.getQueueState().activeId());
        assertEquals("fixture failure", queue.getQueueState().lastFailure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"skip", "resume"})
    void malformedGenerationDoesNotMutateCurrentTask(String action) throws Exception {
        long id = pausedTask();
        for (String value : new String[]{"1.5", "\"wrong\"", "99999999999999999999999"}) {
            assertEquals(400, post(action, "{\"generation\":" + value + "}").statusCode());
            assertTrue(queue.getQueueState().paused());
            assertEquals(id, queue.getQueueState().activeId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skip", "resume"})
    void currentRequestAcceptedAndAdvancesGeneration(String action) throws Exception {
        assertEquals(200, post(action, "{\"generation\":101}").statusCode());
        assertFalse(queue.acceptClientGeneration(100L));
        assertEquals(200, post(action, "{\"generation\":101}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"skip", "resume"})
    void legacyRequestWithoutGenerationRemainsCompatible(String action) throws Exception {
        assertEquals(200, post(action, "").statusCode());
        assertTrue(queue.acceptClientGeneration(100L));
    }
}
