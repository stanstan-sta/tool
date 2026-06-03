# Minecraft Fabric Bridge Agent

This project runs an LLM-controlled Minecraft client through a Fabric bridge mod.

The current setup is **Fabric Bridge**:

- Minecraft is launched by Lunar, Prism, MultiMC, or the normal Minecraft Launcher.
- The Fabric client loads the bridge mod and custom Baritone jar.
- This Node.js app talks to that client over `http://localhost:8765`.
- The web console runs on `http://localhost:8080` by default.

This repo still has old Mindcraft and Mineflayer names in some paths, packages, and source files because it started from that codebase. Those names are legacy. The setup below is the supported path.

Run the Node.js commands from `tool\`.

## Requirements

- Windows
- Node.js 20 or 22 LTS
- Java 21, needed to build Fabric mods
- Minecraft Java Edition with Fabric for the target version
- A model provider:
  - LM Studio
  - Ollama
  - or a cloud API key in `keys.json`
- Python with `torch` and `transformers` for local Qwen3 embeddings

## Quick Start

### 1. Install Node dependencies

```bat
cd tool
npm install
```

### 2. Pick your agent profile

Edit `settings.js`.

The important fields are:

```js
"launch_mode": "fabric_ui",
"bridge_url": "http://localhost:8765",
"profiles": [
  "./miku.json"
]
```

Then edit the selected profile, for example `miku.json`, and set:

```json
{
  "name": "Miku",
  "model": "lmstudio/your-model-name"
}
```

Use one of these model formats:

```text
lmstudio/model-name
ollama/model-name
local-embedding/Qwen/Qwen3-Embedding-0.6B
openai/model-name
anthropic/model-name
google/model-name
```

For cloud models, copy `keys.example.json` to `keys.json` and add the API key.

Local embeddings do not need a Hugging Face API key. The active `miku.json`
profile is configured to use `Qwen/Qwen3-Embedding-0.6B` through the local
Python worker. The first run may download the model weights into the Hugging
Face cache; later runs use the cached copy.

Check the local embedding dependencies:

```bat
npm run embedding:check
```

Run a real local embedding smoke test:

```bat
npm run embedding:smoke
```

### 3. Build and install the mods

Close Minecraft first, then run:

```bat
npm run build:mods
```

This builds:

- `tool\fabric-bridge-mod`
- `..\baritone`

It then copies the newest bridge and Baritone jars into your mods folder.

By default the script looks for:

```text
%USERPROFILE%\.lunarclient\profiles\lunar\1.21\mods\fabric-1.21.11
%APPDATA%\.minecraft\mods
```

To choose a folder manually:

```bat
set MINDCRAFT_MODS_DIR=C:\path\to\your\mods
npm run build:mods
```

To remove old bridge and Baritone jars first:

```bat
npm run build:mods:clean
```

### 4. Launch Minecraft

Start Minecraft with the Fabric profile that has:

- the bridge mod jar
- the custom Baritone Fabric jar

Load into your world. The bridge should listen on:

```text
http://localhost:8765
```

### 5. Start the agent

```bat
START_LOCAL.bat
```

or:

```bat
npm start
```

Open the console:

```text
http://localhost:8080
```

`START_LOCAL.bat` auto-installs dependencies if needed and restarts the agent if it exits.

## Useful Commands

```bat
npm start
```

Start the agent once.

```bat
START_LOCAL.bat
```

Start the agent with auto-restart.

```bat
npm run build:mods
```

Build and copy bridge plus Baritone jars.

```bat
npm run build:mods:clean
```

Remove old jars before copying the new ones.

```bat
npm run bridge:probe
```

Check whether the Fabric bridge is reachable.

```bat
npm run reinstall
```

Delete `node_modules` and `package-lock.json`, then reinstall.

## Main Config Files

- `settings.js` - runtime mode, bridge URL, enabled profiles, chat settings, safety flags
- `miku.json`, `andy.json`, `profiles/*.json` - agent name, model, and personality
- `keys.json` - cloud API keys, created from `keys.example.json`
- `fabric-bridge-mod/config/mindcraft-bridge.json` - bridge mod config
- `docs/mod-build-workflow.md` - mod build and copy details
- `docs/fabric-bridge-setup.md` - deeper bridge notes

## Troubleshooting

### The agent starts, but Minecraft does nothing

Check that Minecraft is already running with the Fabric bridge mod loaded, then run:

```bat
npm run bridge:probe
```

If the bridge is not reachable, confirm the bridge URL in `settings.js` is:

```js
"bridge_url": "http://localhost:8765"
```

### The wrong model or personality is used

Check the `profiles` array in `settings.js`. Only profiles listed there are loaded.

### `npm install` fails

Use a current Node.js LTS version, then try:

```bat
npm run reinstall
```

### Old behavior keeps showing up

Close Minecraft, remove old bridge and Baritone jars, then rebuild:

```bat
npm run build:mods:clean
```

## What This Is Not
Mineflayer dependencies may still exist for legacy code, but the main setup is:

```text
LLM agent -> Node.js runtime -> Fabric Bridge HTTP API -> Minecraft Fabric client -> Baritone or BRIDGE
```
