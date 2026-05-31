package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class HusbandryProvider implements ItemProvider {
    private static final Set<String> SHEAR_ITEMS = Set.of(
        "minecraft:white_wool", "minecraft:orange_wool", "minecraft:magenta_wool",
        "minecraft:light_blue_wool", "minecraft:yellow_wool", "minecraft:lime_wool",
        "minecraft:pink_wool", "minecraft:gray_wool", "minecraft:light_gray_wool",
        "minecraft:cyan_wool", "minecraft:purple_wool", "minecraft:blue_wool",
        "minecraft:brown_wool", "minecraft:green_wool", "minecraft:red_wool",
        "minecraft:black_wool", "minecraft:string", "minecraft:red_mushroom"
    );

    private static final Set<String> MILK_ITEMS = Set.of(
        "minecraft:milk_bucket"
    );

    private static final Set<String> HONEY_ITEMS = Set.of(
        "minecraft:honey_bottle", "minecraft:honeycomb"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        return SHEAR_ITEMS.contains(id) || MILK_ITEMS.contains(id) || HONEY_ITEMS.contains(id);
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        if (SHEAR_ITEMS.contains(id)) {
            boolean hasShears = ctx.inventory().containsKey("minecraft:shears");
            if (!hasShears) {
                boolean resolved = false;
                for (ItemProvider p : CommandExecutor.allProviders()) {
                    if (p == this) continue;
                    if (p.canProvide("minecraft:shears", ctx)) {
                        ProviderPlan sub = p.plan("minecraft:shears", 1, ctx);
                        if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                    }
                }
                if (!resolved) return new ProviderPlan(false, "NO_SHEARS", steps);
            }
            steps.add(new PlanStep("shear", "{\"target\":\"sheep\",\"count\":" + count + "}"));
            return new ProviderPlan(true, null, steps);
        }

        if (MILK_ITEMS.contains(id)) {
            boolean hasBucket = ctx.inventory().containsKey("minecraft:bucket");
            if (!hasBucket) {
                boolean resolved = false;
                for (ItemProvider p : CommandExecutor.allProviders()) {
                    if (p == this) continue;
                    if (p.canProvide("minecraft:bucket", ctx)) {
                        ProviderPlan sub = p.plan("minecraft:bucket", 1, ctx);
                        if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                    }
                }
                if (!resolved) return new ProviderPlan(false, "NO_BUCKET", steps);
            }
            steps.add(new PlanStep("milk", "{\"target\":\"cow\",\"count\":" + count + "}"));
            return new ProviderPlan(true, null, steps);
        }

        if (HONEY_ITEMS.contains(id)) {
            steps.add(new PlanStep("shear", "{\"target\":\"beehive\",\"count\":" + count + "}"));
            return new ProviderPlan(true, null, steps);
        }

        return new ProviderPlan(false, "UNSUPPORTED_HUSBANDRY_ITEM", steps);
    }
}
