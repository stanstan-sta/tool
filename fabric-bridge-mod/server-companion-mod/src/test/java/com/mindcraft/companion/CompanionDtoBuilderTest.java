package com.mindcraft.companion;

import com.mindcraft.protocol.EventMessage;
import com.mindcraft.protocol.FactsMessage;
import com.mindcraft.protocol.PlayerInfo;
import com.mindcraft.protocol.ProtocolCodec;
import com.mindcraft.protocol.RosterMessage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

class CompanionDtoBuilderTest {

    @Test
    void buildPlayerInfoAllFields() {
        PlayerInfo info = CompanionServerMod.buildPlayerInfo(
            "Player1", "00000000-0000-0000-0000-000000000001",
            "minecraft:overworld", "survival", "minecraft:diamond_sword",
            100.5, 64.0, -200.3, 20.0, 45.0f, 0.0f,
            false, true
        );

        assertEquals("Player1", info.name);
        assertEquals("00000000-0000-0000-0000-000000000001", info.uuid);
        assertEquals("minecraft:overworld", info.dim);
        assertEquals("survival", info.gamemode);
        assertEquals("minecraft:diamond_sword", info.mainhand);
        assertEquals(100.5, info.x);
        assertEquals(64.0, info.y);
        assertEquals(-200.3, info.z);
        assertEquals(20.0, info.health);
        assertEquals(45.0f, info.yaw);
        assertEquals(0.0f, info.pitch);
        assertFalse(info.sneaking);
        assertTrue(info.inVehicle);
    }

    @Test
    void buildPlayerInfoMinimalFields() {
        PlayerInfo info = CompanionServerMod.buildPlayerInfo(
            "Player2", "00000000-0000-0000-0000-000000000002",
            "minecraft:overworld", "creative", "",
            0.0, 64.0, 0.0, 20.0, 0.0f, 0.0f,
            false, false
        );

        assertEquals("Player2", info.name);
    }

    @Test
    void buildEventBlockBreak() {
        long ts = System.currentTimeMillis();
        EventMessage event = CompanionServerMod.buildEvent(
            "block_break", "Player1", "minecraft:overworld",
            "block.minecraft.stone", null, null, null,
            10, 20, 30, ts
        );

        assertEquals("block_break", event.kind);
        assertEquals("Player1", event.player);
        assertEquals("minecraft:overworld", event.dim);
        assertEquals("block.minecraft.stone", event.block);
        assertNull(event.cause);
        assertNull(event.from);
        assertNull(event.to);
        assertEquals(10, event.x);
        assertEquals(20, event.y);
        assertEquals(30, event.z);
        assertEquals(ts, event.ts);
    }

    @Test
    void buildEventPlayerDeath() {
        long ts = System.currentTimeMillis();
        EventMessage event = CompanionServerMod.buildEvent(
            "player_death", "Player1", null, null,
            "fall", null, null,
            null, null, null, ts
        );

        assertEquals("player_death", event.kind);
        assertEquals("Player1", event.player);
        assertNull(event.dim);
        assertNull(event.block);
        assertEquals("fall", event.cause);
        assertNull(event.x);
        assertNull(event.y);
        assertNull(event.z);
        assertEquals(ts, event.ts);
    }

    @Test
    void buildEventDimensionChange() {
        long ts = System.currentTimeMillis();
        EventMessage event = CompanionServerMod.buildEvent(
            "player_dimension_change", "Player1", null, null,
            null, "minecraft:overworld", "minecraft:the_nether",
            null, null, null, ts
        );

        assertEquals("player_dimension_change", event.kind);
        assertEquals("Player1", event.player);
        assertEquals("minecraft:overworld", event.from);
        assertEquals("minecraft:the_nether", event.to);
    }

    @Test
    void buildFactsAllFields() {
        FactsMessage facts = CompanionServerMod.buildFacts(
            new int[]{0, 64, 0}, 0.0, 0.0, 29999984.0
        );

        assertArrayEquals(new int[]{0, 64, 0}, facts.spawn);
        assertEquals(0.0, facts.borderCenterX);
        assertEquals(0.0, facts.borderCenterZ);
        assertEquals(29999984.0, facts.borderSize);
    }

    @Test
    void buildFactsNullBorder() {
        FactsMessage facts = CompanionServerMod.buildFacts(
            new int[]{-100, 64, 200}, null, null, null
        );

        assertArrayEquals(new int[]{-100, 64, 200}, facts.spawn);
        assertNull(facts.borderCenterX);
        assertNull(facts.borderCenterZ);
        assertNull(facts.borderSize);
    }

    @Test
    void rosterMessageRoundTripThroughCodec() {
        PlayerInfo p1 = CompanionServerMod.buildPlayerInfo(
            "Player1", "uuid-1", "minecraft:overworld", "survival", "minecraft:stone_sword",
            10, 20, 30, 20.0, 0, 0, false, false);
        PlayerInfo p2 = CompanionServerMod.buildPlayerInfo(
            "Player2", "uuid-2", "minecraft:the_nether", "spectator", "",
            -100, 40, 300, 10.0, 180, 45, true, false);

        RosterMessage roster = new RosterMessage();
        roster.players = Arrays.asList(p1, p2);

        String json = ProtocolCodec.encode(roster);
        RosterMessage decoded = ProtocolCodec.decode(json, RosterMessage.class);

        assertNotNull(decoded);
        assertEquals(2, decoded.players.size());
        assertEquals("Player1", decoded.players.get(0).name);
        assertEquals("Player2", decoded.players.get(1).name);
        assertEquals("minecraft:the_nether", decoded.players.get(1).dim);
        assertTrue(decoded.players.get(1).sneaking);
        assertFalse(decoded.players.get(1).inVehicle);
    }
}
