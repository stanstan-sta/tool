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

                    // Acquire fuel using proper fuel capacity calculation
                    int bestCap = CommandExecutor.fuelCapacityItems("minecraft:coal_block");
                    String fuel = "minecraft:coal_block";
                    for (String candidate : new String[]{"minecraft:coal_block", "minecraft:coal", "minecraft:charcoal"}) {
                        int cap = CommandExecutor.fuelCapacityItems(candidate);
                        if (cap > bestCap) { bestCap = cap; fuel = candidate; }
                    }
                    int fuelNeeded = Math.max(1, (int) Math.ceil((double) count / bestCap));
                    boolean fuelResolved = false;
                    for (ItemProvider p : CommandExecutor.allProviders()) {
                        if (p instanceof SmeltingProvider) continue;
                        if (p.canProvide(fuel, ctx)) {
                            ProviderPlan sub = p.plan(fuel, fuelNeeded, ctx);
                            if (sub.ok()) {
                                steps.addAll(sub.steps());
                                fuelResolved = true;
                                break;
                            }
                        }
                    }
                    if (!fuelResolved) {
                        fuel = "minecraft:charcoal";
                        for (ItemProvider p : CommandExecutor.allProviders()) {
                            if (p instanceof SmeltingProvider) continue;
                            if (p.canProvide(fuel, ctx)) {
                                ProviderPlan sub = p.plan(fuel, fuelNeeded, ctx);
                                if (sub.ok()) {
                                    steps.addAll(sub.steps());
                                    fuelResolved = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!fuelResolved) return new ProviderPlan(false, "NO_FUEL", steps);

                    steps.add(new PlanStep("raw_command", "{\"command\":\"#task smelt " + ItemIds.strip(input) + " " + count + "\"}"));
                    return new ProviderPlan(true, null, steps);
                } finally {
                    inProgress.remove(normalizedId);
                }
            }
        }
        return new ProviderPlan(false, "NO_RECIPE", List.of());
    }
}
