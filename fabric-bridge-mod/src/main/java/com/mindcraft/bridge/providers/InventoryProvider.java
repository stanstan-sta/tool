package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.Map;

public class InventoryProvider implements ItemProvider {
    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return ctx.inventory().containsKey(itemId) && ctx.inventory().get(itemId) > 0;
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        int available = ctx.inventory().getOrDefault(itemId, 0);
        if (available >= count) {
            return new ProviderPlan(true, null, java.util.List.of());
        }
        return new ProviderPlan(false, "MISSING_ITEM", java.util.List.of());
    }
}
