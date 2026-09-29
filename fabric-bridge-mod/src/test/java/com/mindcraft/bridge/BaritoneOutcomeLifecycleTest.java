package com.mindcraft.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaritoneOutcomeLifecycleTest {
    private final TaskQueue queue = TaskQueue.getInstance();

    @AfterEach
    void cleanUp() {
        queue.cancelAll();
    }

    private void route(String message) throws Exception {
        Method method = StateCollector.class.getDeclaredMethod("routeBaritoneToTaskQueue", String.class);
        method.setAccessible(true);
        method.invoke(null, "[Baritone] " + message);
    }

    private String currentToken() {
        TaskQueue.TaskHandle handle = queue.captureActiveHandle(queue.getQueueState().active());
        return handle.id() + "-" + handle.generation();
    }

    @Test
    void missingCraftTableFailsBeforeAnyIdleFallback() throws Exception {
        queue.enqueue("#craft");
        route("Bridge task failed: " + currentToken() + " - not_found");
        assertTrue(queue.getQueueState().paused());
        assertEquals("not_found", queue.getQueueState().lastFailure());
    }

    @Test
    void daytimeSleepFailureIsNotSuccess() throws Exception {
        queue.enqueue("#sleep");
        route("Bridge task failed: " + currentToken() + " - not_night");
        assertTrue(queue.getQueueState().paused());
        assertEquals("not_night", queue.getQueueState().lastFailure());
    }

    @Test
    void plainCraftWithoutInventoryCallbackCompletes() throws Exception {
        queue.enqueue("#craft");
        long id = queue.getQueueState().activeId();
        route("Bridge task complete: " + currentToken());
        assertTrue(queue.awaitTerminal(id, 5_000L).success());
        assertEquals("idle", queue.getQueueState().status());
    }

    @Test
    void optionalBaritoneDisplayPrefixDoesNotHideTaggedOutcomes() {
        assertTrue(StateCollector.shouldRouteBaritoneStatus(true, "Bridge task failed: 7-2 - not_night"));
        assertTrue(StateCollector.shouldRouteBaritoneStatus(true, "[B] Bridge task complete: 7-2"));
        assertFalse(StateCollector.shouldRouteBaritoneStatus(false, "Bridge task complete: 7-2"));
    }

    @Test
    void cancellationRetiresOnlyTheMatchingTask() throws Exception {
        queue.enqueue("#task interact chest");
        String stale = currentToken();
        queue.cancelAll();
        queue.enqueue("#task interact furnace");
        String current = currentToken();
        route("Bridge task cancelled: " + stale + " - cancelled");
        assertEquals("executing", queue.getQueueState().status());
        route("Bridge task cancelled: " + current + " - cancelled");
        assertTrue(queue.getQueueState().paused());
        assertEquals("CANCELLED", queue.getQueueState().failureCode());
    }

    @Test
    void controlCommandsDoNotWaitForPlanCompletion() throws Exception {
        Method classify = TaskQueue.class.getDeclaredMethod("classify", String.class, Runnable.class, String.class);
        classify.setAccessible(true);
        for (String command : new String[] {"#task cancel", "#task status", "#task queue", "#task clear"}) {
            Object task = classify.invoke(queue, command, null, null);
            Method completion = task.getClass().getDeclaredMethod("completion");
            completion.setAccessible(true);
            assertEquals(TaskQueue.CompletionPolicy.IMMEDIATE, completion.invoke(task), command);
        }
    }

    @Test
    void tokenProducerMatchesTheCommandIncludingSleepCoordinates() {
        assertEquals("getTaskPlanProcess", TaskQueue.baritoneTokenProcessGetter("#task interact chest"));
        assertEquals("getTaskPlanProcess", TaskQueue.baritoneTokenProcessGetter("#craft"));
        assertEquals("getSleepInBedProcess", TaskQueue.baritoneTokenProcessGetter("#sleep 1 64 2"));
        assertNull(TaskQueue.baritoneTokenProcessGetter("#mine 1 coal_ore"));
    }

    @Test
    void missingTokenApiRejectsBeforeCommandExecution() throws Exception {
        Method classify = TaskQueue.class.getDeclaredMethod("classify", String.class, Runnable.class, String.class);
        classify.setAccessible(true);
        Object task = classify.invoke(queue, "#sleep", null, null);
        var active = TaskQueue.class.getDeclaredField("activeTask");
        active.setAccessible(true);
        active.set(queue, task);
        Method dispatch = TaskQueue.class.getDeclaredMethod("dispatchBaritoneTask", task.getClass());
        dispatch.setAccessible(true);
        try (MockedStatic<ClientThread> client = mockStatic(ClientThread.class);
             MockedStatic<CommandExecutor> commands = mockStatic(CommandExecutor.class)) {
            client.when(() -> ClientThread.run(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            });
            // Baritone is absent from this unit runtime: reflection cannot install a token.
            dispatch.invoke(queue, task);
            commands.verify(() -> CommandExecutor.execute(anyString()), never());
            assertEquals("baritone_task_token_unavailable", queue.getQueueState().lastFailure());
            assertTrue(queue.getQueueState().paused());
        }
    }
}
