# Handoff: Port the Server-Companion mod to a Paper plugin

**Audience:** an implementer (small model) who will create a new Bukkit/Paper plugin that
reproduces the Fabric `server-companion-mod` over Paper's plugin-messaging API.

**Why:** the existing `tool/server-companion-mod/` is a **Fabric** mod and **cannot load on a
Paper server**. The client (`tool/fabric-bridge-mod/`) is Fabric and already listens on the
`mindcraft:companion` channel. We only need a **new server-side sender** that speaks the same
wire format. The client side and the JSON protocol do **not** change.

---

## 0. The one rule that matters: byte-for-byte wire parity

The Fabric client receives messages with this codec (do not change it; it is the contract):

```java
// tool/fabric-bridge-mod/.../CompanionPayload.java
public static final CustomPayload.Id<CompanionPayload> ID =
    new CustomPayload.Id<>(Identifier.of("mindcraft", "companion"));   // channel = "mindcraft:companion"
public static final PacketCodec<PacketByteBuf, CompanionPayload> CODEC =
    PacketCodec.tuple(PacketCodecs.string(262144), CompanionPayload::json, CompanionPayload::new);
```

`PacketCodecs.string(262144)` reads a string from the buffer as:

```
[ VarInt: number of UTF-8 bytes ]  [ that many UTF-8 bytes ]
```

So **every plugin message you send must be exactly**: `VarInt(len) + UTF-8(jsonString)`.

> 🚨 **The #1 way to fail this port:** sending the raw JSON bytes with no length prefix, or
> using `DataOutputStream.writeUTF` (which writes a **2-byte big-endian short** length, not a
> Minecraft VarInt). Either corrupts the frame and the client silently drops it. Use the exact
> `frame()` / `writeVarInt()` code in §6.

Everything else (which events, which fields) is "nice to have parity"; **the framing is non-negotiable.**

---

## 1. The wire protocol (already defined — reuse it verbatim)

The message schema lives in `tool/bridge-protocol/` — **pure Java + Gson, zero Minecraft deps.**
You will **copy these 8 files** into the new plugin (see §3) so your JSON is produced by the
exact same serializer the Fabric server mod uses. `VERSION = 1`, frozen.

Channel constants (`BridgeProtocol.java`): namespace `mindcraft`, path `companion`, version `1`.

Four message types, each a top-level JSON object with a `"type"` discriminator the client peeks at:

| `type`   | Class           | Client stores it as           | When you send it |
|----------|-----------------|-------------------------------|------------------|
| `hello`  | `HelloMessage`  | `server_companion`            | once, on an allowlisted player's join |
| `facts`  | `FactsMessage`  | `server_facts`                | once, on join (if `send-facts`) |
| `roster` | `RosterMessage` | `server_players` (array only) | every `broadcast-interval-ticks` |
| `event`  | `EventMessage`  | appended to `server_events`   | on each game event (if `send-events`) |

Exact fields (from `tool/bridge-protocol/...`):

```jsonc
// hello
{ "v":1, "type":"hello", "modVersion":"1.0.0", "capabilities":["roster","event","facts"] }

// facts
{ "v":1, "type":"facts", "spawn":[x,y,z], "borderCenterX":0.0, "borderCenterZ":0.0, "borderSize":59999968.0 }

// roster  (client extracts ONLY the players array)
{ "v":1, "type":"roster", "players":[ PlayerInfo, ... ] }

// PlayerInfo (nested in roster.players; NO "type" field)
{ "name":"Steve","uuid":"...","dim":"minecraft:overworld","gamemode":"survival",
  "mainhand":"minecraft:diamond_pickaxe","x":1.5,"y":64.0,"z":-3.2,"health":20.0,
  "yaw":90.0,"pitch":0.0,"sneaking":false,"inVehicle":false }

// event  (kind varies; null fields are omitted/null per Gson defaults)
{ "v":1, "type":"event", "kind":"block_break", "player":"Steve", "dim":"minecraft:overworld",
  "block":"minecraft:stone", "cause":null, "from":null, "to":null, "x":10,"y":63,"z":-4, "ts":1717660000000 }
```

Send **one plugin message per protocol message** (do not batch multiple JSON objects into one frame).

---

## 2. Behavior to reproduce (from `CompanionServerMod.java` + `CompanionConfig.java`)

- **Allowlist = who RECEIVES.** Default **empty list = send to nobody** (privacy). The roster
  always contains *all* online players, but is only delivered to allowlisted recipients.
- On an **allowlisted player's join**: send `hello`, then `facts` (if enabled). Always (if events
  enabled) broadcast a `player_join` event.
- **Roster**: every interval, build a `roster` of all online players, deliver to all online
  allowlisted players. Default interval `1500 ms` → **30 ticks**.
- **Events** (if enabled), broadcast to all online allowlisted players:
  | kind | trigger | extra fields |
  |------|---------|--------------|
  | `player_join` | join | `dim` |
  | `player_leave` | quit | (name + ts only) |
  | `block_break` | block broken | `dim`, `block`, `x/y/z` (block coords) |
  | `player_death` | death | `dim`, `cause`, `x/y/z` |
  | `player_dimension_change` | changed world | `from`, `to` |
- All work runs on the **main server thread** (Bukkit API is not thread-safe; `sendPluginMessage`
  must be main-thread). The scheduler task and event handlers already run there.

Config defaults to mirror: `enabled=true`, `broadcast-interval-ticks=30`, `send-roster/events/facts=true`,
`player-allowlist=[]`.

---

## 3. Create the project skeleton

Create `tool/server-companion-paper/` with this layout:

```
server-companion-paper/
├─ settings.gradle
├─ build.gradle
├─ gradlew, gradlew.bat, gradle/wrapper/*      ← COPY from ../server-companion-mod
└─ src/main/
   ├─ java/com/mindcraft/protocol/             ← COPY all 8 files from ../bridge-protocol (unchanged)
   │    BridgeProtocol.java  Envelope.java  EventMessage.java  FactsMessage.java
   │    HelloMessage.java    PlayerInfo.java RosterMessage.java ProtocolCodec.java
   ├─ java/com/mindcraft/companion/paper/
   │    CompanionPaperPlugin.java              ← §6 (full source below)
   └─ resources/
        plugin.yml                             ← §5
        config.yml                             ← §5
└─ src/test/java/com/mindcraft/companion/paper/
        WireParityTest.java                    ← §7 (offline proof of framing)
```

**Copy steps (deterministic — do exactly this):**
1. Copy the 8 `*.java` files from `tool/bridge-protocol/src/main/java/com/mindcraft/protocol/`
   into `tool/server-companion-paper/src/main/java/com/mindcraft/protocol/`. **Do not edit them.**
2. Copy `gradlew`, `gradlew.bat`, and the `gradle/wrapper/` folder from `tool/server-companion-mod/`.

> Rationale for copying the protocol rather than a Gradle project-dependency: it keeps this a
> single self-contained plugin jar with **no Shadow/relocation step**. `ProtocolCodec` uses Gson,
> which **Paper bundles and exposes to plugins at runtime** — so Gson is `compileOnly`, never shaded.

---

## 4. `settings.gradle`

```groovy
rootProject.name = 'mindcraft-server-companion-paper'
```

## 4b. `build.gradle`

```groovy
plugins {
    id 'java'
}

group = 'com.mindcraft'
version = '1.0.0'

repositories {
    mavenCentral()
    maven { url = 'https://repo.papermc.io/repository/maven-public/' }
}

dependencies {
    // Set this to your server's EXACT version. Confirm the artifact exists by browsing
    // https://repo.papermc.io/ ; within a minor the API is compatible, but matching is safest.
    compileOnly 'io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT'
    // Gson is provided by the Paper/Spigot runtime — compile against it, do NOT bundle it.
    compileOnly 'com.google.code.gson:gson:2.10.1'

    // The test calls a static method on CompanionPaperPlugin, so the JVM must resolve that
    // class's supertypes (JavaPlugin/Listener). compileOnly deps do NOT propagate to the test
    // classpath, so paper-api and gson must be repeated as testImplementation here.
    testImplementation 'io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT'
    testImplementation 'com.google.code.gson:gson:2.10.1'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testRuntimeOnly  'org.junit.platform:junit-platform-launcher:1.10.2'
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }      // Paper 1.21 requires Java 21
}

test { useJUnitPlatform() }

jar { archiveBaseName = 'mindcraft-server-companion-paper' }
```

---

## 5. Resources

`src/main/resources/plugin.yml`:
```yaml
name: MindcraftServerCompanion
version: 1.0.0
main: com.mindcraft.companion.paper.CompanionPaperPlugin
api-version: '1.21'
author: Mindcraft
description: Streams server-authoritative roster/events/facts to the Mindcraft Fabric client.
folia-supported: false
```

`src/main/resources/config.yml`:
```yaml
# Mindcraft Server Companion (Paper)
enabled: true
# Roster broadcast period in server ticks (20 ticks = 1 second). 30 = 1.5s, matching the Fabric mod.
broadcast-interval-ticks: 30
send-roster: true
send-events: true
send-facts: true
# PRIVACY: empty list = send to NOBODY. Add the exact in-game username(s) of whoever
# is running the Mindcraft Fabric client.
player-allowlist: []
```

---

## 6. `CompanionPaperPlugin.java` (full, paste-ready)

```java
package com.mindcraft.companion.paper;

import com.mindcraft.protocol.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CompanionPaperPlugin extends JavaPlugin implements Listener {

    // "mindcraft:companion" — must equal the Fabric client's channel id.
    public static final String CHANNEL = BridgeProtocol.CHANNEL_NAMESPACE + ":" + BridgeProtocol.CHANNEL_PATH;

    private boolean enabled, sendRoster, sendEvents, sendFacts;
    private long broadcastIntervalTicks;
    private final List<String> allowlist = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCfg();

        // Outgoing (server -> client) channel registration is mandatory before sendPluginMessage.
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        if (!enabled) {
            getLogger().info("Disabled in config; listeners not registered.");
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (sendRoster) {
            Bukkit.getScheduler().runTaskTimer(this, this::broadcastRoster,
                    broadcastIntervalTicks, broadcastIntervalTicks);
        }
        getLogger().info("Mindcraft Server Companion (Paper) enabled on channel " + CHANNEL);
    }

    private void loadCfg() {
        reloadConfig();
        enabled    = getConfig().getBoolean("enabled", true);
        sendRoster = getConfig().getBoolean("send-roster", true);
        sendEvents = getConfig().getBoolean("send-events", true);
        sendFacts  = getConfig().getBoolean("send-facts", true);
        broadcastIntervalTicks = Math.max(1L, getConfig().getLong("broadcast-interval-ticks", 30L));
        allowlist.clear();
        for (String s : getConfig().getStringList("player-allowlist")) {
            if (s != null && !s.isEmpty()) allowlist.add(s.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isAllowlisted(Player p) {
        if (allowlist.isEmpty()) return false;                       // privacy default: nobody
        return allowlist.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    private List<Player> recipients() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (isAllowlisted(p)) out.add(p);
        return out;
    }

    // ---------------- listeners ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isAllowlisted(p)) {
            sendTo(p, buildHello());
            if (sendFacts) sendTo(p, buildFacts());
        }
        if (sendEvents) {
            EventMessage ev = newEvent("player_join", p.getName());
            ev.dim = dimOf(p.getWorld());
            broadcastEvent(ev);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (sendEvents) broadcastEvent(newEvent("player_leave", e.getPlayer().getName()));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!sendEvents) return;
        EventMessage ev = newEvent("block_break", e.getPlayer().getName());
        ev.dim   = dimOf(e.getPlayer().getWorld());
        ev.block = e.getBlock().getType().getKey().toString();       // "minecraft:stone"
        ev.x = e.getBlock().getX(); ev.y = e.getBlock().getY(); ev.z = e.getBlock().getZ();
        broadcastEvent(ev);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!sendEvents) return;
        Player p = e.getEntity();
        EventMessage ev = newEvent("player_death", p.getName());
        ev.dim = dimOf(p.getWorld());
        if (p.getLastDamageCause() != null) {
            ev.cause = p.getLastDamageCause().getCause().name().toLowerCase(Locale.ROOT);
        }
        Location l = p.getLocation();
        ev.x = l.getBlockX(); ev.y = l.getBlockY(); ev.z = l.getBlockZ();
        broadcastEvent(ev);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (!sendEvents) return;
        Player p = e.getPlayer();
        EventMessage ev = newEvent("player_dimension_change", p.getName());
        ev.from = dimOf(e.getFrom());
        ev.to   = dimOf(p.getWorld());
        broadcastEvent(ev);
    }

    // ---------------- builders ----------------

    private HelloMessage buildHello() {
        HelloMessage h = new HelloMessage();
        h.modVersion = "1.0.0";
        h.capabilities = List.of("roster", "event", "facts");
        return h;
    }

    private FactsMessage buildFacts() {
        World w = overworld();
        FactsMessage f = new FactsMessage();
        Location s = w.getSpawnLocation();
        f.spawn = new int[]{ s.getBlockX(), s.getBlockY(), s.getBlockZ() };
        WorldBorder b = w.getWorldBorder();
        f.borderCenterX = b.getCenter().getX();
        f.borderCenterZ = b.getCenter().getZ();
        f.borderSize    = b.getSize();
        return f;
    }

    private void broadcastRoster() {
        List<Player> rec = recipients();
        if (rec.isEmpty()) return;                                   // skip work when nobody listens
        RosterMessage roster = new RosterMessage();
        roster.players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) roster.players.add(playerInfo(p));
        byte[] framed = frame(ProtocolCodec.encode(roster));
        for (Player r : rec) r.sendPluginMessage(this, CHANNEL, framed);
    }

    private PlayerInfo playerInfo(Player p) {
        PlayerInfo i = new PlayerInfo();
        i.name = p.getName();
        i.uuid = p.getUniqueId().toString();
        i.dim  = dimOf(p.getWorld());
        i.gamemode = p.getGameMode().name().toLowerCase(Locale.ROOT);   // "survival"
        ItemStack hand = p.getInventory().getItemInMainHand();
        i.mainhand = (hand == null || hand.getType().isAir())
                ? "minecraft:air" : hand.getType().getKey().toString();
        Location l = p.getLocation();
        i.x = l.getX(); i.y = l.getY(); i.z = l.getZ();
        i.health = p.getHealth();
        i.yaw = l.getYaw(); i.pitch = l.getPitch();
        i.sneaking  = p.isSneaking();
        i.inVehicle = p.isInsideVehicle();
        return i;
    }

    private EventMessage newEvent(String kind, String player) {
        EventMessage ev = new EventMessage();
        ev.kind = kind; ev.player = player; ev.ts = System.currentTimeMillis();
        return ev;
    }

    // ---------------- send + framing ----------------

    private void broadcastEvent(EventMessage ev) {
        byte[] framed = frame(ProtocolCodec.encode(ev));
        for (Player r : recipients()) r.sendPluginMessage(this, CHANNEL, framed);
    }

    private void sendTo(Player p, Object message) {
        p.sendPluginMessage(this, CHANNEL, frame(ProtocolCodec.encode(message)));
    }

    /**
     * Frame a JSON string EXACTLY as Minecraft's PacketByteBuf string codec expects:
     * VarInt(utf8ByteLength) followed by the UTF-8 bytes. The Fabric client decodes this with
     * PacketCodecs.string(262144). DO NOT use DataOutputStream.writeUTF (that is a 2-byte short).
     */
    static byte[] frame(String json) {
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(utf8.length + 5);
        writeVarInt(out, utf8.length);
        out.write(utf8, 0, utf8.length);
        return out.toByteArray();
    }

    /** Standard Minecraft VarInt: 7 data bits per byte, high bit (0x80) = "more bytes follow". */
    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    // ---------------- helpers ----------------

    private World overworld() {
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) return w;
        return Bukkit.getWorlds().get(0);
    }

    private static String dimOf(World w) {
        switch (w.getEnvironment()) {
            case NORMAL:  return "minecraft:overworld";
            case NETHER:  return "minecraft:the_nether";
            case THE_END: return "minecraft:the_end";
            default:      return w.getKey().toString();
        }
    }
}
```

---

## 7. `WireParityTest.java` — prove the framing offline (no server needed)

This is the gate that catches the VarInt mistake before you ever start a server. It frames a
message exactly as the plugin does, then **decodes it the way the Fabric client would** (read a
VarInt, read that many bytes, parse JSON, peek `type`).

```java
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
```

---

## 8. Build & verify

```powershell
cd f:\tool_test\tool\server-companion-paper
.\gradlew.bat test            # MUST pass — proves wire framing before any live test
.\gradlew.bat build           # produces build\libs\mindcraft-server-companion-paper-1.0.0.jar
```

**Live verification (requires your Paper server + the Fabric client):**
1. Put the jar in the Paper server's `plugins/` folder; start the server once so `config.yml`
   generates under `plugins/MindcraftServerCompanion/`.
2. Add your in-game name to `player-allowlist`, then `/reload confirm` or restart.
3. On the **client** side, enable the channel: `BridgeConfig.companionChannelEnabled = true`
   and the Node flag `bridge_server_data_enabled = true` (see the Fabric client config).
4. Join the server with the Fabric client. Confirm in the agent's `/state` that **`server_players`**
   populates with your live coordinates, and `server_facts` / `server_companion` are non-null.
   This is the real success signal — the same one the Fabric server mod targets.

---

## 9. Definition of done

- [ ] `server-companion-paper/` exists with the layout in §3; the 8 protocol files are byte-identical copies.
- [ ] `gradlew test` passes (`WireParityTest`).
- [ ] `gradlew build` produces `mindcraft-server-companion-paper-*.jar`.
- [ ] Plugin loads on the Paper server with no errors; `config.yml` generates with the §5 defaults.
- [ ] With your name allowlisted + client channel enabled, `/state.server_players` shows your live coords.

---

## 10. Pitfalls checklist (read before you start)

1. **VarInt framing** — use §6 `frame()`. Never `writeUTF`, never raw bytes. (§7 catches this.)
2. **Channel string** — must be lowercase `mindcraft:companion`; register it as **outgoing** in `onEnable`.
3. **Version match** — the Paper server's Minecraft version must equal the Fabric client's (**1.21.11**),
   or the connection/protocol won't line up. Set `paper-api` coordinate to match.
4. **Allowlist empty = nobody.** Out of the box it sends to no one by design; you must add a name.
5. **Main thread only.** Don't call `sendPluginMessage` from async tasks. The scheduler timer and
   event handlers are already main-thread; keep it that way.
6. **Gson at runtime** — provided by Paper, so `compileOnly`. If you ever see
   `NoClassDefFoundError: com.google.gson.Gson` on an unusual fork, add the Shadow plugin and
   bundle Gson (only then). On standard Paper/Spigot this will not happen.
7. **One message per frame** — send `hello`, `facts`, each `event`, and each `roster` as separate
   `sendPluginMessage` calls. The client decodes one JSON object per payload.
8. **Folia** — this targets Paper. On Folia the global scheduler differs; out of scope here.

---

## 11. What you are NOT changing

- `tool/bridge-protocol/` (source of the copied DTOs) — leave as is.
- `tool/fabric-bridge-mod/` (the client receiver `CompanionChannel`/`CompanionPayload`) — **no changes**;
  a Bukkit plugin message and a Fabric custom payload arrive identically on the client.
- `tool/src/bridge/server_data.js` and the `bridge_server_data_enabled` consumer — unchanged.
- `tool/server-companion-mod/` (the Fabric mod) — leave in place for anyone running a Fabric server.
```
