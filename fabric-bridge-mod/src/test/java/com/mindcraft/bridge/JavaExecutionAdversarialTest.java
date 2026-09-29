package com.mindcraft.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExecutionAdversarialTest {

    private final TaskQueue queue = TaskQueue.getInstance();

    @AfterEach
    void tearDown() {
        queue.cancelAll();
    }

    @Test
    void staleTaskHandleCannotCompleteReplacementTask() {
        long cancelledTask = queue.createActiveTrackingTask("#use_item", "use_item");
        assertTrue(cancelledTask > 0);
        TaskQueue.TaskHandle stale = queue.captureActiveHandle("#use_item");
        queue.cancelAll();

        long replacementTask = queue.createActiveTrackingTask("#use_item", "use_item");
        assertTrue(replacementTask > cancelledTask);
        assertEquals("executing", queue.getQueueState().status());

        assertFalse(queue.complete(stale, "#use_item", "late-worker"));
        assertEquals("executing", queue.getQueueState().status());
        assertEquals(replacementTask, queue.getQueueState().activeId());
    }

    @Test
    void selfExecutingBatchActionIsQueuedBehindActiveTask() throws Exception {
        long active = queue.createActiveTrackingTask("#move", "move");
        assertTrue(active > 0);

        BridgeHttpServer server = new BridgeHttpServer(0);
        try {
            Method process = BridgeHttpServer.class.getDeclaredMethod(
                    "processTypedBatchAction", String.class, List.class, List.class);
            process.setAccessible(true);
            List<CommandExecutor.TranslatedAction> results = new ArrayList<>();
            List<Long> taskIds = new ArrayList<>();

            int queued = (Integer) process.invoke(server,
                    "{\"type\":\"attack\",\"target_type\":\"zombie\"}",
                    results, taskIds);

            assertEquals(1, queued);
            assertEquals(1, taskIds.size());
            assertEquals(1, results.size());
            assertTrue(results.get(0).ok());
            assertEquals("self_executing", results.get(0).lifecycle());
            assertEquals(active, queue.getQueueState().activeId());
            assertEquals(1, queue.getQueueState().pending());
        } finally {
            server.stop();
        }
    }

    @Test
    void staleUnlabelledBaritoneCompletionIsIgnored() {
        long originalSettle = BridgeConfig.get().mineSettleMs;
        BridgeConfig.get().mineSettleMs = 0;
        try {
            queue.enqueue("#mine 1 coal_ore");
            queue.cancelAll();
            queue.enqueue("#mine 1 diamond_ore");
            assertEquals("executing", queue.getQueueState().status());

            // StateCollector cannot associate this unlabelled Baritone log event
            // with the cancelled command, so it is applied to the replacement.
            queue.onBaritoneComplete();
            assertEquals("executing", queue.getQueueState().status());
            assertEquals("#mine 1 diamond_ore", queue.getQueueState().active());
        } finally {
            BridgeConfig.get().mineSettleMs = originalSettle;
        }
    }

    @Test
    void nestedCompletionIsRecordedByTaskIdAndRestoresParent() {
        long parent = queue.createActiveTrackingTask("#obtain", "obtain");
        long child = queue.createNestedTrackingTask("#child", "child");
        TaskQueue.TaskHandle childHandle = queue.captureActiveHandle("#child");

        assertTrue(queue.complete(childHandle, "#child", "test"));
        TaskQueue.TaskTerminal terminal = queue.awaitTerminal(child, 100L);

        assertTrue(terminal.success());
        assertEquals(parent, queue.getQueueState().activeId());
        assertEquals("#obtain", queue.getQueueState().active());
    }

    @Test
    void workSpawnedByActiveDescriptorPrecedesOriginalBatchSuffix() throws Exception {
        long launcher = queue.createActiveTrackingTask("#craft", "craft");
        TaskQueue.TaskHandle handle = queue.captureActiveHandle("#craft");
        queue.enqueueTypedActions(List.of(new TaskQueue.TypedAction(
                "select_slot", "{\"type\":\"select_slot\",\"slot\":1}")));

        TaskQueue.EnqueueResult[] spawned = new TaskQueue.EnqueueResult[1];
        TaskQueue.withTaskHandle(handle, () -> {
            spawned[0] = queue.enqueueDetailed(List.of(
                    new TaskQueue.QueuedCommand("#mine 1 oak_log", "craft"),
                    new TaskQueue.QueuedCommand("#mine 1 cobblestone", "craft")));
            return null;
        });
        assertTrue(queue.dismissActiveTask(launcher));

        assertEquals("#mine 1 oak_log", queue.getQueueState().active());
        assertEquals(2, queue.getQueueState().pending());
        assertTrue(queue.dismissActiveTask(spawned[0].taskIds().get(0)));
        assertEquals("#mine 1 cobblestone", queue.getQueueState().active());
        assertTrue(queue.dismissActiveTask(spawned[0].taskIds().get(1)));
        assertEquals("#select_slot", queue.getQueueState().active());
    }
}
