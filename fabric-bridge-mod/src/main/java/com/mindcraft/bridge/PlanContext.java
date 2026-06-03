package com.mindcraft.bridge;

import java.util.Map;

public record PlanContext(
    Map<String, Integer> inventory,
    String dimension,
    boolean allowCombat,
    boolean allowTravel,
    PlanLedger ledger,
    String originDimension,
    String currentPlannedDimension,
    ReturnPolicy returnPolicy
) {
    public enum ReturnPolicy { NONE, RETURN_TO_ORIGIN, RETURN_TO_OVERWORLD }

    public PlanContext(Map<String, Integer> inventory, String dimension, boolean allowCombat, boolean allowTravel) {
        this(inventory, dimension, allowCombat, allowTravel, PlanLedger.fromInventory(inventory),
                DimensionDriver.normalizeDimension(dimension), DimensionDriver.normalizeDimension(dimension),
                ReturnPolicy.RETURN_TO_ORIGIN);
    }

    public PlanContext withLedger(PlanLedger ledger) {
        return new PlanContext(inventory, dimension, allowCombat, allowTravel, ledger,
                originDimension, currentPlannedDimension, returnPolicy);
    }

    public PlanContext withPlannedDimension(String dimension) {
        return new PlanContext(inventory, dimension, allowCombat, allowTravel, ledger,
                originDimension, DimensionDriver.normalizeDimension(dimension), returnPolicy);
    }
}
