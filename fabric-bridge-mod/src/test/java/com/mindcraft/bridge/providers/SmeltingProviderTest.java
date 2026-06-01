package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SmeltingProvider")
class SmeltingProviderTest {

    private SmeltingProvider provider;
    private PlanContext emptyCtx;

    @BeforeEach
    void setUp() {
        provider = new SmeltingProvider();
        Map<String, Integer> emptyInventory = new HashMap<>();
        emptyCtx = new PlanContext(emptyInventory, "overworld", true, true);
    }

    @Nested
    @DisplayName("canProvide()")
    class CanProvide {

        @Test
        @DisplayName("returns true for smeltable output items")
        void returnsTrueForSmeltableItems() {
            assertTrue(provider.canProvide("minecraft:iron_ingot", emptyCtx));
            assertTrue(provider.canProvide("minecraft:gold_ingot", emptyCtx));
            assertTrue(provider.canProvide("minecraft:glass", emptyCtx));
            assertTrue(provider.canProvide("minecraft:stone", emptyCtx));
            assertTrue(provider.canProvide("minecraft:cooked_beef", emptyCtx));
            assertTrue(provider.canProvide("minecraft:diamond", emptyCtx));
            assertTrue(provider.canProvide("minecraft:emerald", emptyCtx));
            assertTrue(provider.canProvide("minecraft:charcoal", emptyCtx));
            assertTrue(provider.canProvide("minecraft:smooth_stone", emptyCtx));
            assertTrue(provider.canProvide("minecraft:brick", emptyCtx));
        }

        @Test
        @DisplayName("returns false for non-smeltable items")
        void returnsFalseForNonSmeltableItems() {
            assertFalse(provider.canProvide("minecraft:stick", emptyCtx));
            assertFalse(provider.canProvide("minecraft:dirt", emptyCtx));
            assertFalse(provider.canProvide("minecraft:crafting_table", emptyCtx));
            assertFalse(provider.canProvide("minecraft:netherite_ingot", emptyCtx));
            assertFalse(provider.canProvide("minecraft:ender_pearl", emptyCtx));
        }

        @Test
        @DisplayName("normalizes item IDs")
        void normalizesItemIds() {
            assertTrue(provider.canProvide("IRON_INGOT", emptyCtx));
            assertTrue(provider.canProvide("minecraft:IRON_INGOT", emptyCtx));
            assertTrue(provider.canProvide("glass", emptyCtx));
        }
    }

    @Nested
    @DisplayName("plan()")
    class Plan {

        @Test
        @DisplayName("returns NO_RECIPE for unknown item")
        void returnsNoRecipeForUnknown() {
            ProviderPlan plan = provider.plan("minecraft:netherite_ingot", 1, emptyCtx);

            assertFalse(plan.ok());
            assertEquals("NO_RECIPE", plan.failureCode());
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("resolves fuel via provider chain even when not in inventory")
        void resolvesFuelViaProviders() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:cobblestone", 10);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);

            assertTrue(plan.ok(), "Should resolve fuel through available providers even without fuel in inventory");
            assertTrue(plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("mine")
                    && s.payloadJson().contains("coal")),
                "Should mine coal when no inventory fuel is available");
            assertFalse(plan.steps().stream()
                .anyMatch(s -> s.payloadJson().contains("coal_block")),
                "Should not plan coal_block as the default missing fuel");
        }

        @Test
        @DisplayName("uses existing wood fuel instead of planning coal")
        void usesExistingWoodFuel() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:cobblestone", 10);
            inventory.put("minecraft:oak_planks", 4);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);

            assertTrue(plan.ok());
            assertFalse(plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("mine")
                    && s.payloadJson().contains("coal")),
                "Existing planks should satisfy fuel without mining coal");
        }

        @Test
        @DisplayName("resolves input via providers even when not in inventory")
        void resolvesInputViaProviders() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);

            assertTrue(plan.ok(), "Should resolve input through available providers even without cobblestone in inventory");
        }

        @Test
        @DisplayName("includes furnace crafting if no furnace in inventory")
        void includesFurnaceCrafting() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:cobblestone", 10);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);

            assertTrue(plan.ok());
            boolean hasCraftFurnace = plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("craft")
                    && s.payloadJson().contains("furnace"));
            assertTrue(hasCraftFurnace, "Should craft furnace if none in inventory");
        }

        @Test
        @DisplayName("skips furnace crafting if already have one")
        void skipsFurnaceCrafting() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:cobblestone", 10);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);

            assertTrue(plan.ok());
            boolean hasCraftFurnace = plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("craft")
                    && s.payloadJson().contains("furnace"));
            assertFalse(hasCraftFurnace, "Should not craft furnace if already have one");
        }

        @Test
        @DisplayName("includes smelt step with correct count and input")
        void includesSmeltStep() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:cobblestone", 20);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 10, ctx);

            assertTrue(plan.ok());
            boolean hasSmeltStep = plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("raw_command")
                    && s.payloadJson().contains("smelt")
                    && s.payloadJson().contains("cobblestone")
                    && s.payloadJson().contains("10"));
            assertTrue(hasSmeltStep, "Should have smelt step with input and count 10");
        }

        @Test
        @DisplayName("returns ok=true for smeltable items with proper inventory")
        void returnsOkTrue() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:cobblestone", 5);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);
            assertTrue(plan.ok());
        }

        @Test
        @DisplayName("handles large counts")
        void handlesLargeCounts() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:cobblestone", 2000);
            inventory.put("minecraft:coal_block", 20);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1000, ctx);

            assertTrue(plan.ok());
            boolean hasSmeltStep = plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("raw_command")
                    && s.payloadJson().contains("1000"));
            assertTrue(hasSmeltStep, "Should handle large count");
        }

        @Test
        @DisplayName("works for cooked food from raw food")
        void worksForCookedFood() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:beef", 5);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:cooked_beef", 3, ctx);

            assertTrue(plan.ok());
            boolean hasSmeltStep = plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("raw_command")
                    && s.payloadJson().contains("smelt")
                    && s.payloadJson().contains("beef")
                    && s.payloadJson().contains("3"));
            assertTrue(hasSmeltStep, "Should smelt beef into cooked_beef");
        }

        @Test
        @DisplayName("works for brick from clay_ball")
        void worksForBrickFromClayBall() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:clay_ball", 10);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:brick", 5, ctx);

            assertTrue(plan.ok());
            boolean hasSmeltStep = plan.steps().stream()
                .anyMatch(s -> s.actionType().equals("raw_command")
                    && s.payloadJson().contains("smelt")
                    && s.payloadJson().contains("clay_ball")
                    && s.payloadJson().contains("5"));
            assertTrue(hasSmeltStep, "Should smelt clay_ball into brick");
        }

        @Test
        @DisplayName("failureCode is null on success")
        void failureCodeNullOnSuccess() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:furnace", 1);
            inventory.put("minecraft:cobblestone", 5);
            inventory.put("minecraft:coal_block", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:stone", 1, ctx);

            assertTrue(plan.ok());
            assertNull(plan.failureCode());
        }
    }
}
