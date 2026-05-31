package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FluidCollectionProvider implements ItemProvider {
    private static final int MAX_DEPTH = 5;
    private static final ThreadLocal<Set<String>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);

    private static final Set<String> FLUID_ITEMS = Set.of(
        "minecraft:water_bucket", "minecraft:lava_bucket",
        "minecraft:milk_bucket", "minecraft:bucket",
        "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
        "minecraft:snowball", "minecraft:snow_block"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return FLUID_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        return planInternal(itemId, count, ctx, 0);
    }

    private ProviderPlan planInternal(String itemId, int count, PlanContext ctx, int depth) {
        if (depth > MAX_DEPTH) {
            return new ProviderPlan(false, "MAX_DEPTH_EXCEEDED", List.of());
        }

        String normalizedId = ItemIds.normalize(itemId);
        Set<String> inProgress = IN_PROGRESS.get();
        if (inProgress.contains(normalizedId)) {
            return new ProviderPlan(false, "CYCLE_DETECTED_" + normalizedId, List.of());
        }

        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        inProgress.add(normalizedId);
        try {
            // Check for bucket in inventory (needed for water/lava collection)
            if (id.contains("bucket")) {
                int hasBucket = ctx.inventory().getOrDefault("minecraft:bucket", 0);
                if (hasBucket <= 0) {
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
            }

            steps.add(new PlanStep("collect_fluid", "{\"target\":\"" + id + "\",\"count\":" + count + "}"));
            return new ProviderPlan(true, null, steps);
        } finally {
            inProgress.remove(normalizedId);
        }
    }
}
