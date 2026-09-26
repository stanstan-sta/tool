package com.mindcraft.companion.paper;

import com.mindcraft.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WireParityTest {

    // Mirror of the client read path: VarInt length prefix, then UTF-8 bytes.
    private static String unframe(byte[] buf) {
        int value = 0, pos = 0, idx = 0;
        while (true) {
            byte b = buf[idx++];
            value |= (b & 0x7F) << pos;
            if ((b & 0x80) == 0) break;
            pos += 7;
            if (pos >= 32) throw new IllegalStateException("VarInt too big");
        }
        int len = value;
        assertEquals(buf.length - idx, len, "frame length must equal declared VarInt length");
        return new String(buf, idx, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test void roster_round_trips_and_type_is_peekable() {
        PlayerInfo p = new PlayerInfo();
        p.name = "Steve"; p.uuid = "u"; p.dim = "minecraft:overworld"; p.gamemode = "survival";
        p.mainhand = "minecraft:diamond_pickaxe"; p.x = 1.5; p.y = 64; p.z = -3.2; p.health = 20;
        RosterMessage roster = new RosterMessage();
        roster.players = new ArrayList<>(List.of(p));

        String json = unframe(CompanionPaperPlugin.frame(ProtocolCodec.encode(roster)));
        assertEquals("roster", ProtocolCodec.peekType(json));
        RosterMessage back = ProtocolCodec.decode(json, RosterMessage.class);
        assertEquals(1, back.players.size());
        assertEquals("Steve", back.players.get(0).name);
    }

    @Test void varint_is_multibyte_for_long_payloads() {
        // >127 UTF-8 bytes forces a 2-byte VarInt; this is where writeUTF-style bugs surface.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("abcd");  // 200 chars
        EventMessage ev = new EventMessage();
        ev.kind = "block_break"; ev.player = sb.toString();
        byte[] framed = CompanionPaperPlugin.frame(ProtocolCodec.encode(ev));
        assertEquals("event", ProtocolCodec.peekType(unframe(framed)));
    }
}
