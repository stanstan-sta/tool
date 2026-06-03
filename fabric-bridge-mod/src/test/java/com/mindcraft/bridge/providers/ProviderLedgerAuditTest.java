package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.PlanContext;
import com.mindcraft.bridge.PlanLedger;
import com.mindcraft.bridge.PlanStep;
import com.mindcraft.bridge.ProviderPlan;
import com.mindcraft.bridge.TaskQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderLedgerAuditTest {

    @AfterEach
    void tearDownQueue() {
        TaskQueue.getInstance().cancelAll();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void ledgerForkRollbackAndCommitAreExplicit() {
        PlanLedger ledger = PlanLedger.fromInventory(Map.of("minecraft:oak_planks", 2));

        PlanLedger failedFork = ledger.fork();
        failedFork.consume("minecraft:oak_planks", 2);
        failedFork.produce("minecraft:stick", 4);

        assertEquals(2, ledger.available("minecraft:oak_planks"));
        assertEquals(0, ledger.available("minecraft:stick"));

        PlanLedger committedFork = ledger.fork();
        committedFork.consume("minecraft:oak_planks", 2);
        committedFork.produce("minecraft:stick", 4);
        ledger.commitFrom(committedFork);

        assertEquals(0, ledger.available("minecraft:oak_planks"));
        assertEquals(4, ledger.available("minecraft:stick"));
    }

    @Test
    void failedInventoryProviderPlanDoesNotConsumeParentLedger() {
        PlanContext ctx = new PlanContext(
                Map.of("minecraft:oak_planks", 1),
                "minecraft:overworld",
                true,
                true);

        ProviderPlan plan = new InventoryProvider().plan("minecraft:oak_planks", 2, ctx);

        assertFalse(plan.ok());
        assertEquals("MISSING_ITEM", plan.failureCode());
        assertEquals(1, ctx.ledger().available("minecraft:oak_planks"));
    }

    @Test
    void craftingSurplusOutputRemainsInLedger() {
        PlanContext ctx = new PlanContext(
                Map.of("minecraft:oak_planks", 2),
                "minecraft:overworld",
                true,
                true);

        ProviderPlan plan = new CraftingProvider().plan("minecraft:stick", 1, ctx);

        assertTrue(plan.ok(), plan.failureCode());
        assertEquals(1, plan.steps().size());
        assertEquals("craft", plan.steps().get(0).actionType());
        assertEquals(3, ctx.ledger().available("minecraft:stick"));
        assertEquals(0, ctx.ledger().available("minecraft:oak_planks"));
    }

    @Test
    void miningPlansOnlyAdditionalMissingCount() {
        PlanContext ctx = new PlanContext(
                Map.of(
                        "minecraft:cobblestone", 2,
                        "minecraft:wooden_pickaxe", 1),
                "minecraft:overworld",
                true,
                true);

        ProviderPlan plan = new MiningProvider().plan("minecraft:cobblestone", 5, ctx);

        assertTrue(plan.ok(), plan.failureCode());
        PlanStep mine = onlyStepOfType(plan.steps(), "mine");
        assertTrue(mine.payloadJson().contains("\"target\":\"cobblestone\""), mine.payloadJson());
        assertTrue(mine.payloadJson().contains("\"count\":3"), mine.payloadJson());
        assertEquals(0, ctx.ledger().available("minecraft:cobblestone"));
    }

    @Test
    void miningHonorsReservedToolWithoutCraftingAnotherOne() {
        PlanLedger ledger = PlanLedger.fromInventory(Map.of());
        ledger.reserveTool("minecraft:iron_pickaxe");
        PlanContext ctx = new PlanContext(Map.of(), "minecraft:overworld", true, true).withLedger(ledger);

        ProviderPlan plan = new MiningProvider().plan("minecraft:diamond", 1, ctx);

        assertTrue(plan.ok(), plan.failureCode());
        assertFalse(plan.steps().stream()
                .anyMatch(step -> step.actionType().equals("craft")
                        && step.payloadJson().contains("iron_pickaxe")));
        assertNotNull(onlyStepOfType(plan.steps(), "mine"));
        assertTrue(ctx.ledger().hasToolOrReserved("minecraft:iron_pickaxe"));
    }

    @Test
    void smeltingConsumesExistingFuelVirtuallyAcrossSequentialPlans() {
        PlanContext ctx = new PlanContext(
                Map.of(
                        "minecraft:furnace", 1,
                        "minecraft:cobblestone", 9,
                        "minecraft:coal", 1),
                "minecraft:overworld",
                true,
                true);
        SmeltingProvider provider = new SmeltingProvider();

        ProviderPlan first = provider.plan("minecraft:stone", 8, ctx);
        assertTrue(first.ok(), first.failureCode());
        assertFalse(first.steps().stream()
                .anyMatch(step -> step.actionType().equals("mine")
                        && step.payloadJson().contains("coal")));
        assertEquals(0, ctx.ledger().available("minecraft:coal"));

        ProviderPlan second = provider.plan("minecraft:stone", 1, ctx);
        assertTrue(second.ok(), second.failureCode());
        assertTrue(second.steps().stream()
                .anyMatch(step -> step.actionType().equals("mine")
                        && step.payloadJson().contains("coal")),
                second.steps().toString());
    }

    @Test
    void returnTravelFailureLeavesFollowingMovementPendingAndNotActive() {
        TaskQueue queue = TaskQueue.getInstance();
        long travelId = queue.createActiveTrackingTask("#return_to_overworld", "return_to_overworld");
        assertTrue(travelId > 0);

        TaskQueue.EnqueueResult movement = queue.enqueueDetailed(List.of(
                new TaskQueue.QueuedCommand("#goto 477 102 30", "move")));
        assertEquals(TaskQueue.EnqueueStatus.QUEUED, movement.status());
        assertEquals(1, movement.queued());

        assertTrue(queue.failActiveIf("#return_to_overworld", "portal_travel: dimension change timeout"));
        TaskQueue.QueueState state = queue.getQueueState();

        assertEquals("paused", state.status());
        assertEquals("#return_to_overworld", state.active());
        assertEquals("return_to_overworld", state.activeActionType());
        assertEquals(1, state.pending());
        assertNotEquals("#goto 477 102 30", state.active());
    }

    private static PlanStep onlyStepOfType(List<PlanStep> steps, String actionType) {
        List<PlanStep> matches = steps.stream()
                .filter(step -> step.actionType().equals(actionType))
                .toList();
        assertEquals(1, matches.size(), steps.toString());
        return matches.get(0);
    }
}
