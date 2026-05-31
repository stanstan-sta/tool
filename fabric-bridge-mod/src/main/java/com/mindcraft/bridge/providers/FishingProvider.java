package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FishingProvider implements ItemProvider {
    private static final Set<String> FISH_ITEMS = Set.of(
        "minecraft:cod", "minecraft:cooked_cod", "minecraft:salmon", "minecraft:cooked_salmon",
        "minecraft:pufferfish", "minecraft:tropical_fish", "minecraft:ink_sac",
        "minecraft:nautilus_shell", "minecraft:bow", "minecraft:fishing_rod", "minecraft:enchanted_book",
        "minecraft:name_tag", "minecraft:saddle", "minecraft:lily_pad", "minecraft:leather_boots"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return FISH_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        List<PlanStep> steps = new ArrayList<>();

        // Check for fishing rod in inventory
        int hasRod = ctx.inventory().getOrDefault("minecraft:fishing_rod", 0);
        if (hasRod <= 0) {
            boolean resolved = false;
            for (ItemProvider p : CommandExecutor.allProviders()) {
                if (p == this) continue;
                if (p.canProvide("minecraft:fishing_rod", ctx)) {
                    ProviderPlan sub = p.plan("minecraft:fishing_rod", 1, ctx);
                    if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                }
            }
            if (!resolved) return new ProviderPlan(false, "NO_FISHING_ROD", steps);
        }

        steps.add(new PlanStep("fish", "{\"count\":" + count + "}"));
        return new ProviderPlan(true, null, steps);
    }
}
