# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An LLM-controlled Minecraft agent. The Node.js app in `tool/` drives a real Minecraft **Fabric** client over an HTTP bridge (`http://localhost:8765`) exposed by a companion mod in `fabric-bridge-mod/`. The LLM decides intent; the Fabric mod owns all game/client interaction; **Baritone** owns pathfinding and long-running automation.

Run all Node commands from the `tool/` directory.

> The repo started life as Mindcraft/Mineflayer (note `"name": "mindcraft"` in package.json, the `mineflayer-*` deps, and `Mindcraft`/`mindcraft` in paths). **Those names are legacy.** The supported runtime is the Fabric Bridge path, selected by `settings.launch_mode` being `fabric_ui`/`fabric_headless`. Don't assume Mineflayer is in use just because its code is present.

## Commands

```bat
npm start                  :: start the agent (node main.js)
START_LOCAL.bat            :: start with auto-restart + auto dep-install (preferred for dev)
npm run build:mods         :: build fabric-bridge-mod + ../baritone, copy jars into the mods folder
npm run build:mods:clean   :: same, removing old bridge/Baritone jars first (close Minecraft first)
npm run bridge:probe       :: check the Fabric bridge is reachable / inspect actions (--full for more)
npm run embedding:check    :: verify local Python embedding deps (Qwen3) are installed
npm run embedding:smoke    :: real local-embedding smoke test
npm run reinstall          :: delete node_modules + package-lock.json, then npm install
```

Lint (no `lint` script defined; run eslint directly):
```bat
npx eslint src/bridge/your_file.js
```

Tests use the **built-in `node:test` runner** (no `test` script in package.json, no Jest/Mocha):
```bat
node --test test/                              :: all tests
node --test test/bridge_prompt_packs.test.js   :: a single test file
node --test --test-name-pattern="<substr>" test/<file>.test.js   :: a single test by name
```

## Entry point and process model

`main.js` parses args, applies env-var overrides, then calls `Mindcraft.init(...)` and `Mindcraft.createAgent(settings)` once per profile in `settings.profiles`. Each agent runs in its **own child process** spawned by `src/process/agent_process.js`, which picks the init script by mode:
- bridge mode → `src/process/init_bridge_agent.js` → `BridgeAgent` (the supported path)
- legacy → `src/process/init_agent.js` → `Agent` (Mineflayer)

`main.js` installs `uncaughtException`/`unhandledRejection` handlers so a throwing Socket.IO listener can't take down the MindServer and all agents with it — keep that invariant in mind when touching server/listener code.

Configuration precedence: `settings.js` defaults → env vars (`MINECRAFT_PORT`, `PROFILES`, `INSECURE_CODING`, `SETTINGS_JSON`, etc., handled in `main.js`) → CLI args (`--profiles`, `--task_path`+`--task_id`). A web console runs on `http://localhost:8080`.

## The BridgeAgent (the core of the supported runtime)

`src/bridge/bridge_agent.js` is the heart of the system (~3000 lines). It is a **polling loop**, not event-driven from Minecraft:

1. `_runLoop()` polls `GET /state` (delta polling via `?since=<seq>`, adaptive 800ms–5s interval).
2. Player chat → `_handleMessage` (idle) or `_handleActiveTaskMessage` (queue busy → narrow `continue`/`cancel_replace`/`append_after_current` evaluator).
3. The LLM returns either structured JSON (`{reply, actions[], commands[]}`) or `ACTION:`/`COMMAND:` lines; `parseBridgeResponse` handles both and tolerates small-model quirks (multiple JSON objects, lone action objects).
4. Actions/commands are dispatched as a **batch** to the Fabric mod's `TaskQueue`, which runs them one at a time and emits `baritone_queue` completion/failure events.
5. `_recordQueueDispatch` sets `_lastHadActions`/`_pendingContinuation`; on queue drain, `_continuePlan` re-prompts with fresh state to carry multi-step plans forward. On `Task failed:`, `_handleFailureRecovery` clears the queue and replans.

This continuation machinery is load-bearing: most features (including the goal engine) originate **one** batch when idle and let the queue+continuation loop carry the rest. Don't re-implement step execution.

### Proactive layer (all gated by `bridge_proactive_enabled`)
- `event_detector.js` — diffs state to emit discrete events (low_hp, hostiles, nightfall, new player) with per-type cooldowns.
- `drive_model.js` — 4 decaying drives → textual hints (never raw numbers) for ambient turns.
- `_runAmbientTick` — idle self-initiated speech, token-bucket budgeted (`bridge_ambient_budget_per_hour`), suppressed when busy/AFK/recently spoke.
- `goal_manager.js` + `_runGoalTick` — autonomous goal pursuit; `goal:`/`stop goal` chat commands; persists to `bots/<name>/goal.json`.

### Prompt construction (semantic, not template placeholders)
The bridge builds its own context instead of using `$EXAMPLES`/`$CODE_DOCS` placeholders: `bridge_prompt.js` (system prompt) + `bridge_examples.js` (RAG over authored examples) + `bridge_prompt_packs.js`/`bridge_prompt_retriever.js` (compact task-specific guidance retrieved by embedding similarity, e.g. mining/crafting/combat packs). All keyed off the embedding model.

## Other key boundaries

- `src/bridge/fabric_bridge.js` — HTTP client for the mod (`/ping`, `/state`, `/command`, `/action`, `/batch`, `/queue/*`, `/capabilities`).
- `src/models/` — one file per LLM provider (`claude.js`, `gpt.js`, `ollama.js`, `lmstudio.js`, `local-gguf.js`, `gemini.js`, …) behind `prompter.js`. Model strings in profiles use a `provider/model-name` prefix (e.g. `lmstudio/...`, `anthropic/...`, `local-embedding/Qwen/Qwen3-Embedding-0.6B`). `_model_map.js` maps prefixes to provider classes.
- Local embeddings run through a **Python worker** (`services/local_embedding_worker.py`); needs `torch`+`transformers`. Not all profiles need a cloud key.
- `src/agent/` — the legacy Mineflayer agent and its command/skills system. Still imported for shared pieces (`history.js`, `prompter.js` via `Prompter`, tasks), but the live game loop is the bridge.
- `src/mindcraft/` — MindServer (Socket.IO) + web UI; `mindserver_proxy.js` relays agent output/logs to the UI.

## Profiles, keys, tasks

- `settings.profiles` lists active profile JSONs (e.g. `miku.json`). Only listed profiles load.
- Cloud keys: copy `keys.example.json` → `keys.json`.
- Tasks (the MineCollab benchmark: cooking/construction/crafting) are run via `tasks/` runners with `--task_path`/`--task_id`; see `minecollab.md`. Construction tasks need `--insecure_coding` (lets agents run freeform JS — prefer a container).

## Conventions

- ESM throughout (`"type": "module"`); ecmaVersion 2021.
- ESLint enforces semicolons, `require-await`, and `no-floating-promise/no-floating-promise` (every Promise must be awaited or have a `.catch`) — the last one is easy to trip in the async bridge loop.
- Platform is Windows; mod-build scripts are PowerShell (`scripts/build_mods.ps1`). Use PowerShell syntax for shell work.
- Agent runtime files write under `bots/<name>/` (memory, `goal.json`, `ambient.log`).
