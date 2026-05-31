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
        }

        return new ProviderPlan(true, null, steps);
    }

    public ProviderPlan planEndTravel(PlanContext ctx) {
        List<PlanStep> steps = new ArrayList<>();

        int hasEyes = ctx.inventory().getOrDefault("minecraft:eye_of_ender", 0);
        if (hasEyes < 12) {
            int missing = 12 - hasEyes;
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:eye_of_ender\",\"count\":" + missing + "}"));
        }

        steps.add(new PlanStep("locate_stronghold", "{}"));
        steps.add(new PlanStep("fill_portal", "{}"));
        steps.add(new PlanStep("portal_travel", "{\"dimension\":\"the_end\"}"));

        return new ProviderPlan(true, null, steps);
    }

    public ProviderPlan planNetherTravel(PlanContext ctx) {
        List<PlanStep> steps = new ArrayList<>();

        boolean hasFlintAndSteel = ctx.inventory().containsKey("minecraft:flint_and_steel");
        if (!hasFlintAndSteel) {
            steps.add(new PlanStep("craft", "{\"item\":\"minecraft:flint_and_steel\",\"count\":1}"));
        }

        steps.add(new PlanStep("find_portal", "{\"dimension\":\"the_nether\"}"));
        steps.add(new PlanStep("light_portal", "{}"));
        steps.add(new PlanStep("portal_travel", "{\"dimension\":\"the_nether\"}"));

        return new ProviderPlan(true, null, steps);
    }
}
