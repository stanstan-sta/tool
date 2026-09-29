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

`mindserver.js` contains synchronous throws and rejected promises at each Socket.IO listener boundary so a bad request cannot take down all agents. `main.js` treats errors escaping those boundaries as fatal, stops child agents, and exits nonzero. Preserve that distinction when touching server/listener code.

Configuration precedence: `settings.js` defaults → env vars (`MINECRAFT_PORT`, `PROFILES`, `INSECURE_CODING`, `SETTINGS_JSON`, etc., handled in `main.js`) → CLI args (`--profiles`, `--task_path`+`--task_id`). A web console runs on `http://localhost:8080`.

## The BridgeAgent (the core of the supported runtime)

`src/bridge/bridge_agent.js` is the heart of the system (~3000 lines). It is a **polling loop**, not event-driven from Minecraft:

1. `_runLoop()` polls `GET /state` (delta polling via `?since=<seq>`, adaptive 800ms–5s interval).
2. Player chat → `_handleMessage` (idle) or `_handleActiveTaskMessage` (queue busy → narrow `continue`/`cancel_replace`/`append_after_current` evaluator).
3. The LLM returns either structured JSON (`{reply, actions[], commands[]}`) or `ACTION:`/`COMMAND:` lines; `parseBridgeResponse` handles both and tolerates small-model quirks (multiple JSON objects, lone action objects).
4. Actions/commands are dispatched as a **batch** to the Fabric mod's `TaskQueue`, which runs them one at a time and emits `baritone_queue` completion/failure events.
5. `_recordQueueDispatch` sets `_lastHadActions`/`_pendingContinuation`; on queue drain, `_continuePlan` re-prompts with fresh state to carry multi-step plans forward. On `Task failed:`, `_handleFailureRecovery` clears the queue and replans.

**Completion signals (verified against the producers).** Baritone logs `All queued tasks complete` whenever *its own* plan queue empties (`TaskPlanProcess.finishPlan` in the `stanstan-sta/baritone` fork). The mod feeds Baritone one task at a time, so that line appears after **every task, not every batch**. The mod forwards every Baritone log line as a `baritone_queue` event (`StateCollector.installBaritoneLogger`), then settles the task (`BridgeConfig.getSettleForCommand`, e.g. mine 3000 ms) before advancing. A poll that carries a completion event carries **pre-settle** state; only `state.queue.status === 'idle'` on fresh state means the batch drained. Bridge failure matchers are in `StateCollector.routeBaritoneToTaskQueue`; Baritone output that matches neither is completed as success by the TaskQueue idle watcher.

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

Tests passing is not a review. For each item write one line of evidence — file:line, a command you ran, or "N/A because …".

1. **Untrusted text.** Where can player chat, sign/item names or server messages end up? It never goes into a system-role message, a command string or a path. Stored text is truncated, stripped of newlines, and replayed only inside a clearly labelled data fence in a user/tool-role message. (Quoting alone does nothing.)
2. **Producers, not consumers.** For every signal the change relies on (queue events, `/state` fields, action specs), open the code that emits it and confirm when and how often it fires. Don't infer from the consumer.
3. **Timing.** Which snapshot is compared with which? State in the same poll as a completion event is not "after" it, and one completion event is not "batch drained" (see Completion signals).
4. **Exit paths.** Make a table: rows are each piece of per-task state the change reads or writes; columns are success, `Task failed:`, explicit cancel/"stop", dispatch failure/stale, generation change, restart. Each cell: set / consumed / cleared / left stale.
5. **Two computations of one fact.** When a check and a target are computed separately (an "already have" predicate vs a goal's target; expected gain vs what was actually mined/dropped), prove they agree on the same inventory. Use shared item/drop maps, not hand-written ids.
6. **Feedback loops.** If anything derived from a measurement (verifier, reward, similarity) is stored, what happens when the measurement is wrong? Can a false result become permanent belief, and how is it forgotten?
7. **Which prompts.** List the prompt kinds touched (inbound, active-task, continuation, goal, ambient, event, failure) and confirm the added context is relevant to each. Injected text matches the format `parseBridgeResponse` expects and doesn't change the static prefix (KV cache).
8. **Models.** The embedding model/LLM may be local, remote, offline or swapped. Stored text is embedded with `intent: 'document'` and queries with `intent: 'query'` (as in `bridge_prompt_retriever.js`); dimension mismatch is handled per entry; a transient failure degrades one call, not the session; thresholds are checked against real score ranges.
9. **Autonomy gates.** List every path that starts chat, actions or LLM calls without a player message, and the setting that turns each off. New autonomous behaviour: off by default, under `bridge_proactive_enabled`, defaulted in `settings.js`, and not resumed from `bots/<name>/` after restart when disabled.
10. **Repetition and cost.** On the 100th run: counters that reset themselves, throttles, duplicate entries, file and prompt growth, extra LLM/embedding calls per prompt kind, sync I/O on the poll loop, awaits inside read-modify-write of shared state.
11. **Persistence.** What is written, how big it gets, atomic write (tmp + rename; works on Windows), a schema/model tag so old data is recognised, and behaviour on a corrupt file.
12. **Tests.** Fixtures resemble real data (related-but-different strings, realistic cosine ranges). Every exit in (4) has a test. Note anything that throws in a test and is swallowed by a `.catch`.

**Independent review.** For changes to learning/memory, prompt construction, persistence or queue/continuation: start a fresh agent with no conversation history (a new session, or an Agent call that receives only the text below). Give it the commit SHA or diff, this section, the producer files named under Completion signals, and the test command. Do **not** give it your checklist answers, your summary of what the code does, or the commit message's claims. Ask for findings as file:line + how reproduced + severity, plus at least one adversarial test it wrote and ran. Your report lists every finding as fixed, or rejected with a reason.

**Report scope honestly.** State what was read in full, what was only searched, what was reproduced vs inferred, what was never run against a live game, and which checklist items were N/A and why.
