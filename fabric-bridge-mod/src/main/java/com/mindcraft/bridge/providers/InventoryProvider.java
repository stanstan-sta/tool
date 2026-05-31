package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.List;
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
            return new ProviderPlan(true, null, List.of(
                new PlanStep("noop", "{\"item\":\"" + itemId + "\",\"count\":" + count + "}")
            ));
        }
        return new ProviderPlan(false, "MISSING_ITEM", List.of());
    }
}
