package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;

public class SmithingProvider implements ItemProvider {
    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String key = ItemIds.strip(itemId);
        return CommandExecutor.SMITHING_RECIPES.containsKey(key);
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String key = ItemIds.strip(itemId);
        CommandExecutor.SmithingRecipe recipe = CommandExecutor.SMITHING_RECIPES.get(key);
        if (recipe == null) return new ProviderPlan(false, "NO_SMITHING_RECIPE", List.of());

        List<PlanStep> steps = new ArrayList<>();

        // Resolve template
        String template = ItemIds.normalize(recipe.template());
        int hasTemplate = ctx.inventory().getOrDefault(template, 0);
        if (hasTemplate <= 0) {
            boolean resolved = false;
            for (ItemProvider p : CommandExecutor.allProviders()) {
                if (p == this) continue;
                if (p.canProvide(template, ctx)) {
                    ProviderPlan sub = p.plan(template, 1, ctx);
                    if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                }
            }
            if (!resolved) return new ProviderPlan(false, "NO_TEMPLATE_" + ItemIds.strip(template), steps);
        }

        // Resolve base
        String base = ItemIds.normalize(recipe.base());
        int hasBase = ctx.inventory().getOrDefault(base, 0);
        if (hasBase <= 0) {
            boolean resolved = false;
            for (ItemProvider p : CommandExecutor.allProviders()) {
                if (p == this) continue;
                if (p.canProvide(base, ctx)) {
                    ProviderPlan sub = p.plan(base, 1, ctx);
                    if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                }
            }
            if (!resolved) return new ProviderPlan(false, "NO_BASE_" + ItemIds.strip(base), steps);
        }

        // Resolve addition
        String addition = ItemIds.normalize(recipe.addition());
        int hasAddition = ctx.inventory().getOrDefault(addition, 0);
        if (hasAddition <= 0) {
            boolean resolved = false;
            for (ItemProvider p : CommandExecutor.allProviders()) {
                if (p == this) continue;
                if (p.canProvide(addition, ctx)) {
                    ProviderPlan sub = p.plan(addition, 1, ctx);
                    if (sub.ok()) { steps.addAll(sub.steps()); resolved = true; break; }
                }
            }
            if (!resolved) return new ProviderPlan(false, "NO_ADDITION_" + ItemIds.strip(addition), steps);
        }

        String actionJson = "{\"template\":\"" + ItemIds.strip(recipe.template())
            + "\",\"base\":\"" + ItemIds.strip(recipe.base())
            + "\",\"addition\":\"" + ItemIds.strip(recipe.addition())
            + "\",\"output\":\"" + ItemIds.strip(recipe.output()) + "\"}";
        steps.add(new PlanStep("smith", actionJson));
        return new ProviderPlan(true, null, steps);
    }
}
