package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.*;

public class CraftingProvider implements ItemProvider {
    private static final int MAX_TRANSMUTATION_DEPTH = 5;
    private static final ThreadLocal<Set<String>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String key = ItemIds.strip(itemId);
        if (CommandExecutor.RECIPE_DATABASE.containsKey(key)) return true;
        for (CommandExecutor.SmeltRecipe recipe : CommandExecutor.SMELT_RECIPES.values()) {
            if (ItemIds.strip(itemId).equals(ItemIds.strip(recipe.output()))) return true;
        }
        if (CommandExecutor.GATHER_PROVIDERS.containsKey(ItemIds.normalize(itemId))) return true;
        return false;
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        return planInternal(itemId, count, ctx, 0);
    }

    private ProviderPlan planInternal(String itemId, int count, PlanContext ctx, int depth) {
        if (depth > MAX_TRANSMUTATION_DEPTH) {
            return new ProviderPlan(false, "MAX_DEPTH_EXCEEDED", List.of());
        }

        String normalizedId = ItemIds.normalize(itemId);
        Set<String> inProgress = IN_PROGRESS.get();
        if (inProgress.contains(normalizedId)) {
            return new ProviderPlan(false, "CYCLE_DETECTED_" + normalizedId, List.of());
        }

        PlanLedger child = ctx.ledger().fork();
        int missingOutput = child.consume(itemId, count);
        if (missingOutput <= 0) {
            ctx.ledger().commitFrom(child);
            return new ProviderPlan(true, null, List.of());
        }
        PlanContext childCtx = ctx.withLedger(child);

        String key = ItemIds.strip(itemId);
        CommandExecutor.RecipeData recipe = CommandExecutor.RECIPE_DATABASE.get(key);
        if (recipe == null) return new ProviderPlan(false, "NO_RECIPE", List.of());

        inProgress.add(normalizedId);
        try {
            int batches = (missingOutput + recipe.outputCount - 1) / recipe.outputCount;
            Map<String, Integer> neededIngredients = new HashMap<>();

            for (CommandExecutor.GridSlot slot : recipe.slots) {
                if (slot.ingredientPatterns.isEmpty()) continue;
                String ingredient = resolveAmbiguousPattern(slot.ingredientPatterns, childCtx);
                neededIngredients.merge(ingredient, batches, Integer::sum);
            }

            List<PlanStep> steps = new ArrayList<>();

            boolean needsCraftingTable = false;
            for (CommandExecutor.GridSlot slot : recipe.slots) {
                if (slot.gridIndex > 4) { needsCraftingTable = true; break; }
            }
            if (needsCraftingTable) {
                int hasTable = child.available("minecraft:crafting_table");
                if (hasTable <= 0) {
                    steps.add(new PlanStep("craft", "{\"item\":\"minecraft:crafting_table\",\"count\":1}"));
                    child.produce("minecraft:crafting_table", 1);
                }
            }

            for (Map.Entry<String, Integer> entry : neededIngredients.entrySet()) {
                String ing = entry.getKey();
                int needed = entry.getValue();
                int missing = child.consume(ing, needed);
                if (missing <= 0) continue;

                CommandExecutor.GatherProvider gp = CommandExecutor.GATHER_PROVIDERS.get(ItemIds.normalize(ing));
                if (gp != null) {
                    String requiredTool = CommandExecutor.MINE_TOOL_REQUIREMENTS.get(gp.mineTarget());
                    if (requiredTool != null && !child.hasToolOrReserved(requiredTool)) {
                        int toolIdx = CommandExecutor.PICKAXE_TIERS.indexOf(ItemIds.normalize(requiredTool));
                        boolean craftedTool = false;
                        for (int i = Math.max(0, toolIdx); i < CommandExecutor.PICKAXE_TIERS.size(); i++) {
                            String candidate = CommandExecutor.PICKAXE_TIERS.get(i);
                            if (child.hasToolOrReserved(candidate)) { craftedTool = true; break; }
                            if (CommandExecutor.RECIPE_DATABASE.containsKey(ItemIds.strip(candidate))) {
                                steps.add(new PlanStep("craft", "{\"item\":\"" + candidate + "\",\"count\":1}"));
                                child.produce(candidate, 1);
                                child.reserveTool(candidate);
                                craftedTool = true;
                                break;
                            }
                        }
                        if (!craftedTool) {
                            return new ProviderPlan(false, "NO_TOOL_" + requiredTool, steps);
                        }
                    }
                }

                boolean resolved = false;
                for (ItemProvider p : CommandExecutor.allProviders()) {
                    if (p == this) continue;
                    PlanLedger fork = child.fork();
                    PlanContext forkCtx = childCtx.withLedger(fork);
                    if (p.canProvide(ing, forkCtx)) {
                        ProviderPlan sub = p.plan(ing, missing, forkCtx);
                        if (sub.ok()) {
                            child.commitFrom(fork);
                            steps.addAll(sub.steps());
                            resolved = true;
                            break;
                        }
                    }
                }

                if (!resolved && CommandExecutor.RECIPE_DATABASE.containsKey(ItemIds.strip(ing))) {
                    PlanLedger fork = child.fork();
                    ProviderPlan sub = planInternal(ing, missing, childCtx.withLedger(fork), depth + 1);
                    if (sub.ok()) {
                        child.commitFrom(fork);
                        steps.addAll(sub.steps());
                        resolved = true;
                    }
                }

                if (!resolved) {
                    return new ProviderPlan(false, "UNSATISFIABLE_INGREDIENT_" + ItemIds.strip(ing), steps);
                }
            }

            String craftJson = "{\"item\":\"" + itemId + "\",\"count\":" + missingOutput + "}";
            steps.add(new PlanStep("craft", craftJson));
            child.produce(itemId, batches * recipe.outputCount);
            child.consume(itemId, missingOutput);
            ctx.ledger().commitFrom(child);
            return new ProviderPlan(true, null, steps);
        } finally {
            inProgress.remove(normalizedId);
        }
    }

    private String resolveAmbiguousPattern(List<String> patterns, PlanContext ctx) {
        if (patterns.size() == 1) {
            return normalizePattern(patterns.get(0), ctx);
        }

        String bestMatch = null;
        int bestCount = -1;

        for (String pattern : patterns) {
            String normalized = normalizePattern(pattern, ctx);
            int count = ctx.inventory().getOrDefault(normalized, 0);
            if (count > bestCount) {
                bestCount = count;
                bestMatch = normalized;
            }
        }

        return bestMatch != null ? bestMatch : normalizePattern(patterns.get(0), ctx);
    }

    private String normalizePattern(String pattern, PlanContext ctx) {
        if (pattern.startsWith("minecraft:")) {
            return pattern;
        }
        if (pattern.contains("*")) {
            String suffix = pattern.substring(pattern.indexOf('*') + 1);
            for (String item : ctx.inventory().keySet()) {
                if (item.endsWith(suffix)) {
                    return item;
                }
            }
            return pattern.replace("*", "oak");
        }
        return ItemIds.normalize(pattern);
    }
}
