package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FarmingProvider implements ItemProvider {
    private static final Set<String> CROP_ITEMS = Set.of(
        "minecraft:wheat", "minecraft:wheat_seeds", "minecraft:carrots", "minecraft:carrot",
        "minecraft:potatoes", "minecraft:potato", "minecraft:beetroot", "minecraft:beetroot_seeds",
        "minecraft:pumpkin", "minecraft:melon_slice", "minecraft:melon", "minecraft:sugar_cane",
        "minecraft:bamboo", "minecraft:cactus", "minecraft:sweet_berries", "minecraft:glow_berries",
        "minecraft:nether_wart", "minecraft:cocoa_beans", "minecraft:kelp", "minecraft:apple",
        "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:hay_block",
        "minecraft:melon_block", "minecraft:pumpkin_pie", "minecraft:bread",
        "minecraft:cookie", "minecraft:cake"
    );

    private static final Map<String, String> SEED_REQUIREMENTS = Map.of(
        "minecraft:wheat", "minecraft:wheat_seeds",
        "minecraft:carrots", "minecraft:carrot",
        "minecraft:potatoes", "minecraft:potato",
        "minecraft:beetroot", "minecraft:beetroot_seeds",
        "minecraft:pumpkin", "minecraft:pumpkin_seeds",
        "minecraft:melon", "minecraft:melon_seeds",
        "minecraft:nether_wart", "minecraft:nether_wart"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return CROP_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        // Check if seeds/starting item is needed
        String seedItem = SEED_REQUIREMENTS.get(id);
        if (seedItem != null) {
            int hasSeeds = ctx.inventory().getOrDefault(seedItem, 0);
            if (hasSeeds <= 0) {
                boolean resolved = false;
                for (ItemProvider p : CommandExecutor.allProviders()) {
                    if (p == this) continue;
                    if (p.canProvide(seedItem, ctx)) {
                        ProviderPlan sub = p.plan(seedItem, 1, ctx);
                        if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                    }
                }
                if (!resolved) return new ProviderPlan(false, "NO_SEEDS_" + ItemIds.strip(seedItem), steps);
            }
        }

        steps.add(new PlanStep("farm", "{\"crop\":\"" + id + "\",\"count\":" + count + "}"));
        return new ProviderPlan(true, null, steps);
    }
}
