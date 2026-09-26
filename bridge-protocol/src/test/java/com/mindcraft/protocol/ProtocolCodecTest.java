package com.mindcraft.protocol;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecTest {

    @Test
    void roundTripHelloMessage() {
        HelloMessage msg = new HelloMessage();
        msg.modVersion = "1.0.0";
        msg.capabilities = List.of("roster", "event", "facts");

        String json = ProtocolCodec.encode(msg);
        HelloMessage decoded = ProtocolCodec.decode(json, HelloMessage.class);

        assertNotNull(decoded);
        assertEquals(msg.v, decoded.v);
        assertEquals(msg.type, decoded.type);
        assertEquals(msg.modVersion, decoded.modVersion);
        assertEquals(msg.capabilities, decoded.capabilities);
    }

    @Test
    void roundTripRosterMessage() {
        RosterMessage msg = new RosterMessage();
        PlayerInfo p = new PlayerInfo();
        p.name = "Alex"; p.uuid = "uuid-1"; p.dim = "minecraft:overworld";
        p.gamemode = "survival"; p.mainhand = "minecraft:diamond_sword";
        p.x = 100.5; p.y = 64; p.z = -200.3; p.health = 18.5;
        p.yaw = 45.0f; p.pitch = 12.0f; p.sneaking = true; p.inVehicle = false;
        msg.players = List.of(p);

        String json = ProtocolCodec.encode(msg);
        RosterMessage decoded = ProtocolCodec.decode(json, RosterMessage.class);

        assertNotNull(decoded);
        assertEquals(1, decoded.players.size());
        PlayerInfo dp = decoded.players.get(0);
        assertEquals(p.name, dp.name);
        assertEquals(p.uuid, dp.uuid);
        assertEquals(p.dim, dp.dim);
        assertEquals(p.gamemode, dp.gamemode);
        assertEquals(p.mainhand, dp.mainhand);
        assertEquals(p.x, dp.x, 1e-6);
        assertEquals(p.y, dp.y, 1e-6);
        assertEquals(p.z, dp.z, 1e-6);
        assertEquals(p.health, dp.health, 1e-6);
        assertEquals(p.yaw, dp.yaw, 1e-4);
        assertEquals(p.pitch, dp.pitch, 1e-4);
        assertEquals(p.sneaking, dp.sneaking);
        assertEquals(p.inVehicle, dp.inVehicle);
    }

    @Test
    void roundTripEventMessage() {
        EventMessage msg = new EventMessage();
        msg.kind = "player_death";
        msg.player = "Alex";
        msg.dim = "minecraft:overworld";
        msg.cause = "fall";
        msg.x = 100; msg.y = 64; msg.z = -200;
        msg.ts = System.currentTimeMillis();

        String json = ProtocolCodec.encode(msg);
        EventMessage decoded = ProtocolCodec.decode(json, EventMessage.class);

        assertNotNull(decoded);
        assertEquals(msg.kind, decoded.kind);
        assertEquals(msg.player, decoded.player);
        assertEquals(msg.cause, decoded.cause);
        assertEquals(msg.x, decoded.x);
        assertEquals(msg.y, decoded.y);
        assertEquals(msg.z, decoded.z);
        assertNotNull(decoded.ts);
    }

    @Test
    void roundTripFactsMessage() {
        FactsMessage msg = new FactsMessage();
        msg.spawn = new int[]{0, 64, 0};
        msg.borderCenterX = 0.0;
        msg.borderCenterZ = 0.0;
        msg.borderSize = 60000000.0;

        String json = ProtocolCodec.encode(msg);
        FactsMessage decoded = ProtocolCodec.decode(json, FactsMessage.class);

        assertNotNull(decoded);
        assertArrayEquals(msg.spawn, decoded.spawn);
        assertEquals(msg.borderCenterX, decoded.borderCenterX, 1e-6);
        assertEquals(msg.borderCenterZ, decoded.borderCenterZ, 1e-6);
        assertEquals(msg.borderSize, decoded.borderSize, 1e-6);
    }

    @Test
    void peekTypeReturnsCorrectDiscriminator() {
        HelloMessage hello = new HelloMessage();
        hello.modVersion = "1.0.0";
        assertEquals("hello", ProtocolCodec.peekType(ProtocolCodec.encode(hello)));

        RosterMessage roster = new RosterMessage();
        roster.players = List.of();
        assertEquals("roster", ProtocolCodec.peekType(ProtocolCodec.encode(roster)));

        EventMessage event = new EventMessage();
        event.kind = "block_break";
        assertEquals("event", ProtocolCodec.peekType(ProtocolCodec.encode(event)));

        FactsMessage facts = new FactsMessage();
        facts.spawn = new int[]{0, 0, 0};
        assertEquals("facts", ProtocolCodec.peekType(ProtocolCodec.encode(facts)));
    }

    @Test
    void decodeInvalidJsonReturnsNull() {
        assertNull(ProtocolCodec.decode("not json", HelloMessage.class));
        assertNull(ProtocolCodec.peekType("{"));
    }

    @Test
    void forwardCompatibilityExtraFieldsIgnored() {
        String json = "{\"v\":1,\"type\":\"hello\",\"modVersion\":\"1.0.0\",\"unknownField\":\"ignored\"}";
        HelloMessage decoded = ProtocolCodec.decode(json, HelloMessage.class);
        assertNotNull(decoded);
        assertEquals("1.0.0", decoded.modVersion);
    }
}
