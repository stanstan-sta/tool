package com.mindcraft.bridge;

import com.mindcraft.bridge.workers.ActionRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorJsonTest {

    @Test
    void extractJsonString_returnsValueForKey() {
        String json = "{\"name\":\"diamond_sword\",\"count\":5}";
        assertEquals("diamond_sword", CommandExecutor.extractJsonString(json, "name"));
    }

    @Test
    void extractJsonString_returnsNullForMissingKey() {
        String json = "{\"name\":\"diamond_sword\"}";
        assertNull(CommandExecutor.extractJsonString(json, "missing"));
    }

    @Test
    void extractJsonString_handlesEscapedQuotes() {
        String json = "{\"msg\":\"hello \\\"world\\\"\"}";
        assertEquals("hello \"world\"", CommandExecutor.extractJsonString(json, "msg"));
    }

    @Test
    void extractJsonString_handlesEscapedNewlines() {
        String json = "{\"msg\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", CommandExecutor.extractJsonString(json, "msg"));
    }

    @Test
    void extractJsonPrimitive_returnsNumber() {
        String json = "{\"count\":5}";
        assertEquals("5", CommandExecutor.extractJsonPrimitive(json, "count"));
    }

    @Test
    void extractJsonPrimitive_returnsQuotedString() {
        String json = "{\"type\":\"smith\"}";
        assertEquals("smith", CommandExecutor.extractJsonPrimitive(json, "type"));
    }

    @Test
    void extractJsonPrimitive_returnsNullForMissingKey() {
        String json = "{\"count\":5}";
        assertNull(CommandExecutor.extractJsonPrimitive(json, "missing"));
    }

    @Test
    void extractJsonObject_returnsNestedObject() {
        String json = "{\"items\":{\"sword\":1,\"pickaxe\":2},\"extra\":\"data\"}";
        String obj = CommandExecutor.extractJsonObject(json, "items");
        assertNotNull(obj);
        assertEquals("{\"sword\":1,\"pickaxe\":2}", obj);
    }

    @Test
    void extractJsonObject_returnsNullForMissingKey() {
        String json = "{\"items\":{\"sword\":1}}";
        assertNull(CommandExecutor.extractJsonObject(json, "missing"));
    }

    @Test
    void splitJsonArray_parsesSimpleArray() {
        var result = CommandExecutor.splitJsonArray("[\"a\",\"b\",\"c\"]");
        assertEquals(3, result.size());
        assertEquals("\"a\"", result.get(0));
        assertEquals("\"b\"", result.get(1));
        assertEquals("\"c\"", result.get(2));
    }

    @Test
    void splitJsonArray_parsesEmptyArray() {
        var result = CommandExecutor.splitJsonArray("[]");
        assertEquals(0, result.size());
    }

    @Test
    void splitJsonArray_parsesNestedObjects() {
        var result = CommandExecutor.splitJsonArray("[{\"x\":1},{\"y\":2}]");
        assertEquals(2, result.size());
        assertEquals("{\"x\":1}", result.get(0));
        assertEquals("{\"y\":2}", result.get(1));
    }

    @Test
    void splitJsonArray_returnsNullInputAsEmptyList() {
        var result = CommandExecutor.splitJsonArray(null);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void sleepTry_translatesToQueuedSleepCommand() {
        CommandExecutor.TranslatedAction result = CommandExecutor.translateTypedJson("{\"type\":\"sleep_try\"}");

        assertTrue(result.ok());
        assertEquals("sleep_try", result.actionType());
        assertEquals("queued", result.lifecycle());
        assertEquals("#sleep", result.command());
    }

    @Test
    void rawCommandSleepAliasesNormalizeToSleepCommand() {
        CommandExecutor.TranslatedAction plain = CommandExecutor.translateTypedJson(
            "{\"type\":\"raw_command\",\"command\":\"sleep\"}");
        CommandExecutor.TranslatedAction task = CommandExecutor.translateTypedJson(
            "{\"type\":\"raw_command\",\"command\":\"#task sleep\"}");

        assertTrue(plain.ok());
        assertEquals("#sleep", plain.command());
        assertTrue(task.ok());
        assertEquals("#sleep", task.command());
    }

    @Test
    void rawCommandForbiddenCommandIsRejected() {
        CommandExecutor.TranslatedAction result = CommandExecutor.translateTypedJson(
            "{\"type\":\"raw_command\",\"command\":\"/op player\"}");

        assertFalse(result.ok());
        assertEquals("raw_command_forbidden", result.failureCode());
    }

    @Test
    void brewSmithEnchantTranslateAsGenericWorkers() {
        CommandExecutor.TranslatedAction brew = CommandExecutor.translateTypedJson(
            "{\"type\":\"brew\",\"potions\":\"minecraft:water_bottle\",\"ingredient\":\"minecraft:nether_wart\"}");
        CommandExecutor.TranslatedAction smith = CommandExecutor.translateTypedJson(
            "{\"type\":\"smith\",\"template\":\"minecraft:netherite_upgrade_smithing_template\",\"base\":\"minecraft:diamond_chestplate\",\"addition\":\"minecraft:netherite_ingot\"}");
        CommandExecutor.TranslatedAction enchant = CommandExecutor.translateTypedJson(
            "{\"type\":\"enchant\",\"item\":\"minecraft:diamond_sword\"}");

        assertTrue(brew.ok());
        assertTrue(brew.genericWorker());
        assertEquals("#brew", brew.command());
        assertTrue(smith.ok());
        assertTrue(smith.genericWorker());
        assertEquals("#smith", smith.command());
        assertTrue(enchant.ok());
        assertTrue(enchant.genericWorker());
        assertEquals("#enchant", enchant.command());
    }

    @Test
    void bridgeStateActionsAreRegisteredAsExternalActions() {
        var registry = ActionRegistry.get();

        assertTrue(registry.isExternallyRoutable("screen_click_slot"));
        assertTrue(registry.isExternallyRoutable("container_deposit"));
        assertTrue(registry.isExternallyRoutable("container_withdraw"));
        assertTrue(registry.isExternallyRoutable("container_quick_move"));
        assertTrue(registry.isExternallyRoutable("look"));
        assertTrue(registry.isExternallyRoutable("look_at"));
        assertTrue(registry.isExternallyRoutable("press_key"));
        assertTrue(registry.isExternallyRoutable("swing"));
        assertTrue(registry.isExternallyRoutable("attack_entity"));
        assertTrue(registry.isExternallyRoutable("use_item_on_block"));
        assertTrue(registry.isExternallyRoutable("use_item_on_entity"));
        assertTrue(registry.isExternallyRoutable("hold_use_item"));
    }

    @Test
    void bridgeStateActionsValidateMissingRequiredFieldsBeforeExecution() {
        CommandExecutor.TranslatedAction click = CommandExecutor.translateTypedJson("{\"type\":\"screen_click_slot\"}");
        CommandExecutor.TranslatedAction look = CommandExecutor.translateTypedJson("{\"type\":\"look\",\"yaw\":90}");
        CommandExecutor.TranslatedAction attack = CommandExecutor.translateTypedJson("{\"type\":\"attack_entity\"}");
        CommandExecutor.TranslatedAction useBlock = CommandExecutor.translateTypedJson("{\"type\":\"use_item_on_block\",\"x\":1,\"z\":3}");

        assertFalse(click.ok());
        assertEquals("missing_slot", click.failureCode());
        assertFalse(look.ok());
        assertEquals("missing_pitch", look.failureCode());
        assertFalse(attack.ok());
        assertEquals("missing_entity_id", attack.failureCode());
        assertFalse(useBlock.ok());
        assertEquals("missing_coordinates", useBlock.failureCode());
    }

    @Test
    void bridgeStateActionsValidateInvalidValuesBeforeExecution() {
        CommandExecutor.TranslatedAction key = CommandExecutor.translateTypedJson(
            "{\"type\":\"press_key\",\"key\":\"warp_drive\"}");
        CommandExecutor.TranslatedAction slotAction = CommandExecutor.translateTypedJson(
            "{\"type\":\"screen_click_slot\",\"slot\":1,\"action\":\"teleport\"}");
        CommandExecutor.TranslatedAction hold = CommandExecutor.translateTypedJson(
            "{\"type\":\"hold_use_item\",\"duration_ms\":9000}");

        assertFalse(key.ok());
        assertEquals("invalid_key", key.failureCode());
        assertFalse(slotAction.ok());
        assertEquals("invalid_action", slotAction.failureCode());
        assertFalse(hold.ok());
        assertEquals("invalid_duration_ms", hold.failureCode());
    }

    @Test
    void bridgeMineCommandForGatherUsesBaritoneTargetTotalPlusCraftReserve() {
        CommandExecutor.MakePlan plan = new CommandExecutor.MakePlan();
        plan.addSnapshot("minecraft:cobblestone", 2);
        CommandExecutor.GatherProvider gather = new CommandExecutor.GatherProvider(
                java.util.List.of("cobblestone", "stone"), "minecraft:cobblestone");

        String command = CommandExecutor.bridgeMineCommandForGather(plan, gather, 1);

        assertEquals("#mine 8 cobblestone stone", command);
    }

    @Test
    void bridgeMineCommandForGatherDoesNotReserveOreDrops() {
        CommandExecutor.MakePlan plan = new CommandExecutor.MakePlan();
        plan.addSnapshot("minecraft:raw_iron", 2);
        CommandExecutor.GatherProvider gather = new CommandExecutor.GatherProvider(
                java.util.List.of("iron_ore", "deepslate_iron_ore"), "minecraft:raw_iron");

        String command = CommandExecutor.bridgeMineCommandForGather(plan, gather, 1);

        assertEquals("#mine 3 iron_ore deepslate_iron_ore", command);
    }

    @Test
    void coalesceMineStepsUsesTargetTotalAndPreservesAliases() {
        var steps = java.util.List.of(
                new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.MINE,
                        "raw_iron", 3, "#mine 3 iron_ore deepslate_iron_ore"),
                new CommandExecutor.MakeStep(CommandExecutor.MakeStepKind.MINE,
                        "raw_iron", 5, "#mine 5 iron_ore deepslate_iron_ore"));

        java.util.List<CommandExecutor.MakeStep> coalesced = CommandExecutor.coalesceMakeSteps(steps);

        assertEquals(1, coalesced.size());
        assertEquals(5, coalesced.get(0).count());
        assertEquals("#mine 5 iron_ore deepslate_iron_ore", coalesced.get(0).command());
    }

}
