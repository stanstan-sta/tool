# Handoff: Voyager self-improvement layer + review findings

Last updated 2026-09-26. Written for the next agent (any model). Read `CLAUDE.md` first — its **Completion signals** note and **Reviewing changes** checklist are required reading.

## Current state

- `develop` contains: `server_data.js` restore (gitignore fix), the Voyager layer (`src/bridge/skill_library.js`, `src/bridge/curriculum.js`, integration in `bridge_agent.js`), tests in `test/skill_library.test.js`, the review checklist in `CLAUDE.md`, and this file.
- `node --test test/*.test.js` → 141 pass. **The tests are not evidence the layer works** — several pass on idealised fixtures (see F4/test notes).
- Nothing has been run against a live game.
- **The skill library is ON by default and should be turned OFF** (`settings.js`: `"bridge_skill_library_enabled": false`) until items P1–P3 below are done: its learning signal is currently mostly noise, and it replays raw player chat into a system prompt (F6). The curriculum is already off by default.

## Root cause (read this before fixing individual items)

Feedback is unreliable at every layer, so anything that learns from outcomes learns garbage:
1. **Baritone fork** reports most failures as nothing, and the bridge's idle watcher then marks them successful (B6, B7); starting a new plan reports the old one as "Task failed" (B8).
2. **Bridge**: `All queued tasks complete` fires per task, not per batch.
3. **Node verifier** settles on that first event, with pre-settle state, against block names instead of drops, on the un-pruned batch.

Fix in this order: Baritone reporting → Node task record → re-enable skill library.

## Priorities

**P0 — one-line fixes (safe, do first)**
- B1 `baritone/src/main/java/baritone/utils/ToolSet.java:192`: `/ avoidanceMultiplier(b)` → `* avoidanceMultiplier(b)` (currently makes Baritone prefer breaking chests/furnaces/crafting tables).
- B18 `baritone/gradle.properties`: remove `org.gradle.java.home=C:/...` (move to `~/.gradle/gradle.properties`).
- Set `bridge_skill_library_enabled: false` in `tool/settings.js`.

**P1 — Baritone fork failure reporting** (`stanstan-sta/baritone`, fork changes = `git diff c70ce564 HEAD`)
- B6: every early return in `TaskPlanProcess.runInteractPlanByBlockName`/`createInteractPlan`/`runContainerPlanByBlockName`/`runSmeltAllItems`, `TaskCommand` ("Could not find any cached positions"), `CraftCommand` ("Could not find any cached crafting tables"), and CommandException paths must log `Task failed: <label> - not_found|invalid_args`.
- B7: `SleepInBedProcess.finish()` → log `Task failed: sleep - <outcome>` on failure, `All queued tasks complete` on success.
- B8: log superseded/cancelled plans as `Task cancelled: <label>` (not `Task failed:`); bridge should treat `#task cancel` as IMMEDIATE.
- B5: `InteractBlockProcess.java:121` and `TaskPlanProcess.java:706` call `cancelEverything()` inside `onTick`, which cancels themselves → `#interact` never clicks. Return `REQUEST_PAUSE` instead.
- B4: `abortPlan`/`cancelPlan`/`onLostControl` must close any open container (else the bot can't move).
- B3: remove `*_log`/`*_wood` from `isKnownFurnaceFuel` (furnace quick-move puts logs in the input slot → 10-min hang).
- B2: `TaskPlanProcess.java:600` carried-item check fails exact-count chest transfers mid-split; only check when `transferPhase == NONE`.

**P2 — Node "task record" redesign** (fixes N3, N4, N10, N11, N13, N14, N15, F7, F8, F9)
Replace `_pendingVerification`/`_currentTaskText` with a task record: starts on a player request or goal start; accumulates every batch (after pruning/expansion) dispatched for it; ends on queue idle (fresh state, after settle — i.e. where `_continuePlan` confirms `queue.status === 'idle'`), `Task failed:`, cancel, stale generation, or restart; verified once at the end. Casual chat, waypoint commands, ambient and event batches don't relabel it. A skill = the whole task's batch sequence, stored only on success.

**P3 — skill library / curriculum fixes**
- F6 (security): never put stored task text in a system-role message. Strip newlines, truncate, put it in a labelled data fence in a user-role message.
- N1: verifier expects mined block names; map blocks to drops (`iron_ore→raw_iron`, `stone→cobblestone`, `coal_ore→coal`, `diamond_ore→diamond`, deepslate variants…).
- N2: curriculum targets are exact items (`oak_log`, `cooked_beef`) while `have()` accepts equivalents → goals never complete. Let the goal accept the same equivalents.
- F2: curriculum re-derives milestones from current inventory, so crafting consumes earlier milestones and it goes backwards. Persist completed milestones.
- F3: LLM `goal_done` on an unmet curriculum goal → immediate re-propose loop with reset counters and chat spam. Defer/cool down per milestone.
- N5: "stop"/explicit cancel must clear a curriculum goal. N6: quiet period must also apply while a curriculum goal runs. N7/F12: curriculum must respect `bridge_proactive_enabled`, low HP/death/open GUI, and must not resume from `goal.json` when disabled.
- N12: inject skills only into task prompts (inbound/continuation/goal/failure), not ambient/event.
- Embeddings: store with `intent: 'document'`, query with `'query'` (like `bridge_prompt_retriever.js`); don't null the model for the whole session on one failure (F4); re-embed on dimension change; raise the 0.35 cosine threshold after measuring real Qwen3 scores (N9); lexical fallback threshold 0.2 retrieves wrong skills (N8).
- F5: `canonicalActions` strips x/y/z, but `move` requires them — drop coordinate-only actions from stored skills instead.
- F10: `skills.json` reaches ~8 MB (full 1024-d vectors); sync non-atomic write per settle. Use tmp+rename, smaller vectors or a sidecar, don't embed lessons.
- F11: concurrent `recordOutcome` duplicates skills and ids.

## Remaining Baritone medium/low findings (fork)
Goal reached but block face not reachable → infinite loop (TaskPlanProcess:545-563); `#task smelt X 16` with 8 reports success; tick-thread scans up to 256-chunk radius / ~2M block lookups for beds; new cache magic breaks other Baritone builds sharing `baritone/cache`; tracked-block list split (`BLOCKS_TO_KEEP_TRACK_OF` vs `blocksToKeepTrackOf`) → `#mine trapped_chest` finds nothing; `BlockUtils` variant expansion mines stone bricks/smooth stone, `#mine planks` targets logs; `CachedRegion.getLocationsOf` unsynchronised; repack "retry tier" is a no-op; committed `*.log` files and `fabric/bin/`; `stepIdx` overwrite before `succeedStep`; dead multi-furnace branch; `isNightOrThunder` window wider than vanilla; mixins disabled when another `baritone` mod is present.

## Other notes
- Other companion `server_events` are drained every poll but never read (F16).
- Next feature after fixes: make System One (Decider-2b) the gate for reflexes/events/ambient speech instead of hard-coded rules; add relationship/persona memory. See `system_one.js`.
- The session that produced this could not push to `stanstan-sta/baritone`; that repo needs its own changes.

## Finding IDs
N# = author self-review, F# = independent Node review, B# = independent Baritone review. Full reports are summarised above; file:line references are against `develop` @ `7438452` and baritone @ `e7a22d3`.
