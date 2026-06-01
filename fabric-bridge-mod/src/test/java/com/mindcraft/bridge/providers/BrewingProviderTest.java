package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.PlanContext;
import com.mindcraft.bridge.ProviderPlan;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrewingProviderTest {

    @Test
    void recursivePotionPlanIncludesIntermediateBrews() {
        BrewingProvider provider = new BrewingProvider();
        Map<String, Integer> inventory = new HashMap<>();
        inventory.put("minecraft:water_bottle", 3);  // Must have actual water bottles, not just glass
        inventory.put("minecraft:blaze_powder", 1);
        inventory.put("minecraft:nether_wart", 1);
        inventory.put("minecraft:golden_carrot", 1);
        inventory.put("minecraft:fermented_spider_eye", 1);
        PlanContext ctx = new PlanContext(inventory, "minecraft:overworld", true, true);

        ProviderPlan plan = provider.plan("minecraft:potion_of_invisibility", 1, ctx);

        assertTrue(plan.ok(), plan.failureCode());
        String joined = plan.steps().toString();
        assertTrue(joined.contains("\"potions\":\"minecraft:water_bottle\""), joined);
        assertTrue(joined.contains("\"ingredient\":\"minecraft:nether_wart\""), joined);
        assertTrue(joined.contains("\"output\":\"minecraft:awkward_potion\""), joined);
        assertTrue(joined.contains("\"potions\":\"minecraft:awkward_potion\""), joined);
        assertTrue(joined.contains("\"ingredient\":\"minecraft:golden_carrot\""), joined);
        assertTrue(joined.contains("\"output\":\"minecraft:potion_of_night_vision\""), joined);
        assertTrue(joined.contains("\"potions\":\"minecraft:potion_of_night_vision\""), joined);
        assertTrue(joined.contains("\"ingredient\":\"minecraft:fermented_spider_eye\""), joined);
        assertTrue(joined.contains("\"output\":\"minecraft:potion_of_invisibility\""), joined);
    }

    @Test
    void waterBottleWithoutWaterBottlesFails() {
        BrewingProvider provider = new BrewingProvider();
        Map<String, Integer> inventory = new HashMap<>();
        inventory.put("minecraft:glass_bottle", 10);  // glass bottles alone are not enough
        inventory.put("minecraft:blaze_powder", 1);
        inventory.put("minecraft:nether_wart", 1);
        PlanContext ctx = new PlanContext(inventory, "minecraft:overworld", true, true);

        ProviderPlan plan = provider.plan("minecraft:potion_of_strength", 1, ctx);

        // Should fail because there are no water bottles to brew with
        assertFalse(plan.ok(), "Plan should fail without water bottles");
        assertTrue(plan.failureCode().contains("NO_WATER_BOTTLES") || plan.failureCode().contains("NO_BASE"),
                "Failure code should indicate missing water bottles, got: " + plan.failureCode());
    }
}
