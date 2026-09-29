package com.mindcraft.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BridgeSchematicStateTest {

    @Test
    void parsesDistinctDoorAndBedCellStates() {
        BridgeSchematic.StateDescriptor lower = BridgeSchematic.parseDescriptor(
                "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        BridgeSchematic.StateDescriptor upper = BridgeSchematic.parseDescriptor(
                "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]");
        BridgeSchematic.StateDescriptor foot = BridgeSchematic.parseDescriptor(
                "minecraft:red_bed[facing=east,occupied=false,part=foot]");
        BridgeSchematic.StateDescriptor head = BridgeSchematic.parseDescriptor(
                "minecraft:red_bed[facing=east,occupied=false,part=head]");

        assertNotNull(lower);
        assertNotNull(upper);
        assertNotNull(foot);
        assertNotNull(head);
        assertEquals("lower", lower.properties().get("half"));
        assertEquals("upper", upper.properties().get("half"));
        assertEquals("foot", foot.properties().get("part"));
        assertEquals("head", head.properties().get("part"));
    }

    @Test
    void acceptsLegacyBareIdsAndQualifiesTheirNamespace() {
        BridgeSchematic.StateDescriptor descriptor = BridgeSchematic.parseDescriptor("oak_planks");
        assertNotNull(descriptor);
        assertEquals("minecraft:oak_planks", descriptor.blockId());
        assertEquals(0, descriptor.properties().size());
    }

    @Test
    void rejectsMalformedPropertyDescriptorsInsteadOfDroppingTheirState() {
        assertNull(BridgeSchematic.parseDescriptor("minecraft:oak_door[half]"));
        assertNull(BridgeSchematic.parseDescriptor("minecraft:red_bed[part=head,part=foot]"));
    }
}
