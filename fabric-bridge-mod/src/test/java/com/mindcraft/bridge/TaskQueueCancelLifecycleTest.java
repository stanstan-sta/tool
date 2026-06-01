package com.mindcraft.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TaskQueue Cancel Lifecycle")
class TaskQueueCancelLifecycleTest {

    private final TaskQueue queue = TaskQueue.getInstance();

    @AfterEach
    void tearDown() {
        queue.cancelAll();
        // Allow watcher threads to observe the null activeTask and exit
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
    }

    @Test
    @DisplayName("cancelAll clears activeTask, empties pending, sets lastFailure='cancelled'")
    void cancelAllClearsState() {
        queue.enqueue("#goto 100 64 200");
        TaskQueue.QueueState afterEnqueue = queue.getQueueState();
        assertNotNull(afterEnqueue.activeId(), "should have active task after enqueue");

        queue.cancelAll();
        TaskQueue.QueueState afterCancel = queue.getQueueState();
        assertNull(afterCancel.activeId(), "activeTask must be null after cancel");
        assertEquals(0, afterCancel.pending(), "pending queue must be empty after cancel");
        assertEquals("cancelled", afterCancel.lastFailure(), "lastFailure should be 'cancelled' after cancel");
    }

    @Test
    @DisplayName("cancel -> enqueue replacement: activeTask = replacement, lastFailure = null, paused = false")
    void cancelThenEnqueueReplacement() {
        queue.enqueue("#goto 100 64 200");
        queue.cancelAll();

        TaskQueue.QueueState afterCancel = queue.getQueueState();
        assertNull(afterCancel.activeId());
        assertEquals("cancelled", afterCancel.lastFailure());

        queue.enqueue("#goto 300 64 400");

        TaskQueue.QueueState afterReplace = queue.getQueueState();
        assertNotNull(afterReplace.activeId(), "replacement must have activeTask set");
        assertNull(afterReplace.lastFailure(), "lastFailure must be null after replacement starts executing");
        assertFalse(afterReplace.paused(), "should not be paused after replacement");
        assertEquals("executing", afterReplace.status(), "status should be 'executing' for replacement");
    }

    @Test
    @DisplayName("createActiveTrackingTask with command X succeeds completeActiveIf(command X)")
    void trackingTaskCompleteActiveIfMatches() {
        long id = queue.createActiveTrackingTask("#test_cmd", "test_type");
        assertTrue(id > 0, "tracking task must be created");
        assertTrue(queue.completeActiveIf("#test_cmd"), "completeActiveIf must match same command");
        TaskQueue.QueueState state = queue.getQueueState();
        assertEquals("idle", state.status(), "task must be completed after match");
    }

    @Test
    @DisplayName("createActiveTrackingTask with command X does not match completeActiveIf(command Y)")
    void trackingTaskCompleteActiveIfMismatch() {
        long id = queue.createActiveTrackingTask("#cmd_x", "test_type");
        assertTrue(id > 0, "tracking task must be created");
        assertFalse(queue.completeActiveIf("#cmd_y"), "completeActiveIf with wrong command must fail");
        TaskQueue.QueueState state = queue.getQueueState();
        assertEquals("executing", state.status(), "task must still be active after mismatch");
        queue.dismissActiveTask(id);
    }

    @Test
    @DisplayName("createActiveTrackingTask with command X fails activeIf(command X)")
    void trackingTaskFailActiveIfMatches() {
        long id = queue.createActiveTrackingTask("#test_cmd", "test_type");
        assertTrue(id > 0, "tracking task must be created");
        assertTrue(queue.failActiveIf("#test_cmd", "test failure"), "failActiveIf must match same command");
        TaskQueue.QueueState state = queue.getQueueState();
        assertEquals("paused", state.status(), "task must be paused after failure match");
        assertEquals("test failure", state.lastFailure(), "lastFailure must be set");
    }

    @Test
    @DisplayName("createActiveTrackingTask with command X does not match failActiveIf(command Y)")
    void trackingTaskFailActiveIfMismatch() {
        long id = queue.createActiveTrackingTask("#cmd_x", "test_type");
        assertTrue(id > 0, "tracking task must be created");
        assertFalse(queue.failActiveIf("#cmd_y", "test failure"), "failActiveIf with wrong command must fail");
        TaskQueue.QueueState state = queue.getQueueState();
        assertEquals("executing", state.status(), "task must still be active after mismatch");
        queue.dismissActiveTask(id);
    }

    @Test
    @DisplayName("cancel -> enqueue -> getQueueState shows no stale 'cancelled' failure")
    void noStaleFailureAfterCancelReplace() {
        queue.enqueue("#mine 10 diamond_ore");
        queue.cancelAll();

        TaskQueue.QueueState s1 = queue.getQueueState();
        assertEquals("cancelled", s1.lastFailure(), "precondition: lastFailure='cancelled' after cancel");
        assertNull(s1.activeId(), "precondition: activeTask=null after cancel");

        queue.enqueue("#goto 0 64 0");
        TaskQueue.QueueState s2 = queue.getQueueState();

        assertNotNull(s2.activeId(), "activeTask must be set for replacement");
        assertNull(s2.lastFailure(), "lastFailure must be null — stale 'cancelled' must not survive");
        assertNotEquals("cancelled", s2.failureCode(), "failureCode must not be CANCELLED for replacement task");
        assertEquals("executing", s2.status());
        assertEquals(0, s2.pending(), "pending should be 0 since replacement is now active");
    }
}
