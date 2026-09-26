package com.mindcraft.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

class ProtocolCodecTest {

    @Test
    void roundTripHelloMessage() {
        HelloMessage msg = new HelloMessage();
        msg.modVersion = "1.0.0";
        msg.capabilities = Arrays.asList("roster", "event", "facts");

        String json = ProtocolCodec.encode(msg);
        HelloMessage decoded = ProtocolCodec.decode(json, HelloMessage.class);

        assertNotNull(decoded);
        assertEquals(1, decoded.v);
        assertEquals("hello", decoded.type);
        assertEquals("1.0.0", decoded.modVersion);
        assertEquals(Arrays.asList("roster", "event", "facts"), decoded.capabilities);
    }

    @Test
    void roundTripPlayerInfo() {
        PlayerInfo p = new PlayerInfo();
        p.name = "Player1";
        p.uuid = "00000000-0000-0000-0000-000000000001";
        p.dim = "minecraft:overworld";
        p.gamemode = "survival";
        p.mainhand = "minecraft:diamond_sword";
        p.x = 100.5;
        p.y = 64.0;
        p.z = -200.3;
        p.health = 20.0;
        p.yaw = 45.0f;
        p.pitch = 0.0f;
        p.sneaking = false;
        p.inVehicle = true;

        String json = ProtocolCodec.encode(p);
        PlayerInfo decoded = ProtocolCodec.decode(json, PlayerInfo.class);

        assertNotNull(decoded);
        assertEquals("Player1", decoded.name);
        assertEquals("00000000-0000-0000-0000-000000000001", decoded.uuid);
        assertEquals("minecraft:overworld", decoded.dim);
        assertEquals("survival", decoded.gamemode);
        assertEquals("minecraft:diamond_sword", decoded.mainhand);
        assertEquals(100.5, decoded.x);
        assertEquals(64.0, decoded.y);
        assertEquals(-200.3, decoded.z);
        assertEquals(20.0, decoded.health);
        assertEquals(45.0f, decoded.yaw);
        assertEquals(0.0f, decoded.pitch);
        assertFalse(decoded.sneaking);
        assertTrue(decoded.inVehicle);
    }

    @Test
    void roundTripRosterMessage() {
        PlayerInfo p = new PlayerInfo();
        p.name = "Player1";
        p.uuid = "00000000-0000-0000-0000-000000000001";
        p.dim = "minecraft:overworld";
        p.gamemode = "creative";
        p.x = 0;
        p.y = 64;
        p.z = 0;
        p.health = 20.0;

        RosterMessage msg = new RosterMessage();
        msg.players = Arrays.asList(p);

        String json = ProtocolCodec.encode(msg);
        RosterMessage decoded = ProtocolCodec.decode(json, RosterMessage.class);

        assertNotNull(decoded);
        assertEquals(1, decoded.v);
        assertEquals("roster", decoded.type);
        assertNotNull(decoded.players);
        assertEquals(1, decoded.players.size());
        assertEquals("Player1", decoded.players.get(0).name);
    }

    @Test
    void roundTripEventMessage() {
        EventMessage msg = new EventMessage();
        msg.kind = "block_break";
        msg.player = "Player1";
        msg.dim = "minecraft:overworld";
        msg.block = "minecraft:stone";
        msg.x = 10;
        msg.y = 20;
        msg.z = 30;
        msg.ts = System.currentTimeMillis();

        String json = ProtocolCodec.encode(msg);
        EventMessage decoded = ProtocolCodec.decode(json, EventMessage.class);

        assertNotNull(decoded);
        assertEquals(1, decoded.v);
        assertEquals("event", decoded.type);
        assertEquals("block_break", decoded.kind);
        assertEquals("Player1", decoded.player);
        assertEquals("minecraft:overworld", decoded.dim);
        assertEquals("minecraft:stone", decoded.block);
        assertEquals(10, decoded.x);
        assertEquals(20, decoded.y);
        assertEquals(30, decoded.z);
        assertNotNull(decoded.ts);
    }

    @Test
    void roundTripFactsMessage() {
        FactsMessage msg = new FactsMessage();
        msg.spawn = new int[]{0, 64, 0};
        msg.borderCenterX = 0.0;
        msg.borderCenterZ = 0.0;
        msg.borderSize = 29999984.0;

        String json = ProtocolCodec.encode(msg);
        FactsMessage decoded = ProtocolCodec.decode(json, FactsMessage.class);

        assertNotNull(decoded);
        assertEquals(1, decoded.v);
        assertEquals("facts", decoded.type);
        assertArrayEquals(new int[]{0, 64, 0}, decoded.spawn);
        assertEquals(0.0, decoded.borderCenterX);
        assertEquals(0.0, decoded.borderCenterZ);
        assertEquals(29999984.0, decoded.borderSize);
    }

    @Test
    void peekTypeReturnsDiscriminator() {
        assertEquals("hello", ProtocolCodec.peekType("{\"v\":1,\"type\":\"hello\"}"));
        assertEquals("roster", ProtocolCodec.peekType("{\"v\":1,\"type\":\"roster\"}"));
        assertEquals("event", ProtocolCodec.peekType("{\"v\":1,\"type\":\"event\"}"));
        assertEquals("facts", ProtocolCodec.peekType("{\"v\":1,\"type\":\"facts\"}"));
    }

    @Test
    void peekTypeReturnsNullForInvalidJson() {
        assertNull(ProtocolCodec.peekType("not json"));
    }

    @Test
    void peekTypeReturnsNullForEmptyJson() {
        assertNull(ProtocolCodec.peekType(""));
    }

    @Test
    void decodeReturnsNullForInvalidJson() {
        assertNull(ProtocolCodec.decode("not json", HelloMessage.class));
    }

    @Test
    void decodeReturnsNullForEmptyInput() {
        assertNull(ProtocolCodec.decode("", PlayerInfo.class));
    }

    @Test
    void forwardCompatExtraFieldsIgnored() {
        String json = "{\"v\":1,\"type\":\"hello\",\"modVersion\":\"1.0.0\",\"capabilities\":[],\"unknownField\":\"ignored\"}";
        HelloMessage decoded = ProtocolCodec.decode(json, HelloMessage.class);
        assertNotNull(decoded);
        assertEquals("1.0.0", decoded.modVersion);
    }

    @Test
    void eventMessageOptionalFields() {
        EventMessage msg = new EventMessage();
        msg.kind = "player_join";
        msg.player = "Player1";
        msg.ts = 123456789L;

        String json = ProtocolCodec.encode(msg);
        EventMessage decoded = ProtocolCodec.decode(json, EventMessage.class);

        assertNotNull(decoded);
        assertEquals("player_join", decoded.kind);
        assertEquals("Player1", decoded.player);
        assertNull(decoded.dim);
        assertNull(decoded.block);
        assertNull(decoded.x);
        assertNull(decoded.y);
        assertNull(decoded.z);
        assertEquals(123456789L, decoded.ts.longValue());
    }

    @Test
    void encodeProducesValidJson() {
        HelloMessage msg = new HelloMessage();
        msg.modVersion = "1.0.0";
        String json = ProtocolCodec.encode(msg);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"modVersion\":\"1.0.0\""));
    }
}
