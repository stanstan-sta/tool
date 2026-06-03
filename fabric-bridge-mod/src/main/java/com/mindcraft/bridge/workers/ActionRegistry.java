package com.mindcraft.bridge.workers;

import com.mindcraft.bridge.BridgeConfig;
import com.mindcraft.bridge.workers.crafting.SmithWorker;
import com.mindcraft.bridge.workers.crafting.BrewWorker;
import com.mindcraft.bridge.workers.crafting.EnchantWorker;
import com.mindcraft.bridge.workers.combat.CombatWorker;
import com.mindcraft.bridge.workers.combat.BossCombatWorker;
import com.mindcraft.bridge.workers.husbandry.HusbandryWorker;
import com.mindcraft.bridge.workers.gathering.FarmWorker;
import com.mindcraft.bridge.workers.gathering.LootWorker;
import com.mindcraft.bridge.workers.gathering.FishWorker;
import com.mindcraft.bridge.workers.redstone.RedstoneWorker;
import com.mindcraft.bridge.workers.storage.SpecialStorageWorker;

import java.util.LinkedHashMap;
import java.util.Map;

public class ActionRegistry {
    private static final ActionRegistry INSTANCE = new ActionRegistry();

    public enum ActionLifecycle { QUEUED, SELF_EXECUTING, IMMEDIATE }
    public enum ActionVisibility { PUBLIC, EXPERIMENTAL, INTERNAL, DISABLED }
    public enum DispatchKind { EXPLICIT, GENERIC_WORKER, BARITONE, NODE_ONLY }

    public record ActionSpec(
        String type,
        String provider,
        ActionLifecycle lifecycle,
        ActionVisibility visibility,
        DispatchKind dispatchKind,
        String[] required,
        String[] optional,
        String description
    ) {}

    private final Map<String, ActionSpec> specs = new LinkedHashMap<>();
    private final Map<String, Worker> workers = new LinkedHashMap<>();

    private ActionRegistry() {
        // ── Baritone commands (routed via extractRawBaritoneCommand) ──
        registerSpec(new ActionSpec("move", "baritone_chat", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.BARITONE,
            new String[]{"x", "y", "z"}, new String[]{}, "Walk to coordinates"));
        registerSpec(new ActionSpec("mine", "baritone_chat", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.BARITONE,
            new String[]{"target"}, new String[]{"count", "secondaryTarget"}, "Mine target blocks"));
        registerSpec(new ActionSpec("follow", "baritone_chat", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.BARITONE,
            new String[]{"target"}, new String[]{}, "Follow player"));
        registerSpec(new ActionSpec("cancel", "baritone_chat", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.BARITONE,
            new String[]{}, new String[]{}, "Cancel queue"));
        registerSpec(new ActionSpec("raw_command", "baritone_chat", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.BARITONE,
            new String[]{"command"}, new String[]{}, "Run raw Baritone command"));

        // ── Explicit handlers in CommandExecutor ──
        registerSpec(new ActionSpec("craft", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item"}, new String[]{"count"}, "Craft or acquire item with full auto-planning"));
        registerSpec(new ActionSpec("flee", "bridge", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"distance"}, "Move away from nearest hostile"));
        registerWorker(new ActionSpec("attack", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"target_type", "count", "search_time_s", "until_items", "retreat_hp", "max_distance"}, "Melee hunt state machine"), new CombatWorker());
        registerSpec(new ActionSpec("sleep_try", "bridge", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{}, "Queue a sleep attempt with #sleep"));
        registerSpec(new ActionSpec("build_schematic", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"origin", "size", "palette", "blocks"}, new String[]{"name"}, "Build from in-memory schematic payload"));
        registerSpec(new ActionSpec("cancel_build", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{}, "Cancel builder process"));
        registerSpec(new ActionSpec("select_slot", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"slot"}, new String[]{}, "Select hotbar slot (0-8)"));
        registerSpec(new ActionSpec("equip", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item", "slot"}, new String[]{}, "Equip item to armor slot"));
        registerSpec(new ActionSpec("equip_best", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"slot"}, "Equip best weapon from inventory"));
        registerSpec(new ActionSpec("use_item", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item"}, new String[]{}, "Use item in hand"));
        registerSpec(new ActionSpec("consume", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item"}, new String[]{}, "Consume food/potion"));
        registerSpec(new ActionSpec("drop_items", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item"}, new String[]{"count"}, "Drop items from inventory"));
        registerSpec(new ActionSpec("pickup_items", "bridge", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"radius"}, "Pick up nearby items"));
        registerSpec(new ActionSpec("open_block", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"x", "y", "z"}, new String[]{}, "Open block screen"));
        registerSpec(new ActionSpec("close_screen", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{}, "Close open screen"));
        registerSpec(new ActionSpec("transfer_items", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item", "count", "from_slot", "to_slot"}, new String[]{}, "Transfer items between slots"));
        registerSpec(new ActionSpec("screen_click_slot", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"slot"}, new String[]{"button", "action", "sync_id"}, "Click a slot in the currently open screen"));
        registerSpec(new ActionSpec("container_deposit", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"item", "slot", "count", "sync_id"}, "Move an exact item count from player inventory into the open container"));
        registerSpec(new ActionSpec("container_withdraw", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"item", "slot", "count", "sync_id"}, "Move an exact item count from the open container into player inventory"));
        registerSpec(new ActionSpec("container_quick_move", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"slot"}, new String[]{}, "Shift-click a screen slot"));
        registerSpec(new ActionSpec("look", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"yaw", "pitch"}, new String[]{}, "Set player yaw and pitch"));
        registerSpec(new ActionSpec("look_at", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"x", "y", "z", "entity_id"}, "Look at coordinates or an entity"));
        registerSpec(new ActionSpec("press_key", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"key"}, new String[]{"pressed", "duration_ms"}, "Press or release a movement or action key"));
        registerSpec(new ActionSpec("swing", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"hand"}, "Swing main hand or offhand"));
        registerSpec(new ActionSpec("attack_entity", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"entity_id"}, new String[]{}, "Attack an entity by id"));
        registerSpec(new ActionSpec("use_item_on_block", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"x", "y", "z"}, new String[]{"face", "direction", "hand"}, "Use held item on a block"));
        registerSpec(new ActionSpec("use_item_on_entity", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"entity_id"}, new String[]{"hand"}, "Use held item on an entity"));
        registerSpec(new ActionSpec("hold_use_item", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"duration_ms", "hand"}, "Hold use-item briefly"));
        registerSpec(new ActionSpec("interact_block", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"x", "y", "z"}, new String[]{"direction"}, "Interact with block"));
        registerSpec(new ActionSpec("interact_entity", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"entity_id"}, new String[]{}, "Interact with entity"));
        registerWorker(new ActionSpec("smith", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"template", "base", "addition"}, new String[]{"output"},
            "Use a smithing table for a known recipe only. Netherite upgrade requires netherite_upgrade_smithing_template + diamond gear + netherite_ingot. Armor trim requires a specified *_armor_trim_smithing_template + armor + trim material. Worker auto-finds the table."), new SmithWorker());
        registerWorker(new ActionSpec("brew", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"ingredient", "potions", "fuel"}, "Brew potions"), new BrewWorker());
        registerWorker(new ActionSpec("enchant", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"item", "level", "lapis"}, "Enchant item"), new EnchantWorker());
        registerSpec(new ActionSpec("anvil", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"input1", "input2"}, new String[]{"output", "name"}, "Use anvil"));
        registerSpec(new ActionSpec("grindstone", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"input1", "input2"}, new String[]{}, "Disenchant or repair on grindstone"));
        registerSpec(new ActionSpec("stonecut", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"input", "output"}, new String[]{"count"}, "Cut stone blocks"));
        registerSpec(new ActionSpec("loom", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"banner", "dye"}, new String[]{"pattern"}, "Apply pattern to banner"));
        registerSpec(new ActionSpec("cartography", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"input1", "input2"}, new String[]{}, "Use cartography table"));
        registerSpec(new ActionSpec("trade", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"villager_id"}, new String[]{"trade_index"}, "Trade with villager"));
        registerSpec(new ActionSpec("obtain", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"item"}, new String[]{"count"}, "Obtain item through any supported provider"));
        registerSpec(new ActionSpec("ranged_attack", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"target_type", "count", "max_distance"}, "Ranged bow/crossbow attack"));
        registerSpec(new ActionSpec("defend", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"duration_s"}, "Shield defense mode"));
        registerSpec(new ActionSpec("retreat", "bridge", ActionLifecycle.QUEUED, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"distance"}, "Retreat from combat"));
        registerSpec(new ActionSpec("clear_hostiles", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"radius"}, "Kill all hostiles in radius"));
        registerSpec(new ActionSpec("hunt_mob", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"target_type"}, new String[]{"count", "search_time_s"}, "Hunt specific mob type"));
        registerSpec(new ActionSpec("ride_entity", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"entity_id"}, new String[]{}, "Mount entity"));
        registerSpec(new ActionSpec("use_boat", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"x", "y", "z"}, "Place and mount boat"));
        registerSpec(new ActionSpec("use_minecart", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"x", "y", "z"}, "Place and mount minecart"));
        registerSpec(new ActionSpec("dismount", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{}, "Dismount current vehicle"));
        registerSpec(new ActionSpec("elytra_fly", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"x", "y", "z"}, new String[]{}, "Fly with elytra to coordinates"));
        registerSpec(new ActionSpec("portal_travel", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"dimension"}, new String[]{}, "Travel through portal"));
        registerSpec(new ActionSpec("return_to_overworld", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{}, "Return to overworld from nether/end"));
        registerSpec(new ActionSpec("place_block", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"x", "y", "z", "block"}, new String[]{"direction"}, "Place single block"));
        registerSpec(new ActionSpec("break_block", "bridge", ActionLifecycle.IMMEDIATE, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"x", "y", "z"}, new String[]{}, "Break single block"));
        registerSpec(new ActionSpec("validate_structure", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"origin", "size", "palette", "blocks"}, new String[]{}, "Validate built structure against schematic"));
        registerSpec(new ActionSpec("repair_structure", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"origin", "size", "palette", "blocks"}, new String[]{}, "Repair mismatched blocks in built structure"));
        registerSpec(new ActionSpec("collect_fluid", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"target"}, new String[]{}, "Collect fluid with bucket"));
        registerWorker(new ActionSpec("farm", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"crop"}, new String[]{"count"}, "Harvest crops with replanting"), new FarmWorker());
        registerWorker(new ActionSpec("fish", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{}, new String[]{"count"}, "Fish with rod"), new FishWorker());
        registerWorker(new ActionSpec("loot", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.EXPLICIT,
            new String[]{"target"}, new String[]{}, "Loot item from container"), new LootWorker());

        // ── Generic workers (dispatched via TaskQueue WORKER_THREAD) ──
        registerWorker(new ActionSpec("shear", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Shear nearby sheep"), new HusbandryWorker());
        registerWorker(new ActionSpec("milk", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Milk nearby cow"), new HusbandryWorker());
        registerWorker(new ActionSpec("breed", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Breed nearby animals"), new HusbandryWorker());
        registerWorker(new ActionSpec("tame", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Tame nearby animal"), new HusbandryWorker());
        registerWorker(new ActionSpec("fight_dragon", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"retreat_hp"}, "Fight Ender Dragon"), new BossCombatWorker());
        registerWorker(new ActionSpec("fight_wither", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"retreat_hp"}, "Fight Wither"), new BossCombatWorker());
        registerWorker(new ActionSpec("raid", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"retreat_hp"}, "Defend against village raid"), new BossCombatWorker());
        registerWorker(new ActionSpec("tune_repeater", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"x", "y", "z", "delay"}, "Set repeater delay"), new RedstoneWorker());
        registerWorker(new ActionSpec("read_comparator", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"x", "y", "z"}, "Read comparator signal strength"), new RedstoneWorker());
        registerWorker(new ActionSpec("place_redstone", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"x", "y", "z", "block"}, "Place redstone dust or component"), new RedstoneWorker());
        registerWorker(new ActionSpec("interact_item_frame", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{"entity_id"}, new String[]{}, "Place item in item frame"), new SpecialStorageWorker());
        registerWorker(new ActionSpec("interact_armor_stand", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{"entity_id"}, new String[]{}, "Place armor on armor stand"), new SpecialStorageWorker());
        registerWorker(new ActionSpec("use_jukebox", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"x", "y", "z", "disc"}, "Insert or eject music disc"), new SpecialStorageWorker());
        registerWorker(new ActionSpec("find_portal", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Find nearest portal"), new LootWorker());
        registerWorker(new ActionSpec("light_portal", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Light a nether portal"), new LootWorker());
        registerWorker(new ActionSpec("fill_portal", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Fill end portal frame blocks"), new LootWorker());
        registerWorker(new ActionSpec("locate_stronghold", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Locate nearest stronghold"), new LootWorker());
        registerWorker(new ActionSpec("cure_villager", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Cure zombie villager"), new LootWorker());
        registerWorker(new ActionSpec("find_entity", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{"entity_type"}, "Find nearest living/item entity by type. Not for blocks or workstation blocks such as smithing_table."), new LootWorker());
        registerWorker(new ActionSpec("feed_entity", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.PUBLIC, DispatchKind.GENERIC_WORKER,
            new String[]{"entity_id"}, new String[]{}, "Feed an entity"), new LootWorker());

        // ── Internal (not shown in /capabilities, not externally routable) ──
        registerWorker(new ActionSpec("cycle_lectern", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.INTERNAL, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Cycle lectern book pages (internal)"), new LootWorker());
        registerWorker(new ActionSpec("cycle_trades", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.INTERNAL, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Cycle villager trades (internal)"), new LootWorker());
        registerWorker(new ActionSpec("find_enchanting_table", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.INTERNAL, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Find enchanting table (internal)"), new LootWorker());
        registerWorker(new ActionSpec("enchant_item", "bridge", ActionLifecycle.SELF_EXECUTING, ActionVisibility.INTERNAL, DispatchKind.GENERIC_WORKER,
            new String[]{}, new String[]{}, "Alternate enchant entry (internal)"), new EnchantWorker());
    }

    public static ActionRegistry get() { return INSTANCE; }

    public void registerSpec(ActionSpec spec) {
        specs.put(spec.type(), spec);
    }

    public void registerWorker(ActionSpec spec, Worker worker) {
        specs.put(spec.type(), spec);
        workers.put(spec.type(), worker);
    }

    public ActionSpec getSpec(String type) {
        return specs.get(type);
    }

    public Worker getWorker(String type) {
        return workers.get(type);
    }

    public boolean hasWorker(String type) {
        return workers.containsKey(type);
    }

    public boolean isKnown(String type) {
        return specs.containsKey(type);
    }

    public boolean isSelfExecuting(String type) {
        ActionSpec spec = specs.get(type);
        return spec != null && spec.lifecycle() == ActionLifecycle.SELF_EXECUTING;
    }

    public boolean isExternallyRoutable(String type) {
        ActionSpec spec = specs.get(type);
        if (spec == null) return false;
        if (spec.visibility() == ActionVisibility.DISABLED) return false;
        if (spec.visibility() == ActionVisibility.INTERNAL) return false;
        if (spec.visibility() == ActionVisibility.EXPERIMENTAL && !BridgeConfig.get().enableExperimentalActions) return false;
        return true;
    }

    public boolean isCapabilityVisible(String type) {
        return isExternallyRoutable(type);
    }

    public DispatchKind dispatchKind(String type) {
        ActionSpec spec = specs.get(type);
        return spec == null ? null : spec.dispatchKind();
    }

    public String actionTypesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ActionSpec spec : specs.values()) {
            if (!isCapabilityVisible(spec.type())) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(spec.type())).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    public String capabilitiesJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"supports_typed_actions\":true,");
        sb.append("\"default_provider\":\"baritone_chat\",");
        sb.append("\"providers\":[\"baritone_chat\"],");
        sb.append("\"action_types\":").append(actionTypesJson()).append(",");
        sb.append("\"protocol_version\":1,");
        sb.append("\"bridge_version\":\"1.1.0\",");
        sb.append("\"minecraft_version\":\"1.21.11\",");
        sb.append("\"actions\":[");
        boolean first = true;
        for (ActionSpec spec : specs.values()) {
            if (!isCapabilityVisible(spec.type())) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"type\":\"").append(jsonEscape(spec.type())).append("\",");
            sb.append("\"provider\":\"").append(jsonEscape(spec.provider())).append("\",");
            sb.append("\"lifecycle\":\"").append(jsonEscape(spec.lifecycle().name().toLowerCase())).append("\",");
            sb.append("\"dispatch\":\"").append(jsonEscape(spec.dispatchKind().name().toLowerCase())).append("\",");
            sb.append("\"required\":").append(stringArrayJson(spec.required())).append(",");
            sb.append("\"optional\":").append(stringArrayJson(spec.optional())).append(",");
            sb.append("\"description\":\"").append(jsonEscape(spec.description())).append("\"");
            sb.append("}");
        }
        sb.append("],");
        sb.append("\"state_fields\":[\"selected_slot\",\"held_items\",\"equipment\",\"equipment_detail\",\"effects\",\"xp\",\"open_screen\",\"targeted_block\",\"targeted_entity\",\"environment\",\"nearby_block_entities\"],");
        sb.append("\"queue_fields\":[\"active_id\",\"active_action_type\",\"active\",\"kind\",\"completion\",\"pending\",\"paused\",\"lastFailure\",\"status\",\"failure_code\",\"elapsed_ms\",\"timeout_ms\",\"cancellable\"],");
        sb.append("\"version\":\"1.1.0\"");
        sb.append("}");
        return sb.toString();
    }

    private static String stringArrayJson(String[] arr) {
        if (arr == null || arr.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(jsonEscape(arr[i])).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
