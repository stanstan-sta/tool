package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.*;

public class BrewingProvider implements ItemProvider {

    private static final Map<String, BrewingRecipe> BREWING_RECIPES = new HashMap<>();

    public record BrewingRecipe(String output, String modifier, String input, String category) {}

    private static final ThreadLocal<Set<String>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);
    private static final int MAX_DEPTH = 5;

    static {
        addRecipe("awkward_potion", "nether_wart", "water_bottle", "potion");

        addRecipe("potion_of_fire_resistance", "magma_cream", "awkward_potion", "potion");
        addRecipe("potion_of_healing", "glistering_melon_slice", "awkward_potion", "potion");
        addRecipe("potion_of_poison", "spider_eye", "awkward_potion", "potion");
        addRecipe("potion_of_strength", "blaze_powder", "awkward_potion", "potion");
        addRecipe("potion_of_swiftness", "sugar", "awkward_potion", "potion");
        addRecipe("potion_of_leaping", "rabbit_foot", "awkward_potion", "potion");
        addRecipe("potion_of_water_breathing", "pufferfish", "awkward_potion", "potion");
        addRecipe("potion_of_invisibility", "fermented_spider_eye", "potion_of_night_vision", "potion");
        addRecipe("potion_of_night_vision", "golden_carrot", "awkward_potion", "potion");
        addRecipe("potion_of_slow_falling", "phantom_membrane", "awkward_potion", "potion");
        addRecipe("potion_of_the_turtle_master", "turtle_shell", "awkward_potion", "potion");
        addRecipe("potion_of_wind_charging", "breeze_rod", "awkward_potion", "potion");
        addRecipe("potion_of_weaving", "cobweb", "awkward_potion", "potion");
        addRecipe("potion_of_oozing", "slime_block", "awkward_potion", "potion");

        addRecipe("potion_of_harming", "fermented_spider_eye", "potion_of_healing", "potion");
        addRecipe("potion_of_harming", "fermented_spider_eye", "potion_of_poison", "potion");
        addRecipe("potion_of_regeneration", "ghast_tear", "awkward_potion", "potion");
        addRecipe("potion_of_regeneration", "fermented_spider_eye", "potion_of_regeneration", "potion");

        addRecipe("potion_of_healing_ii", "glowstone_dust", "potion_of_healing", "potion");
        addRecipe("potion_of_poison_ii", "glowstone_dust", "potion_of_poison", "potion");
        addRecipe("potion_of_strength_ii", "glowstone_dust", "potion_of_strength", "potion");
        addRecipe("potion_of_swiftness_ii", "glowstone_dust", "potion_of_swiftness", "potion");
        addRecipe("potion_of_leaping_ii", "glowstone_dust", "potion_of_leaping", "potion");
        addRecipe("potion_of_the_turtle_master_ii", "glowstone_dust", "potion_of_the_turtle_master", "potion");
        addRecipe("potion_of_regeneration_ii", "glowstone_dust", "potion_of_regeneration", "potion");

        addRecipe("potion_of_fire_resistance_long", "redstone", "potion_of_fire_resistance", "potion");
        addRecipe("potion_of_healing_long", "redstone", "potion_of_healing", "potion");
        addRecipe("potion_of_poison_long", "redstone", "potion_of_poison", "potion");
        addRecipe("potion_of_strength_long", "redstone", "potion_of_strength", "potion");
        addRecipe("potion_of_swiftness_long", "redstone", "potion_of_swiftness", "potion");
        addRecipe("potion_of_leaping_long", "redstone", "potion_of_leaping", "potion");
        addRecipe("potion_of_water_breathing_long", "redstone", "potion_of_water_breathing", "potion");
        addRecipe("potion_of_invisibility_long", "redstone", "potion_of_invisibility", "potion");
        addRecipe("potion_of_night_vision_long", "redstone", "potion_of_night_vision", "potion");
        addRecipe("potion_of_slow_falling_long", "redstone", "potion_of_slow_falling", "potion");
        addRecipe("potion_of_the_turtle_master_long", "redstone", "potion_of_the_turtle_master", "potion");
        addRecipe("potion_of_wind_charging_long", "redstone", "potion_of_wind_charging", "potion");
        addRecipe("potion_of_weaving_long", "redstone", "potion_of_weaving", "potion");
        addRecipe("potion_of_oozing_long", "redstone", "potion_of_oozing", "potion");
        addRecipe("potion_of_regeneration_long", "redstone", "potion_of_regeneration", "potion");

        addRecipe("potion_of_harming_long", "redstone", "potion_of_harming", "potion");
        addRecipe("potion_of_poison_long", "redstone", "potion_of_poison", "potion");

        addRecipe("splash_potion_of_healing", "gunpowder", "potion_of_healing", "splash_potion");
        addRecipe("splash_potion_of_harming", "gunpowder", "potion_of_harming", "splash_potion");
        addRecipe("splash_potion_of_poison", "gunpowder", "potion_of_poison", "splash_potion");
        addRecipe("splash_potion_of_strength", "gunpowder", "potion_of_strength", "splash_potion");
        addRecipe("splash_potion_of_swiftness", "gunpowder", "potion_of_swiftness", "splash_potion");
        addRecipe("splash_potion_of_fire_resistance", "gunpowder", "potion_of_fire_resistance", "splash_potion");
        addRecipe("splash_potion_of_night_vision", "gunpowder", "potion_of_night_vision", "splash_potion");
        addRecipe("splash_potion_of_water_breathing", "gunpowder", "potion_of_water_breathing", "splash_potion");
        addRecipe("splash_potion_of_leaping", "gunpowder", "potion_of_leaping", "splash_potion");
        addRecipe("splash_potion_of_slow_falling", "gunpowder", "potion_of_slow_falling", "splash_potion");
        addRecipe("splash_potion_of_regeneration", "gunpowder", "potion_of_regeneration", "splash_potion");
        addRecipe("splash_potion_of_invisibility", "gunpowder", "potion_of_invisibility", "splash_potion");
        addRecipe("splash_potion_of_the_turtle_master", "gunpowder", "potion_of_the_turtle_master", "splash_potion");

        addRecipe("splash_potion_of_healing_ii", "gunpowder", "potion_of_healing_ii", "splash_potion");
        addRecipe("splash_potion_of_harming_ii", "gunpowder", "potion_of_harming", "splash_potion");
        addRecipe("splash_potion_of_strength_ii", "gunpowder", "potion_of_strength_ii", "splash_potion");
        addRecipe("splash_potion_of_swiftness_ii", "gunpowder", "potion_of_swiftness_ii", "splash_potion");
        addRecipe("splash_potion_of_poison_ii", "gunpowder", "potion_of_poison_ii", "splash_potion");
        addRecipe("splash_potion_of_leaping_ii", "gunpowder", "potion_of_leaping_ii", "splash_potion");
        addRecipe("splash_potion_of_regeneration_ii", "gunpowder", "potion_of_regeneration_ii", "splash_potion");

        addRecipe("splash_potion_of_fire_resistance_long", "gunpowder", "potion_of_fire_resistance_long", "splash_potion");
        addRecipe("splash_potion_of_healing_long", "gunpowder", "potion_of_healing_long", "splash_potion");
        addRecipe("splash_potion_of_harming_long", "gunpowder", "potion_of_harming_long", "splash_potion");
        addRecipe("splash_potion_of_poison_long", "gunpowder", "potion_of_poison_long", "splash_potion");
        addRecipe("splash_potion_of_strength_long", "gunpowder", "potion_of_strength_long", "splash_potion");
        addRecipe("splash_potion_of_swiftness_long", "gunpowder", "potion_of_swiftness_long", "splash_potion");
        addRecipe("splash_potion_of_water_breathing_long", "gunpowder", "potion_of_water_breathing_long", "splash_potion");
        addRecipe("splash_potion_of_night_vision_long", "gunpowder", "potion_of_night_vision_long", "splash_potion");
        addRecipe("splash_potion_of_leaping_long", "gunpowder", "potion_of_leaping_long", "splash_potion");
        addRecipe("splash_potion_of_slow_falling_long", "gunpowder", "potion_of_slow_falling_long", "splash_potion");
        addRecipe("splash_potion_of_regeneration_long", "gunpowder", "potion_of_regeneration_long", "splash_potion");
        addRecipe("splash_potion_of_invisibility_long", "gunpowder", "potion_of_invisibility_long", "splash_potion");
        addRecipe("splash_potion_of_the_turtle_master_long", "gunpowder", "potion_of_the_turtle_master_long", "splash_potion");

        addRecipe("lingering_potion_of_healing", "dragon_breath", "splash_potion_of_healing", "lingering_potion");
        addRecipe("lingering_potion_of_harming", "dragon_breath", "splash_potion_of_harming", "lingering_potion");
        addRecipe("lingering_potion_of_poison", "dragon_breath", "splash_potion_of_poison", "lingering_potion");
        addRecipe("lingering_potion_of_strength", "dragon_breath", "splash_potion_of_strength", "lingering_potion");
        addRecipe("lingering_potion_of_swiftness", "dragon_breath", "splash_potion_of_swiftness", "lingering_potion");
        addRecipe("lingering_potion_of_fire_resistance", "dragon_breath", "splash_potion_of_fire_resistance", "lingering_potion");
        addRecipe("lingering_potion_of_night_vision", "dragon_breath", "splash_potion_of_night_vision", "lingering_potion");
        addRecipe("lingering_potion_of_water_breathing", "dragon_breath", "splash_potion_of_water_breathing", "lingering_potion");
        addRecipe("lingering_potion_of_leaping", "dragon_breath", "splash_potion_of_leaping", "lingering_potion");
        addRecipe("lingering_potion_of_slow_falling", "dragon_breath", "splash_potion_of_slow_falling", "lingering_potion");
        addRecipe("lingering_potion_of_regeneration", "dragon_breath", "splash_potion_of_regeneration", "lingering_potion");
        addRecipe("lingering_potion_of_invisibility", "dragon_breath", "splash_potion_of_invisibility", "lingering_potion");
        addRecipe("lingering_potion_of_the_turtle_master", "dragon_breath", "splash_potion_of_the_turtle_master", "lingering_potion");
    }

    private static void addRecipe(String output, String modifier, String input, String category) {
        BREWING_RECIPES.put(ItemIds.normalize(output), new BrewingRecipe(
            ItemIds.normalize(output), ItemIds.normalize(modifier), ItemIds.normalize(input), category));
    }

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        return BREWING_RECIPES.containsKey(id) || id.equals("minecraft:water_bottle");
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

        inProgress.add(normalizedId);
        try {
            String id = ItemIds.normalize(itemId);
            List<PlanStep> steps = new ArrayList<>();

            if (id.equals("minecraft:water_bottle")) {
                int hasBottles = ctx.inventory().getOrDefault("minecraft:glass_bottle", 0);
                if (hasBottles < count) {
                    int missing = count - hasBottles;
                    boolean resolved = resolveIngredient("minecraft:glass_bottle", missing, steps, ctx);
                    if (!resolved) return new ProviderPlan(false, "NO_BOTTLES", steps);
                }
                steps.add(new PlanStep("brew", "{\"potions\":\"minecraft:water_bottle\",\"count\":" + count + "}"));
                return new ProviderPlan(true, null, steps);
            }

            BrewingRecipe recipe = BREWING_RECIPES.get(id);
            if (recipe == null) return new ProviderPlan(false, "NO_BREW_RECIPE", steps);

            int hasBottles = ctx.inventory().getOrDefault("minecraft:glass_bottle", 0);
            if (hasBottles < count) {
                int missing = count - hasBottles;
                boolean resolved = resolveIngredient("minecraft:glass_bottle", missing, steps, ctx);
                if (!resolved) return new ProviderPlan(false, "NO_BOTTLES", steps);
            }

            int hasFuel = ctx.inventory().getOrDefault("minecraft:blaze_powder", 0);
            if (hasFuel <= 0) {
                boolean resolved = resolveIngredient("minecraft:blaze_powder", 1, steps, ctx);
                if (!resolved) return new ProviderPlan(false, "NO_BREW_FUEL", steps);
            }

            if (!ctx.inventory().containsKey(recipe.input())) {
                ProviderPlan basePlan = planRecursive(recipe.input(), count, ctx, steps, depth + 1);
                if (!basePlan.ok()) return new ProviderPlan(false, "NO_BASE_" + recipe.input(), steps);
            }

            if (!ctx.inventory().containsKey(recipe.modifier())) {
                boolean resolved = resolveIngredient(recipe.modifier(), 1, steps, ctx);
                if (!resolved) return new ProviderPlan(false, "NO_MODIFIER_" + recipe.modifier(), steps);
            }

            String actionJson = "{\"potions\":\"" + id + "\",\"count\":" + count + "}";
            steps.add(new PlanStep("brew", actionJson));
            return new ProviderPlan(true, null, steps);
        } finally {
            inProgress.remove(normalizedId);
        }
    }

    private ProviderPlan planRecursive(String potionId, int count, PlanContext ctx, List<PlanStep> steps, int depth) {
        if (depth > MAX_DEPTH) {
            return new ProviderPlan(false, "MAX_DEPTH_EXCEEDED", steps);
        }

        String normalizedId = ItemIds.normalize(potionId);
        Set<String> inProgress = IN_PROGRESS.get();
        if (inProgress.contains(normalizedId)) {
            return new ProviderPlan(false, "CYCLE_DETECTED_" + normalizedId, steps);
        }

        inProgress.add(normalizedId);
        try {
            String id = ItemIds.normalize(potionId);

            if (id.equals("minecraft:water_bottle")) {
                steps.add(new PlanStep("brew", "{\"potions\":\"minecraft:water_bottle\",\"count\":" + count + "}"));
                return new ProviderPlan(true, null, steps);
            }

            BrewingRecipe recipe = BREWING_RECIPES.get(id);
            if (recipe == null) return new ProviderPlan(false, "NO_BREW_RECIPE", steps);

            if (!ctx.inventory().containsKey(recipe.input())) {
                planRecursive(recipe.input(), count, ctx, steps, depth + 1);
            }

            if (!ctx.inventory().containsKey(recipe.modifier())) {
                boolean resolved = resolveIngredient(recipe.modifier(), 1, steps, ctx);
                if (!resolved) return new ProviderPlan(false, "NO_MODIFIER_" + recipe.modifier(), steps);
            }

            return new ProviderPlan(true, null, steps);
        } finally {
            inProgress.remove(normalizedId);
        }
    }

    private boolean resolveIngredient(String itemId, int count, List<PlanStep> steps, PlanContext ctx) {
        for (ItemProvider p : CommandExecutor.allProviders()) {
            if (p == this) continue;
            if (p.canProvide(itemId, ctx)) {
                ProviderPlan sub = p.plan(itemId, count, ctx);
                if (sub.ok()) {
                    steps.addAll(sub.steps());
                    return true;
                }
            }
        }
        return false;
    }

    public BrewingRecipe getRecipe(String potionId) {
        return BREWING_RECIPES.get(ItemIds.normalize(potionId));
    }

    public boolean canBrew(String potionId) {
        return BREWING_RECIPES.containsKey(ItemIds.normalize(potionId));
    }

    public Set<String> getBrewablePotions() {
        return Collections.unmodifiableSet(BREWING_RECIPES.keySet());
    }
}
