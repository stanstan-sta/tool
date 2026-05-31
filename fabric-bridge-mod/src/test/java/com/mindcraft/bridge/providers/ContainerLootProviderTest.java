package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContainerLootProvider")
class ContainerLootProviderTest {

    private ContainerLootProvider provider;
    private PlanContext emptyCtx;

    @BeforeEach
    void setUp() {
        provider = new ContainerLootProvider();
        Map<String, Integer> emptyInventory = new HashMap<>();
        emptyCtx = new PlanContext(emptyInventory, "overworld", true, true);
    }

    @Nested
    @DisplayName("canProvide()")
    class CanProvide {

        @Test
        @DisplayName("returns true for all lootable items")
        void returnsTrueForLootableItems() {
            Set<String> lootableItems = Set.of(
                "minecraft:enchanted_golden_apple", "minecraft:name_tag", "minecraft:saddle",
                "minecraft:leather_horse_armor", "minecraft:iron_horse_armor",
                "minecraft:golden_horse_armor", "minecraft:diamond_horse_armor",
                "minecraft:nautilus_shell", "minecraft:heart_of_the_sea",
                "minecraft:experience_bottle", "minecraft:dragon_breath",
                "minecraft:elytra", "minecraft:totem_of_undying",
                "minecraft:trident", "minecraft:shulker_shell",
                "minecraft:spire_armor_trim_smithing_template",
                "minecraft:snout_armor_trim_smithing_template",
                "minecraft:rib_armor_trim_smithing_template",
                "minecraft:ward_armor_trim_smithing_template",
                "minecraft:silence_armor_trim_smithing_template"
            );

            for (String item : lootableItems) {
                assertTrue(provider.canProvide(item, emptyCtx),
                    "Should provide " + item);
            }
        }

        @Test
        @DisplayName("returns false for non-lootable items")
        void returnsFalseForNonLootableItems() {
            assertFalse(provider.canProvide("minecraft:dirt", emptyCtx));
            assertFalse(provider.canProvide("minecraft:cobblestone", emptyCtx));
            assertFalse(provider.canProvide("minecraft:stick", emptyCtx));
            assertFalse(provider.canProvide("minecraft:iron_ingot", emptyCtx));
            assertFalse(provider.canProvide("minecraft:diamond", emptyCtx));
        }

        @Test
        @DisplayName("normalizes item IDs")
        void normalizesItemIds() {
            assertTrue(provider.canProvide("ELytra", emptyCtx));
            assertTrue(provider.canProvide("minecraft:ELYTRA", emptyCtx));
            assertTrue(provider.canProvide("totem_of_undying", emptyCtx));
        }
    }

    @Nested
    @DisplayName("plan()")
    class Plan {

        @Test
        @DisplayName("returns empty plan if item already in inventory")
        void returnsEmptyPlanIfAlreadyHave() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:elytra", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:elytra", 1, ctx);

            assertTrue(plan.ok());
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("returns empty plan if inventory has enough count")
        void returnsEmptyPlanIfEnoughCount() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:elytra", 5);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:elytra", 3, ctx);

            assertTrue(plan.ok());
            assertTrue(plan.steps().isEmpty());
        }

        @Test
        @DisplayName("returns loot step if item not in inventory")
        void returnsLootStepIfMissing() {
            ProviderPlan plan = provider.plan("minecraft:elytra", 1, emptyCtx);

            assertTrue(plan.ok());
            assertEquals(1, plan.steps().size());
            assertEquals("loot", plan.steps().get(0).actionType());
        }

        @Test
        @DisplayName("returns loot step if inventory count is insufficient")
        void returnsLootStepIfInsufficient() {
            Map<String, Integer> inventory = new HashMap<>();
            inventory.put("minecraft:elytra", 1);
            PlanContext ctx = new PlanContext(inventory, "overworld", true, true);

            ProviderPlan plan = provider.plan("minecraft:elytra", 3, ctx);

            assertTrue(plan.ok());
            assertEquals(1, plan.steps().size());
            assertEquals("loot", plan.steps().get(0).actionType());
        }

        @Test
        @DisplayName("includes target and count in payload")
        void includesTargetAndCount() {
            ProviderPlan plan = provider.plan("minecraft:elytra", 3, emptyCtx);

            String payload = plan.steps().get(0).payloadJson();
            assertTrue(payload.contains("elytra"), "Payload should contain item ID");
            assertTrue(payload.contains("3"), "Payload should contain count");
        }

        @Test
        @DisplayName("handles multiple count")
        void handlesMultipleCount() {
            ProviderPlan plan = provider.plan("minecraft:totem_of_undying", 5, emptyCtx);

            assertTrue(plan.ok());
            assertEquals(1, plan.steps().size());
            assertEquals("loot", plan.steps().get(0).actionType());
            assertTrue(plan.steps().get(0).payloadJson().contains("5"));
        }

        @Test
        @DisplayName("returns ok=true for all lootable items")
        void returnsOkTrue() {
            ProviderPlan plan = provider.plan("minecraft:name_tag", 1, emptyCtx);
            assertTrue(plan.ok());
        }

        @Test
        @DisplayName("returns loot step for all armor trim templates")
        void returnsLootStepForTrimTemplates() {
            String[] templates = {
                "minecraft:spire_armor_trim_smithing_template",
                "minecraft:snout_armor_trim_smithing_template",
                "minecraft:rib_armor_trim_smithing_template",
                "minecraft:ward_armor_trim_smithing_template",
                "minecraft:silence_armor_trim_smithing_template"
            };
            for (String template : templates) {
                ProviderPlan plan = provider.plan(template, 1, emptyCtx);
                assertTrue(plan.ok(), "Should succeed for " + template);
                assertEquals(1, plan.steps().size(), "Should have loot step for " + template);
                assertEquals("loot", plan.steps().get(0).actionType());
            }
        }
    }
}
