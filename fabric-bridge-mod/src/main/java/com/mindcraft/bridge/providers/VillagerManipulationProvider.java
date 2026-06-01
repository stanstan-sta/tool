package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class VillagerManipulationProvider implements ItemProvider {
    private static final Set<String> MANIPULATION_ITEMS = Set.of(
        "minecraft:enchanted_book",
        "minecraft:emerald_block",
        "minecraft:lectern"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return MANIPULATION_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        if (id.equals("minecraft:enchanted_book")) {
            return new ProviderPlan(false, "UNSUPPORTED_ENCHANTED_BOOK", steps);
        } else if (id.equals("minecraft:emerald_block")) {
            return new ProviderPlan(false, "UNSUPPORTED_CURE_VILLAGER", steps);
        } else if (id.equals("minecraft:lectern")) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:lectern\",\"count\":1}"));
            return new ProviderPlan(true, null, steps);
        }

        return new ProviderPlan(false, "UNSUPPORTED", steps);
    }

    public ProviderPlan planLecternCycle(String enchantment, PlanContext ctx) {
        return new ProviderPlan(false, "UNSUPPORTED_LECTERN_CYCLE", List.of());
    }

    public ProviderPlan planCureVillager(PlanContext ctx) {
        return new ProviderPlan(false, "UNSUPPORTED_CURE_VILLAGER", List.of());
    }
}
