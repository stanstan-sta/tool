package com.mindcraft.bridge;

import java.util.List;

public record ProviderPlan(
    boolean ok,
    String failureCode,
    List<PlanStep> steps
) {}
