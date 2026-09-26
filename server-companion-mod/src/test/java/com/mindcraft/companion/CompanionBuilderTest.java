package com.mindcraft.companion;

import com.mindcraft.protocol.PlayerInfo;
import com.mindcraft.protocol.EventMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompanionBuilderTest {

    @Test
    void buildPlayerInfoMapsAllFields() {
        PlayerInfo info = CompanionServerMod.buildPlayerInfo(
            "Alex", "uuid-1", "minecraft:overworld", "survival",
            "minecraft:diamond_sword",
            100.5, 64.0, -200.3, 18.5, 45.0f, 12.0f,
            true, false
        );

        assertEquals("Alex", info.name);
        assertEquals("uuid-1", info.uuid);
        assertEquals("minecraft:overworld", info.dim);
        assertEquals("survival", info.gamemode);
        assertEquals("minecraft:diamond_sword", info.mainhand);
        assertEquals(100.5, info.x, 1e-6);
        assertEquals(64.0, info.y, 1e-6);
        assertEquals(-200.3, info.z, 1e-6);
        assertEquals(18.5, info.health, 1e-6);
        assertEquals(45.0f, info.yaw, 1e-4);
        assertEquals(12.0f, info.pitch, 1e-4);
        assertTrue(info.sneaking);
        assertFalse(info.inVehicle);
    }

    @Test
    void buildPlayerInfoNullMainhand() {
        PlayerInfo info = CompanionServerMod.buildPlayerInfo(
            "Alex", "uuid-2", "minecraft:nether", "creative", null,
            0, 0, 0, 20, 0, 0, false, true
        );
        assertNull(info.mainhand);
        assertTrue(info.inVehicle);
    }

    @Test
    void buildEventJoin() {
        EventMessage event = CompanionServerMod.buildEvent(
            "player_join", "Alex", "minecraft:overworld",
            null, null, null, null,
            null, null, null, 12345L);

        assertEquals("player_join", event.kind);
        assertEquals("Alex", event.player);
        assertEquals("minecraft:overworld", event.dim);
        assertNull(event.block);
        assertNull(event.cause);
        assertNull(event.from);
        assertNull(event.to);
        assertNull(event.x);
        assertNull(event.y);
        assertNull(event.z);
        assertEquals(12345L, event.ts.longValue());
    }

    @Test
    void buildEventLeave() {
        EventMessage event = CompanionServerMod.buildEvent(
            "player_leave", "Alex", null,
            null, null, null, null,
            null, null, null, 12345L);

        assertEquals("player_leave", event.kind);
        assertEquals("Alex", event.player);
        assertNull(event.dim);
    }

    @Test
    void buildEventDeath() {
        EventMessage event = CompanionServerMod.buildEvent(
            "player_death", "Alex", "minecraft:overworld",
            null, "fall", null, null,
            100, 64, -200, 12345L);

        assertEquals("player_death", event.kind);
        assertEquals("Alex", event.player);
        assertEquals("fall", event.cause);
        assertEquals(100, event.x.intValue());
        assertEquals(64, event.y.intValue());
        assertEquals(-200, event.z.intValue());
    }

    @Test
    void buildEventDimensionChange() {
        EventMessage event = CompanionServerMod.buildEvent(
            "player_dimension_change", "Alex", null,
            null, null, "minecraft:overworld", "minecraft:the_nether",
            null, null, null, 12345L);

        assertEquals("player_dimension_change", event.kind);
        assertEquals("Alex", event.player);
        assertEquals("minecraft:overworld", event.from);
        assertEquals("minecraft:the_nether", event.to);
    }

    @Test
    void buildEventBlockBreak() {
        EventMessage event = CompanionServerMod.buildEvent(
            "block_break", "Alex", "minecraft:overworld", "minecraft:stone",
            null, null, null,
            10, 20, 30, 12345L);

        assertEquals("block_break", event.kind);
        assertEquals("minecraft:stone", event.block);
        assertEquals(10, event.x.intValue());
        assertEquals(20, event.y.intValue());
        assertEquals(30, event.z.intValue());
    }
}
