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
                PlanLedger child = ctx.ledger().fork();
                int missingOutput = child.consume(itemId, count);
                if (missingOutput <= 0) {
                    ctx.ledger().commitFrom(child);
                    return new ProviderPlan(true, null, List.of());
                }
                PlanContext childCtx = ctx.withLedger(child);
                inProgress.add(normalizedId);
                try {
                    String input = recipe.input();
                    List<PlanStep> steps = new ArrayList<>();

                    // Furnace detection: ensure a furnace is available
                    int hasFurnace = child.available("minecraft:furnace");
                    if (hasFurnace <= 0) {
                        // Craft a furnace (8 cobblestone)
                        steps.add(new PlanStep("craft", "{\"item\":\"minecraft:furnace\",\"count\":1}"));
                        child.produce("minecraft:furnace", 1);
                    }

                    // Acquire input material
                    int missingInput = child.consume(input, missingOutput);
                    boolean inputResolved = false;
                    if (missingInput <= 0) {
                        inputResolved = true;
                    } else {
                        for (ItemProvider p : CommandExecutor.allProviders()) {
                            if (p instanceof SmeltingProvider) continue;
                            PlanLedger fork = child.fork();
                            PlanContext forkCtx = childCtx.withLedger(fork);
                            if (p.canProvide(input, forkCtx)) {
                                ProviderPlan sub = p.plan(input, missingInput, forkCtx);
                                if (sub.ok()) {
                                    child.commitFrom(fork);
                                    steps.addAll(sub.steps());
                                    inputResolved = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!inputResolved) return new ProviderPlan(false, "NO_INPUT_" + ItemIds.strip(input), steps);

                    int missingFuelCapacity = reserveFuelCapacity(child, missingOutput, input);
                    if (missingFuelCapacity > 0) {
                        int coalNeeded = Math.max(1, (int) Math.ceil(missingFuelCapacity / 8.0));
                        ProviderPlan fuelPlan = new MiningProvider().plan("minecraft:coal", coalNeeded, childCtx);
                        if (!fuelPlan.ok()) return new ProviderPlan(false, "NO_FUEL", steps);
                        steps.addAll(fuelPlan.steps());
                    }

                    steps.add(new PlanStep("raw_command", "{\"command\":\"#task smelt " + ItemIds.strip(input) + " " + missingOutput + "\"}"));
                    child.produce(itemId, missingOutput);
                    child.consume(itemId, missingOutput);
                    ctx.ledger().commitFrom(child);
                    return new ProviderPlan(true, null, steps);
                } finally {
                    inProgress.remove(normalizedId);
                }
            }
        }
        return new ProviderPlan(false, "NO_RECIPE", List.of());
    }

    private static int reserveFuelCapacity(PlanLedger ledger, int smeltCount, String avoidItemId) {
        int remainingCapacity = Math.max(0, smeltCount);
        String avoid = ItemIds.normalize(avoidItemId);
        List<String> fuels = new ArrayList<>(ledger.snapshot().keySet());
        fuels.sort((a, b) -> {
            int capCompare = Integer.compare(CommandExecutor.fuelCapacityItems(b), CommandExecutor.fuelCapacityItems(a));
            return capCompare != 0 ? capCompare : a.compareTo(b);
        });

        for (String fuel : fuels) {
            if (remainingCapacity <= 0) return 0;
            String item = ItemIds.normalize(fuel);
            if (item.equals(avoid)) continue;
            int capacity = CommandExecutor.fuelCapacityItems(item);
            if (capacity <= 0) continue;
            int available = ledger.available(item);
            int needed = Math.min(available, (int) Math.ceil(remainingCapacity / (double) capacity));
            if (needed <= 0) continue;
            ledger.consume(item, needed);
            remainingCapacity -= needed * capacity;
        }
        return Math.max(0, remainingCapacity);
    }
}
