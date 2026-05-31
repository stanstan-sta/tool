package com.mindcraft.bridge;

import java.util.Map;

public record PlanContext(
    Map<String, Integer> inventory,
    String dimension,
    boolean allowCombat,
    boolean allowTravel
) {}
