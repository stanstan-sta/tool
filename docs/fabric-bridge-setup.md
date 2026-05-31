# Fabric Bridge Setup Guide

This guide explains **two integration levels** for using advanced Minecraft capabilities with the LLM agent:

---

## Option A — Pure Mineflayer (Recommended for most users)

Uses `@miner-org/mineflayer-baritone` — a pure JavaScript port of Baritone running
**inside** the existing Mineflayer bot. No Java, no Fabric required.

### What you get
| Feature | Available |
|---------|-----------|
| Advanced pathfinding (A\*) | ✅ |
| Parkour jumps | ✅ |
| Block breaking during path | ✅ |
| Swimming / ladder climbing | ✅ |
| ESP block scanning (uses chunk data) | ✅ `!espLocate`, `!espGoto` |
| Visual ESP overlay (through walls) | ❌ |
| X-ray / full-bright texture pack | ❌ |

### Setup

1. Enable in `settings.js`:
   ```js
   "use_baritone": true
   ```

2. Install the package (already added to `package.json`):
   ```bash
   npm install
   ```

3. Start the bot normally:
   ```bash
   node main.js
   ```

### New commands

| Command | Description |
|---------|-------------|
| `!baritoneGoto x y z` | Navigate with parkour support |
| `!baritoneCancel` | Cancel current navigation |
| `!baritoneFollow playerName` | Navigate to player's position |
| `!baritoneExplore radius` | Navigate to a random point |
| `!baritioneMine blockType count` | ESP-find + navigate + dig |
| `!espLocate blockType range count` | List nearest blocks (chunk-data ESP) |
| `!espGoto blockType range` | ESP-find + navigate to nearest block |

### Why `!espLocate` is "ESP"

Mineflayer runs at the **protocol level** — it receives the same raw chunk packets as a
real client. Every block in every loaded chunk is accessible via `bot.findBlocks()`.
This means the bot can "see" diamond ore, ancient debris, mobs, or any block type
**anywhere in loaded chunks**, regardless of walls or darkness, exactly like a
visual ESP overlay would.

---

## Option B — Full Fabric Modded Client (Advanced)

For users who want a **real Minecraft client** with visual ESP, X-ray, full-bright,
and other utility mods while still having the LLM control it.

### Architecture

```
┌─────────────────────────────────┐      HTTP / WebSocket
│  stanstan-sta/tool (Node.js)    │ ←──────────────────── ┌──────────────────────────────┐
│  LLM (Ollama / any model)       │                        │  Fabric Client (Java)        │
│  BridgeAgent                    │ ───────────────────→   │  + Baritone mod              │
│  Parses COMMAND: lines          │   POST /command        │  + ESP/X-ray mods (optional) │
│  Injects state into LLM context │ ←──────────────────── │  + Mindcraft Bridge Mod      │
│                                 │   GET  /state          │    (HTTP server on :8765)    │
└─────────────────────────────────┘                        └──────────────────────────────┘
```

### Setup (macOS + Fabric 1.21.x)

#### Step 1 — Install Java 21
```bash
brew install --cask temurin@21
```

#### Step 2 — Install Fabric Loader in your launcher
1. Download Fabric Installer from https://fabricmc.net/use/installer/
2. Open the installer, select Minecraft **1.21.11**, click Install
3. Launch Minecraft once with the Fabric profile to create the mods folder

#### Step 3 — Install Baritone for Fabric
1. Use the modified Baritone checkout that works with this bridge.
  - On Windows, the forked source tree you referenced is `F:\Baritone_Dev\baritone`.
  - Build or copy the matching Fabric jar from that fork.
2. Place it in `~/Library/Application Support/minecraft/mods/`

#### Step 4 — Build and install the Mindcraft Bridge Mod

The bridge mod source lives in `fabric-bridge-mod/` in this repository.

```bash
cd fabric-bridge-mod
./gradlew build
cp build/libs/mindcraft-bridge-*.jar \
   ~/Library/Application\ Support/minecraft/mods/
```

#### Step 5 — Enable Fabric runtime in settings.js
```js
"launch_mode": "fabric_ui", // or "fabric_headless"
"bridge_mode": true,        // legacy setting; launch_mode is preferred
"bridge_url": "http://localhost:8765",
"bridge_structured_output": true
```

And set up a bridge profile. Copy `profiles/fabric_bridge.json` and edit as needed:
```json
{
  "name": "Andy",
  "model": "ollama/qwen2.5:7b",
  "personality": "You are Andy, a helpful Minecraft assistant.",
  "conversing": "..."
}
```

Add `./profiles/fabric_bridge.json` to the `profiles` array in `settings.js`.

#### Step 6 — Launch Minecraft, then start the agent
```bash
# 1. Launch Minecraft with the Fabric profile (bridge mod starts HTTP server on :8765)
# 2. In another terminal:
node main.js
```

### Bridge Mod API

The mod exposes HTTP endpoints on `localhost:8765`:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/ping` | Health check |
| `GET` | `/state` | Player state (position, health, inventory, nearby entities, queued chat) |
| `POST` | `/command` | Execute a command |
| `POST` | `/action` | Execute typed action payload (`move`, `mine`, `follow`, `interact`, `cancel`, `raw_command`) |
| `GET` | `/capabilities` | Provider and feature metadata (`baritone_native`, `baritone_chat`, typed-action support) |

`/state` supports `?since=<seq>` for lightweight delta polling. When unchanged it returns:
```json
{"connected":true,"seq":42,"unchanged":true,"chat":[]}
```

#### Command types

| Prefix | Example | Handled by |
|--------|---------|------------|
| `#` | `#goto 100 64 -200` | Baritone (chat prefix intercept) |
| `/` | `/time set day` | Minecraft command |
| `chat:` | `chat: Hello!` | Public chat message |

#### State response example
```json
{
  "connected": true,
  "x": 100, "y": 64, "z": -200,
  "health": 20.0, "hunger": 18,
  "dimension": "minecraft:overworld",
  "gameMode": "SURVIVAL",
  "inventory": [
    { "slot": 0, "item": "minecraft:diamond_pickaxe", "count": 1 }
  ],
  "nearby_players": ["Steve"],
  "chat": ["<Steve> Hey Andy, go mine some iron"]
}
```

### LLM output format (bridge mode)

The system prompt in `profiles/fabric_bridge.json` now uses structured output:

```json
{
  "reply": "I'll head underground to mine iron.",
  "actions": [
    {"type":"move","x":45,"y":12,"z":-89},
    {"type":"mine","target":"iron_ore","count":16}
  ]
}
```

Only `reply` is shown in WebUI chat output. Game operations are executed from `actions`,
which prevents commands from leaking into user-facing chat.

---

## ⚠️ Safety Notes

- **Single-player or private LAN only.** Using Baritone or any pathfinding automation
  on public servers violates most server rules and anti-cheat systems will flag it.
- The Fabric Bridge Mod opens an **HTTP server on localhost only** (not exposed to the
  network). Never run it on a machine directly connected to the internet without a
  firewall.
- X-ray/ESP mods give unfair advantages. Use only on your own worlds or servers where
  all players consent.
