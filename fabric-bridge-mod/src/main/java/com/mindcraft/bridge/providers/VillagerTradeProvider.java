package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class VillagerTradeProvider implements ItemProvider {
    private static final Set<String> TRADE_ITEMS = Set.of(
        "minecraft:emerald", "minecraft:emerald_block",
        "minecraft:compass", "minecraft:clock",
        "minecraft:book", "minecraft:bookshelf",
        "minecraft:glass", "minecraft:glass_pane",
        "minecraft:chainmail_helmet", "minecraft:chainmail_chestplate",
        "minecraft:chainmail_leggings", "minecraft:chainmail_boots",
        "minecraft:bell", "minecraft:campfire",
        "minecraft:lantern", "minecraft:soul_lantern"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return TRADE_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        // Most villager trades cost emeralds — check if player has them
        int hasEmeralds = ctx.inventory().getOrDefault("minecraft:emerald", 0);
        if (hasEmeralds < count) {
            int missing = count - hasEmeralds;
            boolean resolved = false;
            for (ItemProvider p : CommandExecutor.allProviders()) {
                if (p == this) continue;
                if (p.canProvide("minecraft:emerald", ctx)) {
                    ProviderPlan sub = p.plan("minecraft:emerald", missing, ctx);
                    if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                }
            }
            if (!resolved) return new ProviderPlan(false, "NO_EMERALDS", steps);
        }

        steps.add(new PlanStep("trade", "{\"item\":\"" + id + "\",\"count\":" + count + "}"));
        return new ProviderPlan(true, null, steps);
    }
}
