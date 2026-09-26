---
name: run-tool
description: Run, start, launch, smoke-test, or verify the Mindcraft Fabric Bridge Agent (the Node.js app in tool/). Use when asked to run the agent, check if it's alive, probe the bridge, screenshot the web console, or confirm a code change works against the live app.
---

# run-tool

The Mindcraft Fabric Bridge Agent is a Node.js server (`tool/main.js`) that:
- Serves a web console at `http://localhost:8080`
- Polls a Fabric mod HTTP bridge at `http://localhost:8765` (the game client side)
- Spawns one child process per agent profile (`miku.json` by default)

All commands below are run from `tool/`.

## Prerequisites

- Node.js 20 or 22 LTS (project runs fine on v24 too — verified 2026-06-06)
- `curl` on PATH (for smoke driver)
- Minecraft Java Edition with the Fabric bridge mod loaded (needed for bridge endpoints)
- `keys.json` populated if using cloud models (copy from `keys.example.json`)

```bat
npm install
```

## Build (mods only — skip if jars already present)

```bat
npm run build:mods
```

Requires Java 21. Copies jars into `tool/mods/`.

## Run (agent path — smoke driver)

The smoke driver hits a **live running instance** and verifies 5 things:

```bat
node .claude/skills/run-tool/smoke.mjs
```

Expected output when everything is healthy:

```
[PASS] web console responds
[PASS] bridge /ping
         player=Nakano_chan health=20 pos=477,102,39
[PASS] bridge /state: connected player
[PASS] bridge /capabilities: action types present
[PASS] bridge:probe preflight

5 passed, 0 failed.
```

Override URLs if ports differ:

```bat
set BRIDGE_URL=http://localhost:8765
set CONSOLE_URL=http://localhost:8080
node .claude/skills/run-tool/smoke.mjs
```

### Quick curl probes (no driver)

```bat
curl http://localhost:8765/ping
curl http://localhost:8765/state
curl http://localhost:8765/capabilities
```

### Bridge probe script (preflight only)

```bat
node scripts/bridge_action_probe.mjs
```

Add `--full` for destructive action scenarios (sends real actions to the mod).

## Run (human path — starts the app)

```bat
npm start
```

Or use `START_LOCAL.bat` for auto-restart + auto dep-install (preferred for dev).

The web console opens automatically at `http://localhost:8080`. The agent connects to the Fabric bridge and begins polling `/state`.

Stop with Ctrl-C.

## Tests

Run individual test files (the `test/` directory glob doesn't work with `--test`):

```bat
node --test test/bridge_prompt_packs.test.js
node --test test/bridge_agent_active_task.test.js
node --test test/bridge_action_probe.test.js
node --test test/history_memory_filter.test.js
```

Verified passing: `bridge_prompt_packs.test.js` (6/6 pass, no Minecraft needed).

## Gotchas

- **`node --test test/` fails** — Node's test runner can't find a directory on Windows this way. Pass individual file paths instead.
- **Port 8080 already in use** — means the agent is already running (`node main.js`). Check with `netstat -ano | findstr ":8080"`. The smoke driver works fine against the running instance.
- **`/state` returns `connected: false`** — Minecraft isn't running or the Fabric mod isn't loaded. The Node.js server itself is still up; only bridge endpoints fail.
- **Smoke driver `bridge:probe preflight` fails** — bridge mod unreachable. Check that Minecraft is open with the mod loaded.
- **`import.meta.url` path on Windows** — the smoke driver strips the leading `/` from `file:///F:/...` paths when calling `execSync`. If you move the skill dir, update the `cwd` line.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `EADDRINUSE` on port 8080 | Agent already running. Use the existing instance or kill PID from `netstat`. |
| `curl: (7) Failed to connect to localhost port 8765` | Minecraft not running or bridge mod not loaded. |
| `Cannot find module 'F:\tool_test\tool\test'` | Use `node --test test/file.test.js`, not `node --test test/`. |
| `MODULE_NOT_FOUND` for a model provider | Run `npm install` — a dep is missing. |
