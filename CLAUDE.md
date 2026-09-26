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

### Self-improvement layer (Voyager-style)
- `outcome_verifier.js` — `_armVerification` snapshots inventory when a batch is dispatched; `_settleVerification` scores it on queue drain (reward +1/0/-1, `bots/<name>/reward.log`).
- `skill_library.js` — verified batches become skills keyed by the task text (`_currentTaskText`: player request or goal); failed batches become critic lessons. Top matches are retrieved into the dynamic prompt block as `PROVEN PLANS` / `PAST FAILURES`. Persists to `bots/<name>/skills.json`. Gate: `bridge_skill_library_enabled`.
- `curriculum.js` — survival tech-tree milestones. When `bridge_curriculum_enabled` is true and the bot is idle with no goal and no player chat for `bridge_curriculum_idle_ms`, `_maybeStartCurriculumGoal` sets the next milestone as an ordinary goal (`origin: 'curriculum'`); milestones that keep failing are deferred. Off by default.
- `system_one.js` — Decider-2b (Jev-style multiple-choice model on llama-server) decides continue/cancel/append for mid-task chat; the LLM (System Two) still writes replies and actions.

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

## Reviewing changes (required before calling work done)

Tests passing is not a review. Authors check code against their own assumptions, so self-review misses design flaws. Every question below needs a concrete answer, not "looks fine".

**Checklist — ask of every change:**
1. **Untrusted text:** Where can player chat, sign text, item names or server messages end up? Is any of it stored and replayed into a prompt (especially a system-role block), a command string, or a file path? Escape/quote it and label it as data.
2. **Cross-boundary contracts:** For every signal the change relies on (e.g. `baritone_queue` messages, `/state` fields, action specs in `bridge_prompt.js`), open the producer — the Fabric mod or the Baritone fork (`stanstan-sta/baritone`, `TaskPlanProcess`) — and confirm when and how often it is actually emitted. Don't infer from the consumer.
3. **Timing:** Does the code read state on the same poll as a completion event? The mod settles tasks after Baritone reports (e.g. `mineSettleMs` 3000). Batches run one task at a time; Baritone's "All queued tasks complete" fires per task, not per batch.
4. **Every exit path:** Trace success, `Task failed:`, explicit cancel/"stop", dispatch failure, `batchResult.stale`, generation change and restart. Is per-task state set, consumed and cleared on each?
5. **Repetition over time:** What happens on the 2nd and 100th run? Loops that reset their own counters, state that re-derives from inventory the task consumed, chat or LLM calls with no throttle.
6. **Persistence:** What is written under `bots/<name>/`, how big does it get, is the write atomic, what if it's corrupt or from an older format/embedding model, and does it resume after restart when the feature is now disabled?
7. **Gates and settings:** Does it respect `bridge_proactive_enabled` and other master switches? Is every new setting defaulted in `settings.js`? Is anything autonomous on by default?
8. **Data transforms:** When normalising or canonicalising (item ids, actions), check each type's required fields and real-world equivalents (drops vs blocks: `iron_ore` → `raw_iron`, `stone` → `cobblestone`; any log type, not just oak).
9. **Cost:** Extra LLM or embedding calls per turn, prompt size growth, synchronous I/O on the poll loop.
10. **Tests:** Could each test pass for the wrong reason (idealised vectors, identical strings, zero-overlap fixtures)? Is there a test for the failure paths in (4)?

**Independent review:** Changes to learning/memory, prompt construction, persistence, or the queue/continuation machinery get a second review by a separate agent before being reported done. Give it the diff, this checklist and pointers to the producer code — not the author's reasoning or conclusions — and ask it to reproduce findings where it can.

**Report scope honestly:** When reporting, state what was read in full, what was only searched, what was reproduced vs inferred, and what was never run against a live game.
