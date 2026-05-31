package com.mindcraft.bridge.providers;

import com.mindcraft.bridge.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ContainerLootProvider implements ItemProvider {
    private static final Set<String> LOOTABLE_ITEMS = Set.of(
        "minecraft:enchanted_golden_apple", "minecraft:name_tag", "minecraft:saddle",
        "minecraft:leather_horse_armor", "minecraft:iron_horse_armor",
        "minecraft:golden_horse_armor", "minecraft:diamond_horse_armor",
        "minecraft:nautilus_shell", "minecraft:heart_of_the_sea",
        "minecraft:experience_bottle", "minecraft:dragon_breath",
        "minecraft:elytra", "minecraft:totem_of_undying",
        "minecraft:trident", "minecraft:shulker_shell",
        "minecraft:spire_armor_trim_smithing_template",
        "minecraft:snout_armor_trim_smithing_template",
        "minecraft:rib_armor_trim_smithing_template",
        "minecraft:ward_armor_trim_smithing_template",
        "minecraft:silence_armor_trim_smithing_template"
    );

    @Override
    public boolean canProvide(String itemId, PlanContext ctx) {
        return LOOTABLE_ITEMS.contains(ItemIds.normalize(itemId));
    }

    @Override
    public ProviderPlan plan(String itemId, int count, PlanContext ctx) {
        String id = ItemIds.normalize(itemId);
        List<PlanStep> steps = new ArrayList<>();

        // Check if item already in inventory
        int hasItem = ctx.inventory().getOrDefault(id, 0);
        if (hasItem >= count) {
            return new ProviderPlan(true, null, List.of());
        }

        // Check for containers nearby — if none, the loot action will search
        // The loot worker handles container discovery at runtime

        steps.add(new PlanStep("loot", "{\"target\":\"" + id + "\",\"count\":" + count + "}"));
        return new ProviderPlan(true, null, steps);
    }
}
