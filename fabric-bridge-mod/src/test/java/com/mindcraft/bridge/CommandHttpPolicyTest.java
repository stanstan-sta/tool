package com.mindcraft.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandHttpPolicyTest {
    private void request(String command, int expectedStatus, boolean chat) throws Exception {
        TaskQueue queue = TaskQueue.getInstance();
        queue.cancelAll();
        BridgeHttpServer bridge = new BridgeHttpServer(0);
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("POST");
        Headers headers = new Headers();
        String token = BridgeConfig.get().bridgeToken;
        assertNotNull(token, "bridge token must be configured for handler tests");
        headers.set("Authorization", "Bearer " + token);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(
                ("{\"command\":\"" + command + "\"}").getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(response);
        try (MockedStatic<ClientThread> client = mockStatic(ClientThread.class);
             MockedStatic<CommandExecutor> executor = mockStatic(CommandExecutor.class, CALLS_REAL_METHODS)) {
            client.when(() -> ClientThread.call(any(Callable.class))).thenReturn(Boolean.TRUE);
            executor.when(() -> CommandExecutor.execute(anyString())).thenAnswer(invocation -> null);
            var handler = BridgeHttpServer.class.getDeclaredMethod("handleCommand", HttpExchange.class);
            handler.setAccessible(true);
            handler.invoke(bridge, exchange);
            verify(exchange).sendResponseHeaders(eq(expectedStatus), anyLong());
            if (expectedStatus == 422) {
                assertTrue(response.toString(StandardCharsets.UTF_8).contains("raw_command_forbidden"));
                executor.verify(() -> CommandExecutor.execute(anyString()), never());
                assertEquals(0, queue.getQueueState().pending());
            } else if (chat) {
                executor.verify(() -> CommandExecutor.execute(command.trim()));
            } else {
                assertTrue(response.toString(StandardCharsets.UTF_8).contains("\"queued\":1"));
            }
        } finally {
            bridge.stop();
            queue.cancelAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/op someone", "  /say forbidden", "say forbidden", "#not_allowed", "whisper: somebody text"})
    void unsupportedCommandsCannotExecuteOrQueue(String command) throws Exception {
        request(command, 422, false);
    }

    @Test
    void chatStillExecutesWithoutTheRawCommandGate() throws Exception {
        boolean enabled = BridgeConfig.get().enableRawCommand;
        try {
            BridgeConfig.get().enableRawCommand = false;
            request("chat: hello", 200, true);
            request("#sleep", 422, false);
        } finally { BridgeConfig.get().enableRawCommand = enabled; }
    }

    @Test
    void allowedBaritoneCommandStillQueues() throws Exception {
        request("#sleep", 200, false);
    }
}
