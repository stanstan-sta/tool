package com.mindcraft.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NestedCraftLifecycleTest {
    private final TaskQueue queue = TaskQueue.getInstance();
    private Thread runner;

    @AfterEach
    void tearDown() throws Exception {
        queue.cancelAll();
        if (runner != null) runner.join(2000);
    }

    private TaskQueue.TaskHandle startCraft() {
        queue.createActiveTrackingTask("#obtain", "obtain");
        queue.createNestedTrackingTask("#craft", "craft");
        return queue.captureActiveHandle("#craft");
    }

    private void run(TaskQueue.TaskHandle owner, java.util.concurrent.Callable<CommandExecutor.MakeStep> plan) {
        runner = WorkerThreads.start("test-nested-craft", () -> CommandExecutor.runNestedCraftGoals(owner, plan));
    }

    private TaskQueue.TaskHandle awaitActive(String command) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        do {
            TaskQueue.TaskHandle handle = queue.captureActiveHandle(command);
            if (handle != null) return handle;
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        fail("Expected active " + command + ", got " + queue.getQueueState());
        return null;
    }

    @Test
    void parentAndSuffixWaitForEveryStepAndFreshGoalCheck() throws Exception {
        TaskQueue.TaskHandle craft = startCraft();
        long parent = queue.getQueueState().activeId();
        // The outer batch must remain untouched while obtain and craft are suspended.
        queue.enqueueDetailed(List.of(new TaskQueue.QueuedCommand("#outer-suffix", null)));
        AtomicInteger inventory = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        run(craft, () -> {
            checks.incrementAndGet();
            return switch (inventory.get()) {
                case 0 -> new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.MINE, "oak_log", 1, "#gather-test");
                case 1 -> new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.CRAFT, "oak_planks", 4, "#craft");
                default -> null;
            };
        });
        TaskQueue.TaskHandle gather = awaitActive("#gather-test");
        assertNotEquals(parent, gather.id());
        assertEquals(1, checks.get());
        assertTrue(runner.isAlive());
        assertEquals(1, queue.getQueueState().pending());
        inventory.set(1);
        assertTrue(queue.complete(gather, gather.command(), "test-gathered"));

        TaskQueue.TaskHandle step = awaitActive("#craft");
        // Briefly the owner is restored before the runner starts the concrete craft.
        long deadline = System.currentTimeMillis() + 2000;
        while (step.id() == craft.id() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            step = awaitActive("#craft");
        }
        assertNotEquals(craft.id(), step.id());
        queue.onBaritoneIdle(step.id());
        assertEquals(step.id(), queue.getQueueState().activeId(), "table opening must not complete crafting");
        assertTrue(runner.isAlive());
        inventory.set(4);
        assertTrue(queue.complete(step, "#craft", "test-crafted"));
        runner.join(2000);
        assertFalse(runner.isAlive());
        assertTrue(queue.awaitTerminal(craft.id(), 100).success());
        assertEquals(3, checks.get());
        assertEquals("#obtain", queue.getQueueState().active());
        assertEquals(1, queue.getQueueState().pending());
    }

    @Test
    void alreadySatisfiedGoalCompletesWithoutStartingAChild() throws Exception {
        TaskQueue.TaskHandle craft = startCraft();
        run(craft, () -> null);
        runner.join(2000);
        assertTrue(queue.awaitTerminal(craft.id(), 100).success());
        assertEquals("#obtain", queue.getQueueState().active());
    }

    @Test
    void failedPrerequisiteFailsCraftAndRestoresObtain() throws Exception {
        TaskQueue.TaskHandle craft = startCraft();
        AtomicInteger checks = new AtomicInteger();
        run(craft, () -> {
            checks.incrementAndGet();
            return new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.MINE, "iron_ore", 1, "#failed-step");
        });
        TaskQueue.TaskHandle step = awaitActive("#failed-step");
        assertTrue(queue.fail(step, step.command(), "not_found"));
        runner.join(2000);
        TaskQueue.TaskTerminal outcome = queue.awaitTerminal(craft.id(), 100);
        assertFalse(outcome.success());
        assertTrue(outcome.reason().contains("not_found"));
        assertEquals(1, checks.get());
        assertEquals("#obtain", queue.getQueueState().active());
    }

    @Test
    void planningFailureIsTerminalInsteadOfLaunchingOrHanging() throws Exception {
        TaskQueue.TaskHandle craft = startCraft();
        run(craft, () -> { throw new IllegalStateException("no recipe"); });
        runner.join(2000);
        TaskQueue.TaskTerminal outcome = queue.awaitTerminal(craft.id(), 100);
        assertFalse(outcome.success());
        assertEquals("no recipe", outcome.reason());
        assertEquals("#obtain", queue.getQueueState().active());
    }

    @Test
    void invalidNestedCraftDispatchFailsImmediately() {
        queue.createActiveTrackingTask("#obtain", "obtain");
        long craft = queue.startNestedTypedAction("craft", "{\"type\":\"craft\"}");
        TaskQueue.TaskTerminal outcome = queue.awaitTerminal(craft, 100);
        assertFalse(outcome.success());
        assertTrue(outcome.reason().contains("missing 'item'"));
        assertEquals("#obtain", queue.getQueueState().active());
    }

    @Test
    void timeoutUnwindsTheCraftAndItsSubstep() throws Exception {
        TaskQueue.TaskHandle craft = startCraft();
        run(craft, () -> new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.MINE, "coal", 1, "#blocked-step"));
        TaskQueue.TaskHandle step = awaitActive("#blocked-step");
        assertFalse(queue.awaitTerminal(craft.id(), 0).success());
        runner.join(2000);
        assertFalse(runner.isAlive());
        assertEquals("#obtain", queue.getQueueState().active());
        assertFalse(queue.complete(step, step.command(), "late"));
        assertTrue(queue.isCancellationRequested(craft));
    }

    @Test
    void cancellationCannotReviveCraftOrSettleItsReplacement() throws Exception {
        TaskQueue.TaskHandle craft = startCraft();
        run(craft, () -> new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.MINE, "coal", 1, "#cancelled-step"));
        TaskQueue.TaskHandle step = awaitActive("#cancelled-step");
        queue.cancelAll();
        long replacement = queue.createActiveTrackingTask("#craft", "craft");
        runner.join(2000);
        assertFalse(runner.isAlive());
        assertFalse(queue.complete(step, step.command(), "late"));
        assertFalse(queue.complete(craft, "#craft", "late"));
        assertEquals(-1, queue.startNestedCommand(craft, "#late-step", "craft", null));
        assertEquals(replacement, queue.getQueueState().activeId());
        assertFalse(queue.awaitTerminal(craft.id(), 100).success());
    }
}
