# Handoff: Voyager self-improvement layer + review findings

Last updated 2026-09-26. Written for the next agent (any model). Read `CLAUDE.md` first — its **Completion signals** note and **Reviewing changes** checklist are required reading.

## Current state

- `develop` contains: `server_data.js` restore (gitignore fix), the Voyager layer (`src/bridge/skill_library.js`, `src/bridge/curriculum.js`, integration in `bridge_agent.js`), tests in `test/skill_library.test.js`, the review checklist in `CLAUDE.md`, and this file.
- `node --test test/*.test.js` → 141 pass. **The tests are not evidence the layer works** — several pass on idealised fixtures (see F4/test notes).
- Nothing has been run against a live game.
- The skill library is **OFF by default** (`bridge_skill_library_enabled: false`) until P1–P3 below are done: its learning signal is currently mostly noise, and it replays raw player chat into a system prompt (F6). The curriculum is also off by default.

## Root cause (read this before fixing individual items)

Feedback is unreliable at every layer, so anything that learns from outcomes learns garbage:
1. **Baritone fork** reports most failures as nothing, and the bridge's idle watcher then marks them successful (B6, B7); starting a new plan reports the old one as "Task failed" (B8).
2. **Bridge**: `All queued tasks complete` fires per task, not per batch.
3. **Node verifier** settles on that first event, with pre-settle state, against block names instead of drops, on the un-pruned batch.

Fix in this order: Baritone reporting → Node task record → re-enable skill library.

## Priorities

**P0 — one-line fixes (safe, do first)**
- ~~B1~~ DONE (baritone 5c12cf5) `ToolSet.java:192`: `/ avoidanceMultiplier(b)` → `* avoidanceMultiplier(b)` (currently makes Baritone prefer breaking chests/furnaces/crafting tables).
- ~~B18~~ DONE (baritone 5c12cf5) `gradle.properties`: remove `org.gradle.java.home=C:/...` (move to `~/.gradle/gradle.properties`).
- ~~Set `bridge_skill_library_enabled: false`~~ — done.

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

## Design gaps — missing even after P1–P3 (not bugs)

P1–P3 make the existing layer *correct*; it would then only get better at repeating crafting/gathering tasks it has already done. To actually self-improve it also lacks:
1. **Open-ended curriculum.** Fixed 18-milestone list ending at a diamond pickaxe. Voyager's curriculum is LLM-proposed from explored state and current skills.
2. **Composable skills.** Skills are flat, unparameterised action lists; Voyager's are code that calls other skills.
3. **LLM critic / reflection.** Verification only counts inventory, so building, moving, combat, following and sleeping can't be judged; lessons are templated strings, not reflections.
4. **Learning from the player.** Corrections ("no, not like that"), preferences and praise are ignored (only house `rate_build` exists, unreviewed).
5. **Measurement.** No per-task success rate or trend over time, so improvement can't be told apart from drift.
6. **Forgetting and curation.** No decay, merge or pruning of bad skills; no way for the owner to view/delete them.
7. **Model improvement.** Decider-2b and the LLM are frozen; `bots/<name>/system_one_shadow.jsonl` is logged but no calibration or fine-tuning pipeline uses it.

**Corrections from the independent design review (below):** item 2 should not be "skills as code" — the bridge deliberately doesn't run freeform code and the TaskQueue/craft planner already compose; the viable version is parameterised batch templates + usage attribution. Item 7: fine-tuning isn't realistic at this data volume; calibrate System One thresholds from the shadow log and shadow more gates instead. Item 1: LLM proposals should be the *last* curriculum source, after failed player requests and a novelty set.

### Design review (independent, Fable 5.1; claims marked ✓ were re-verified by grep)
**Missing after P1–P3 (G#):**
- G1 Skills are constants: `item`/`count` literal, matched by exact task text + signature, so "8 oak logs" never helps "16 birch logs" and `successes` rarely exceeds 1. → store `{target_item}`/`{target_count}` slots from the verified target; instantiate at retrieval.
- G2 Open-loop retrieval: nothing records whether a retrieved skill was used or helped, so trust can't move. → task record stores `retrievedSkillIds`; credit/debit by loose match (≥0.8 overlap of `type:item`) on definite outcomes only.
- G3 No fast path: mastered tasks still cost a full System Two call. → if a skill has ≥3 successes/0 failures and stored preconditions hold, dispatch directly (`bridge_skill_fastpath_enabled`, off by default); one failure disables it for that skill. Fewer LLM calls per repeated task is the measurable definition of improvement.
- G4 Lessons have no cause and never resolve. Baritone's `Task failed: <label> - <reason>` carries a category that is discarded. → parse into an enum (`not_found|no_path|invalid_args|no_tool|interrupted|timeout|unknown`); skip lessons for interruptions; a later success marks lessons resolved. Optional one-sentence critic call only for `unknown`.
- G5 Verification is inventory-only. → per-type verifiers returning met/not_met/null: build → `validateHouse` score ≥0.8 (score exists but never reaches reward), move → distance <4, sleep → day phase changes, attack → hostile count/drops. Log `goal_done` claims vs verifier.
- G6 Curriculum closed. → `bots/<name>/curriculum.json` with `completed` + `practice`: failed player requests first, then a `discovered` novelty set, then a constrained LLM proposal (target must be in `_knownItems`).
- G7 No player model. → `bots/<name>/player_model.json` (preferences, corrections, ratings) from structured memory extraction, `rate_build`, and `cancel_replace` within 60 s of an autonomous batch; fenced ≤400-char block in inbound/ambient prompts only; TTL 30 days, cap 40.
- G8 Nothing to recall. → `bots/<name>/episodes.jsonl` at task close + significant events, retrieved for `recall`/"remember when".
- G9 System One frozen, one gate. → calibration script over the shadow log; run new gates (speak now? react to event? goal done?) in shadow, promote by measured agreement.
- Forgetting: decay trust after 30 days unused, drop resolved lessons, owner-facing view/delete.

**Wasted signals:** ✓ `system_one_shadow.jsonl` and ✓ `reward.log` are written, never read; ✓ `getTemplatePreference` imported (`bridge_agent.js:38`), never called; ✓ `world_memory.nearestSighting` never called; Baritone failure reasons; build validator score; `cancel_replace` soon after autonomous batches; player praise/corrections; ambient replies (social reward); `goal_done` accuracy; `_buildCraftFallbackActions` firings (planning-failure metric + skill source); queue timing per action type; inventory deltas (novelty).

**Roadmap (value/effort):**
1. Task record + `bots/<name>/outcomes.jsonl` ledger (origin, target, batches, close reason, failure category, duration, retrievedSkillIds) — also the P2 redesign. Offline-testable.
2. `scripts/learning_report.js`: success rate per task family + trend, System One agreement/calibrated thresholds, fallback rate, `goal_done` accuracy.
3. Lesson quality (G4).
4. Usage attribution + parameterised skills (G1, G2).
5. Player model + wire ratings (G7).
6. Fast path (G3) — needs live game.
7. Curriculum sources (G6) — needs live game end-to-end.
8. Non-inventory verifiers + social reward (G5) — needs live game.

## Remaining Baritone medium/low findings (fork)
Goal reached but block face not reachable → infinite loop (TaskPlanProcess:545-563); `#task smelt X 16` with 8 reports success; tick-thread scans up to 256-chunk radius / ~2M block lookups for beds; new cache magic breaks other Baritone builds sharing `baritone/cache`; tracked-block list split (`BLOCKS_TO_KEEP_TRACK_OF` vs `blocksToKeepTrackOf`) → `#mine trapped_chest` finds nothing; `BlockUtils` variant expansion mines stone bricks/smooth stone, `#mine planks` targets logs; `CachedRegion.getLocationsOf` unsynchronised; repack "retry tier" is a no-op; committed `*.log` files and `fabric/bin/`; `stepIdx` overwrite before `succeedStep`; dead multi-furnace branch; `isNightOrThunder` window wider than vanilla; mixins disabled when another `baritone` mod is present.

## Other notes
- Other companion `server_events` are drained every poll but never read (F16).
- Next feature after fixes: make System One (Decider-2b) the gate for reflexes/events/ambient speech instead of hard-coded rules; add relationship/persona memory. See `system_one.js`.
- Pushes to `stanstan-sta/baritone` work (branch `1.21.11`). P1 items there are not started; B1/B18 are done but were not compiled here — run `npm run build:mods` to confirm.

## Review coverage — what was and wasn't checked

"Absence of a finding" only means something for areas marked **read in full**. Everything else is unreviewed, not clean. **Nothing in either repo has been run against a live game**; all Node tests use mocks; the Baritone fork was not compiled.

**tool — Node (`src/`)**
| Status | Files |
|---|---|
| Read in full (author + independent review) | `bridge/skill_library.js`, `bridge/curriculum.js`, `bridge/server_data.js`, `bridge/outcome_verifier.js`, `bridge/goal_manager.js`, `test/skill_library.test.js` |
| Read in full (author only) | `bridge/system_one.js`, `bridge/survival_reflex.js`, `bridge/world_memory.js`, `test/system_one.test.js`, `scripts/system_one_demo.mjs` |
| Partly read | `bridge/bridge_agent.js` (3.4k lines): read ~600–760 (setup), ~925–1010 (prompt block), ~1440–2265 (poll loop, reasoning worker, failure recovery, goal tick, continuation, idle message handler), ~2300–2510 (active-task handler), ~3112 (pre-dispatch pruning). **Not read:** lines 1–600 (parsing/helpers), `_runAmbientTick`, `_handleEvent`, house build/expansion, most of 2510–3461. `bridge_prompt_retriever.js` and `bridge_examples.js` (embedding calls only); `utils/text.js` (`wordOverlapScore` only); `models/local-embedding.js` (`embed` signature only) |
| Only searched (grep) | `bridge_prompt.js`, `settings.js`, `mindcraft/public/settings_spec.json`, `.gitignore` |
| Not opened | `models/codex.js` and all other providers, `models/prompter.js`, `bridge/event_detector.js`, `drive_model.js`, `state_summary.js`, `mine_preprocessor.js`, `house_builder.js`, `house_templates.js`, `fabric_bridge.js`, `topography.js`, all of `src/agent/`, `src/mindcraft/`, `src/process/`, `main.js`, `services/`, `tasks/` |

**tool — Java**
| Status | Files |
|---|---|
| Partly read | `fabric-bridge-mod/.../StateCollector.java` (Baritone log routing, failure matchers, inventory + server_* JSON), `TaskQueue.java` (completion policies, settle, `onBaritoneComplete`, command→policy mapping; idle watcher per independent review), `BridgeConfig.java` (settle times), `bridge-protocol/.../PlayerInfo.java` |
| Not opened | `CommandExecutor.java` (crafting/smelting planner, largest file), `BridgeHttpServer.java`, `CompanionChannel.java`/`CompanionState.java`, screen/GUI drivers, `server-companion-mod/`, `server-companion-paper/` |

**baritone fork**
| Status | Scope |
|---|---|
| Read in full (independent review) | Every file changed since upstream merge-base `c70ce564`: `TaskPlanProcess`, `TaskCommand`, task API/impl, `SleepInBedProcess`, `InteractBlockProcess`, Craft/Interact/Sleep commands, `CommandCoordParser`, `ChatControlHelper`, mixins + `FabricMixinPlugin`, cache changes (`CachedRegion`/`CachedChunk`/`ChunkPacker`/`BlockUtils`), Find/Mine/Farm/ExecutionControl/`ToolSet`/`LookBehavior`/Settings diffs |
| Read only where fork code calls in | Upstream `PathingControlManager`, `PathingBehavior`, `InventoryPauserProcess`, `MineProcess.searchWorld` |
| Not reviewed | All other upstream Baritone code (assumed OK: mature and widely used) |

**Known unaddressed:** 6 pre-existing eslint errors in `src/bridge/` (`Buffer` no-undef in `fabric_bridge.js`/`house_builder.js`/`house_templates.js`, one `no-empty`) — not part of this work.

## Bot prompt review — NOT DONE (next priority review)
An independent review of the bot's own prompts (`bridge_prompt.js`, `bridge_examples.js`, prompt packs, persona `miku.json`, and the prompt-building parts of `bridge_agent.js`) was started but stopped when usage credits ran out; it produced no findings. The only result: assembled prompts measured **~33.6–40.8 KB each (≈8–10k tokens)** for every call type (inbound, active-task, continuation, goal, ambient, event, failure). Even an idle ambient line sends ~8k tokens, mostly the static rulebook. Still to check: contradictions between prompt/action specs/examples and what `parseBridgeResponse` and the mod accept; whether the persona survives the tool-heavy prompt; whether the "stable" prefix really stays byte-identical (KV cache); untrusted text in system-role content and raw_command exposure; size and robustness for 7B local models.

## Finding IDs
N# = author self-review, F# = independent Node review, B# = independent Baritone review. Full reports are summarised above; file:line references are against `develop` @ `7438452` and baritone @ `e7a22d3`.
