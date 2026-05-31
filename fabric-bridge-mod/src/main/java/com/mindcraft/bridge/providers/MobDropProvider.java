package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MobDropProvider implements ItemProvider {
    private static final Map<String, String> MOB_DROPS = Map.ofEntries(
        Map.entry("minecraft:bone", "skeleton"),
        Map.entry("minecraft:string", "spider"),
        Map.entry("minecraft:spider_eye", "spider"),
        Map.entry("minecraft:gunpowder", "creeper"),
        Map.entry("minecraft:rotten_flesh", "zombie"),
        Map.entry("minecraft:ender_pearl", "enderman"),
        Map.entry("minecraft:blaze_rod", "blaze"),
        Map.entry("minecraft:ghast_tear", "ghast"),
        Map.entry("minecraft:slime_ball", "slime"),
        Map.entry("minecraft:magma_cream", "magma_cube"),
        Map.entry("minecraft:feather", "chicken"),
        Map.entry("minecraft:chicken", "chicken"),
        Map.entry("minecraft:cooked_chicken", "chicken"),
        Map.entry("minecraft:beef", "cow"),
        Map.entry("minecraft:cooked_beef", "cow"),
        Map.entry("minecraft:porkchop", "pig"),
        Map.entry("minecraft:cooked_porkchop", "pig"),
        Map.entry("minecraft:mutton", "sheep"),
        Map.entry("minecraft:cooked_mutton", "sheep"),
        Map.entry("minecraft:rabbit", "rabbit"),
        Map.entry("minecraft:rabbit_hide", "rabbit"),
        Map.entry("minecraft:rabbit_foot", "rabbit"),
        Map.entry("minecraft:leather", "cow"),
        Map.entry("minecraft:wool", "sheep"),
        Map.entry("minecraft:shulker_shell", "shulker"),
        Map.entry("minecraft:phantom_membrane", "phantom"),
        Map.entry("minecraft:scute", "turtle"),
        Map.entry("minecraft:honeycomb", "bee"),
        Map.entry("minecraft:blaze_powder", "blaze"),
        Map.entry("minecraft:bone_meal", "skeleton")
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return MOB_DROPS.containsKey(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        String mobName = MOB_DROPS.get(id);
        if (mobName == null) return new ProviderPlan(false, "UNSUPPORTED_MOB", List.of());

        List<PlanStep> steps = new ArrayList<>();

        // Check if we already have enough of this drop
        int hasDrop = ctx.inventory().getOrDefault(id, 0);
        if (hasDrop >= count) {
            return new ProviderPlan(true, null, List.of());
        }

        // Check for a weapon — hunting is easier with one
        boolean hasWeapon = ctx.inventory().containsKey("minecraft:wooden_sword")
            || ctx.inventory().containsKey("minecraft:stone_sword")
            || ctx.inventory().containsKey("minecraft:iron_sword")
            || ctx.inventory().containsKey("minecraft:diamond_sword");
        if (!hasWeapon) {
            // Try to get a sword through other providers
            boolean resolved = false;
            for (ItemProvider p : CommandExecutor.allProviders()) {
                if (p == this) continue;
                if (p.canProvide("minecraft:wooden_sword", ctx)) {
                    ProviderPlan sub = p.plan("minecraft:wooden_sword", 1, ctx);
                    if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                }
            }
            // Don't fail — we can still punch mobs, just less efficiently
        }

        steps.add(new PlanStep("hunt_mob", "{\"target_type\":\"" + mobName + "\",\"count\":" + count + "}"));
        return new ProviderPlan(true, null, steps);
    }
}
