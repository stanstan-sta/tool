package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DimensionalTravelProvider implements ItemProvider {
    private static final Set<String> TRAVEL_ITEMS = Set.of(
        "minecraft:end_portal_frame",
        "minecraft:eye_of_ender",
        "minecraft:flint_and_steel",
        "minecraft:nether_portal"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return TRAVEL_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        if (id.equals("minecraft:eye_of_ender")) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:eye_of_ender\",\"count\":" + count + "}"));
        } else if (id.equals("minecraft:flint_and_steel")) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:flint_and_steel\",\"count\":1}"));
        } else if (id.equals("minecraft:end_portal_frame")) {
            return new ProviderPlan(false, "UNOBTAINABLE", List.of());
        } else if (id.equals("minecraft:nether_portal")) {
            return new ProviderPlan(false, "UNSUPPORTED_NETHER_PORTAL", List.of());
        }

        return new ProviderPlan(true, null, steps);
    }

    public ProviderPlan planEndTravel(PlanContext ctx) {
        return new ProviderPlan(false, "UNSUPPORTED_END_TRAVEL", List.of());
    }

    public ProviderPlan planNetherTravel(PlanContext ctx) {
        return new ProviderPlan(false, "UNSUPPORTED_NETHER_TRAVEL", List.of());
    }
}
