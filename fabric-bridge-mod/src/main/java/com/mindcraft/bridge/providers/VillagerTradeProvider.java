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

        // VillagerTradeProvider cannot reliably auto-select offers or find
        // the right villager. This is a placeholder until Phase 3 work.
        return new ProviderPlan(false, "UNSUPPORTED_VILLAGER_TRADE", steps);
    }
}
