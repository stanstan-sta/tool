package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.Map;

public class InventoryProvider implements ItemProvider {
    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return ctx.ledger().available(itemId) > 0;
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        PlanLedger child = ctx.ledger().fork();
        int missing = child.consume(itemId, count);
        if (missing <= 0) {
            ctx.ledger().commitFrom(child);
            return new ProviderPlan(true, null, java.util.List.of());
        }
        return new ProviderPlan(false, "MISSING_ITEM", java.util.List.of());
    }
}
