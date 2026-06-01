package com.mindcraft.bridge.integration;

import com.mindcraft.bridge.workers.*;
import com.mindcraft.bridge.workers.crafting.SmithWorker;
import com.mindcraft.bridge.workers.crafting.BrewWorker;
import com.mindcraft.bridge.workers.crafting.EnchantWorker;
import com.mindcraft.bridge.workers.combat.CombatWorker;
import com.mindcraft.bridge.workers.gathering.FarmWorker;
import com.mindcraft.bridge.workers.gathering.LootWorker;
import com.mindcraft.bridge.workers.gathering.FishWorker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for worker classes.
 *
 * These tests validate the worker interface contract and registry wiring
 * without requiring a running Minecraft server. Tests that exercise real
 * block placement and screen interaction need the Fabric Game Test API
 * (fabric-game-test-api-v1) and must run inside a Minecraft test server.
 */
@DisplayName("Worker Integration Tests")
class WorkerIntegrationTest {

    @Nested
    @DisplayName("Worker interface contract")
    class WorkerContract {

        @Test
        @DisplayName("SmithWorker implements Worker")
        void smithImplementsWorker() {
            assertTrue(new SmithWorker() instanceof Worker);
        }

        @Test
        @DisplayName("BrewWorker implements Worker")
        void brewImplementsWorker() {
            assertTrue(new BrewWorker() instanceof Worker);
        }

        @Test
        @DisplayName("EnchantWorker implements Worker")
        void enchantImplementsWorker() {
            assertTrue(new EnchantWorker() instanceof Worker);
        }

        @Test
        @DisplayName("CombatWorker implements Worker")
        void combatImplementsWorker() {
            assertTrue(new CombatWorker() instanceof Worker);
        }

        @Test
        @DisplayName("FarmWorker implements Worker")
        void farmImplementsWorker() {
            assertTrue(new FarmWorker() instanceof Worker);
        }

        @Test
        @DisplayName("LootWorker implements Worker")
        void lootImplementsWorker() {
            assertTrue(new LootWorker() instanceof Worker);
        }

        @Test
        @DisplayName("FishWorker implements Worker")
        void fishImplementsWorker() {
            assertTrue(new FishWorker() instanceof Worker);
        }
    }

    @Nested
    @DisplayName("Worker.requiresThread defaults")
    class RequiresThreadDefaults {

        @Test
        @DisplayName("SmithWorker requires thread by default")
        void smithRequiresThread() {
            assertTrue(new SmithWorker().requiresThread());
        }

        @Test
        @DisplayName("BrewWorker requires thread by default")
        void brewRequiresThread() {
            assertTrue(new BrewWorker().requiresThread());
        }

        @Test
        @DisplayName("EnchantWorker requires thread by default")
        void enchantRequiresThread() {
            assertTrue(new EnchantWorker().requiresThread());
        }

        @Test
        @DisplayName("CombatWorker requires thread by default")
        void combatRequiresThread() {
            assertTrue(new CombatWorker().requiresThread());
        }

        @Test
        @DisplayName("FarmWorker requires thread by default")
        void farmRequiresThread() {
            assertTrue(new FarmWorker().requiresThread());
        }

        @Test
        @DisplayName("LootWorker requires thread by default")
        void lootRequiresThread() {
            assertTrue(new LootWorker().requiresThread());
        }

        @Test
        @DisplayName("FishWorker requires thread by default")
        void fishRequiresThread() {
            assertTrue(new FishWorker().requiresThread());
        }
    }

    @Nested
    @DisplayName("ActionRegistry wiring")
    class ActionRegistryTests {

        @Test
        @DisplayName("registry is a singleton")
        void singleton() {
            assertSame(ActionRegistry.get(), ActionRegistry.get());
        }

        @Test
        @DisplayName("has all registered workers")
        void hasAllWorkers() {
            ActionRegistry registry = ActionRegistry.get();
            assertTrue(registry.hasWorker("smith"), "smith");
            assertTrue(registry.hasWorker("brew"), "brew");
            assertTrue(registry.hasWorker("enchant"), "enchant");
            assertTrue(registry.hasWorker("attack"), "attack");
            assertTrue(registry.hasWorker("farm"), "farm");
            assertTrue(registry.hasWorker("loot"), "loot");
            assertTrue(registry.hasWorker("fish"), "fish");
            assertTrue(registry.hasWorker("shear"), "shear");
            assertTrue(registry.hasWorker("milk"), "milk");
            assertTrue(registry.hasWorker("breed"), "breed");
            assertTrue(registry.hasWorker("tame"), "tame");
        }

        @Test
        @DisplayName("returns correct worker types")
        void workerTypes() {
            ActionRegistry registry = ActionRegistry.get();
            assertInstanceOf(SmithWorker.class, registry.getWorker("smith"));
            assertInstanceOf(BrewWorker.class, registry.getWorker("brew"));
            assertInstanceOf(EnchantWorker.class, registry.getWorker("enchant"));
            assertInstanceOf(CombatWorker.class, registry.getWorker("attack"));
            assertInstanceOf(FarmWorker.class, registry.getWorker("farm"));
            assertInstanceOf(LootWorker.class, registry.getWorker("loot"));
            assertInstanceOf(FishWorker.class, registry.getWorker("fish"));
        }

        @Test
        @DisplayName("getWorker returns null for unknown type")
        void unknownWorker() {
            assertNull(ActionRegistry.get().getWorker("nonexistent"));
        }

        @Test
        @DisplayName("hasWorker returns false for unknown type")
        void hasUnknownWorker() {
            assertFalse(ActionRegistry.get().hasWorker("nonexistent"));
        }
    }

    @Nested
    @DisplayName("WorkerContext record")
    class WorkerContextTests {

        @Test
        @DisplayName("can be created with null player and world")
        void creationWithNulls() {
            WorkerContext ctx = new WorkerContext(
                null,
                () -> false,
                "{\"type\":\"smith\"}"
            );
            assertNull(ctx.taskQueue());
            assertFalse(ctx.isCancelled().get());
            assertEquals("{\"type\":\"smith\"}", ctx.actionJson());
        }

        @Test
        @DisplayName("isCancelled supplier works")
        void isCancelledSupplier() {
            boolean[] flag = {false};
            WorkerContext ctx = new WorkerContext(
                null,
                () -> flag[0],
                "{}"
            );
            assertFalse(ctx.isCancelled().get());
            flag[0] = true;
            assertTrue(ctx.isCancelled().get());
        }
    }

    @Nested
    @DisplayName("WorkerResult factories")
    class WorkerResultTests {

        @Test
        @DisplayName("success creates ok result")
        void success() {
            WorkerResult r = WorkerResult.success("crafted");
            assertTrue(r.ok());
            assertEquals("crafted", r.output());
            assertNull(r.error());
        }

        @Test
        @DisplayName("failure creates error result")
        void failure() {
            WorkerResult r = WorkerResult.failure("no table");
            assertFalse(r.ok());
            assertEquals("no table", r.error());
            assertNull(r.output());
        }
    }
}
