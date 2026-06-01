package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SmeltingProvider implements ItemProvider {
    private static final int MAX_DEPTH = 5;
    private static final ThreadLocal<Set<String>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        for (CommandExecutor.SmeltRecipe recipe : CommandExecutor.SMELT_RECIPES.values()) {
            if (id.equals(recipe.output())) return true;
        }
        return false;
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
        for (CommandExecutor.SmeltRecipe recipe : CommandExecutor.SMELT_RECIPES.values()) {
            if (id.equals(recipe.output())) {
                inProgress.add(normalizedId);
                try {
                    String input = recipe.input();
                    List<PlanStep> steps = new ArrayList<>();

                    // Furnace detection: ensure a furnace is available
                    int hasFurnace = ctx.inventory().getOrDefault("minecraft:furnace", 0);
                    if (hasFurnace <= 0) {
                        // Craft a furnace (8 cobblestone)
                        steps.add(new PlanStep("craft", "{\"item\":\"minecraft:furnace\",\"count\":1}"));
                    }

                    // Acquire input material
                    boolean inputResolved = false;
                    for (ItemProvider p : CommandExecutor.allProviders()) {
                        if (p instanceof SmeltingProvider) continue;
                        if (p.canProvide(input, ctx)) {
                            ProviderPlan sub = p.plan(input, count, ctx);
                            if (sub.ok()) {
                                steps.addAll(sub.steps());
                                inputResolved = true;
                                break;
                            }
                        }
                    }
                    if (!inputResolved) return new ProviderPlan(false, "NO_INPUT_" + ItemIds.strip(input), steps);

                    int missingFuelCapacity = missingFuelCapacity(ctx, count, input);
                    if (missingFuelCapacity > 0) {
                        int coalNeeded = Math.max(1, (int) Math.ceil(missingFuelCapacity / 8.0));
                        ProviderPlan fuelPlan = new MiningProvider().plan("minecraft:coal", coalNeeded, ctx);
                        if (!fuelPlan.ok()) return new ProviderPlan(false, "NO_FUEL", steps);
                        steps.addAll(fuelPlan.steps());
                    }

                    steps.add(new PlanStep("raw_command", "{\"command\":\"#task smelt " + ItemIds.strip(input) + " " + count + "\"}"));
                    return new ProviderPlan(true, null, steps);
                } finally {
                    inProgress.remove(normalizedId);
                }
            }
        }
        return new ProviderPlan(false, "NO_RECIPE", List.of());
    }

    private static int missingFuelCapacity(PlanContext ctx, int smeltCount, String avoidItemId) {
        int availableCapacity = 0;
        String avoid = ItemIds.normalize(avoidItemId);
        for (var entry : ctx.inventory().entrySet()) {
            String item = ItemIds.normalize(entry.getKey());
            if (item.equals(avoid)) continue;
            availableCapacity += entry.getValue() * CommandExecutor.fuelCapacityItems(item);
        }
        return Math.max(0, smeltCount - availableCapacity);
    }
}
