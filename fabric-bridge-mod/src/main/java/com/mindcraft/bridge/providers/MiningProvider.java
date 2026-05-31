package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;

public class MiningProvider implements ItemProvider {
    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        return CommandExecutor.GATHER_PROVIDERS.containsKey(id);
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        CommandExecutor.GatherProvider gp = CommandExecutor.GATHER_PROVIDERS.get(id);
        if (gp == null) return new ProviderPlan(false, "UNSUPPORTED_ITEM", List.of());

        String target = gp.mineTarget();
        String dim = ctx.dimension();
        List<PlanStep> steps = new ArrayList<>();

        // Portal travel if target requires Nether
        boolean isNetherTarget = CommandExecutor.NETHER_GATHER_TARGETS.contains(target);
        if (isNetherTarget && !dim.contains("nether")) {
            steps.add(new PlanStep("raw_command", "{\"command\":\"#portal_travel minecraft:the_nether\"}"));
        }

        // Tool-tier check
        String requiredTool = CommandExecutor.MINE_TOOL_REQUIREMENTS.get(target);
        if (requiredTool != null) {
            boolean hasTool = ctx.inventory().containsKey(ItemIds.normalize(requiredTool));
            if (!hasTool) {
                boolean crafted = false;
                for (ItemProvider p : CommandExecutor.allProviders()) {
                    if (p instanceof MiningProvider) continue;
                    if (p.canProvide(requiredTool, ctx)) {
                        ProviderPlan sub = p.plan(requiredTool, 1, ctx);
                        if (sub.ok()) {
                            steps.addAll(sub.steps());
                            crafted = true;
                            break;
                        }
                    }
                }
                if (!crafted) {
                    for (int i = CommandExecutor.PICKAXE_TIERS.indexOf(requiredTool) - 1; i >= 0; i--) {
                        String lowerTier = CommandExecutor.PICKAXE_TIERS.get(i);
                        for (ItemProvider p : CommandExecutor.allProviders()) {
                            if (p instanceof MiningProvider) continue;
                            if (p.canProvide(lowerTier, ctx)) {
                                ProviderPlan sub = p.plan(lowerTier, 1, ctx);
                                if (sub.ok()) {
                                    steps.addAll(sub.steps());
                                    crafted = true;
                                    break;
                                }
                            }
                        }
                        if (crafted) break;
                    }
                }
                if (!crafted) return new ProviderPlan(false, "NO_TOOL_" + requiredTool, steps);
            }
        }

        steps.add(new PlanStep("mine", "{\"target\":\"" + target + "\",\"count\":" + count + "}"));

        // Portal return
        if (isNetherTarget && !dim.contains("nether")) {
            steps.add(new PlanStep("raw_command", "{\"command\":\"#return_to_overworld\"}"));
        }

        return new ProviderPlan(true, null, steps);
    }
}
