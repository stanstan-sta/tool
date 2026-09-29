package com.mindcraft.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NestedCraftHandoffAdversarialTest {
    private final TaskQueue queue = TaskQueue.getInstance();
    private Thread runner;

    @AfterEach
    void tearDown() throws Exception {
        queue.cancelAll();
        if (runner != null) runner.join(2000);
    }

    @Test
    void rapidChildCompletionsAlwaysRestoreOwnerBeforePublishingTerminal() throws Exception {
        queue.createActiveTrackingTask("#obtain", "obtain");
        queue.createNestedTrackingTask("#craft", "craft");
        TaskQueue.TaskHandle owner = queue.captureActiveHandle("#craft");
        assertNotNull(owner);

        int rounds = 32;
        AtomicInteger completed = new AtomicInteger();
        runner = WorkerThreads.start("test-nested-handoff", () ->
                CommandExecutor.runNestedCraftGoals(owner, () ->
                        completed.get() < rounds
                                ? new CommandExecutor.MakeStep(
                                        CommandExecutor.MakeStepKind.MINE,
                                        "stone",
                                        1,
                                        "#adversarial-step")
                                : null));

        for (int i = 0; i < rounds; i++) {
            TaskQueue.TaskHandle child = awaitChild(owner.id());
            completed.incrementAndGet();
            assertTrue(queue.complete(child, child.command(), "rapid-" + i));
        }

        runner.join(3000);
        assertFalse(runner.isAlive(), "runner hung during child-to-owner handoff");
        assertTrue(queue.awaitTerminal(owner.id(), 100).success());
        assertEquals("#obtain", queue.getQueueState().active());
    }

    private TaskQueue.TaskHandle awaitChild(long ownerId) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            TaskQueue.TaskHandle active = queue.captureActiveHandle("#adversarial-step");
            if (active != null && active.id() != ownerId) return active;
            Thread.sleep(2);
        }
        fail("nested child did not become active: " + queue.getQueueState());
        return null;
    }
}
