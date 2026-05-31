package com.mindcraft.bridge;

import java.util.List;

public interface ItemProvider {
    boolean canProvide(String itemId, PlanContext ctx);
    ProviderPlan plan(String itemId, int count, PlanContext ctx);
}
