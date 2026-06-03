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
        PlanLedger child = ctx.ledger().fork();
        int missing = child.consume(itemId, count);
        if (missing <= 0) {
            ctx.ledger().commitFrom(child);
            return new ProviderPlan(true, null, List.of());
        }
        String id = ItemIds.normalize(itemId);
        CommandExecutor.GatherProvider gp = CommandExecutor.GATHER_PROVIDERS.get(id);
        if (gp == null) return new ProviderPlan(false, "UNSUPPORTED_ITEM", List.of());

        String target = gp.mineTarget();
        String dim = DimensionDriver.normalizeDimension(ctx.currentPlannedDimension());
        List<PlanStep> steps = new ArrayList<>();

        // Portal travel if target requires Nether
        boolean isNetherTarget = CommandExecutor.NETHER_GATHER_TARGETS.contains(target);
        if (isNetherTarget && !dim.contains("nether")) {
            if (!ctx.allowTravel()) return new ProviderPlan(false, "TRAVEL_DISABLED", steps);
            steps.add(new PlanStep("portal_travel", "{\"dimension\":\"minecraft:the_nether\"}"));
            dim = "minecraft:the_nether";
        }

        // Tool-tier check
        String requiredTool = CommandExecutor.MINE_TOOL_REQUIREMENTS.get(target);
        if (requiredTool != null) {
            boolean hasTool = child.hasToolOrReserved(requiredTool);
            if (!hasTool) {
                boolean crafted = false;
                for (ItemProvider p : CommandExecutor.allProviders()) {
                    if (p instanceof MiningProvider) continue;
                    PlanLedger fork = child.fork();
                    PlanContext forkCtx = ctx.withLedger(fork).withPlannedDimension(dim);
                    if (p.canProvide(requiredTool, forkCtx)) {
                        ProviderPlan sub = p.plan(requiredTool, 1, forkCtx);
                        if (sub.ok()) {
                            child.commitFrom(fork);
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
                            PlanLedger fork = child.fork();
                            PlanContext forkCtx = ctx.withLedger(fork).withPlannedDimension(dim);
                            if (p.canProvide(lowerTier, forkCtx)) {
                                ProviderPlan sub = p.plan(lowerTier, 1, forkCtx);
                                if (sub.ok()) {
                                    child.commitFrom(fork);
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

        steps.add(new PlanStep("mine", "{\"target\":\"" + target + "\",\"count\":" + missing + "}"));
        child.produce(itemId, missing);
        child.consume(itemId, missing);

        // Portal return
        String origin = DimensionDriver.normalizeDimension(ctx.originDimension());
        if (ctx.returnPolicy() == PlanContext.ReturnPolicy.RETURN_TO_ORIGIN
                && !origin.isBlank() && !origin.equals(dim)) {
            String payload = origin.equals("minecraft:overworld") ? "{}" : "{\"dimension\":\"" + origin + "\"}";
            steps.add(new PlanStep(origin.equals("minecraft:overworld") ? "return_to_overworld" : "portal_travel", payload));
        } else if (ctx.returnPolicy() == PlanContext.ReturnPolicy.RETURN_TO_OVERWORLD
                && !"minecraft:overworld".equals(dim)) {
            steps.add(new PlanStep("return_to_overworld", "{}"));
        }

        ctx.ledger().commitFrom(child);
        return new ProviderPlan(true, null, steps);
    }
}
