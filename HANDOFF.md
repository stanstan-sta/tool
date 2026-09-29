# 2026-09-29 final autonomous review checkpoint

This section supersedes older status summaries below; historical findings are retained for traceability.

**Published main repository state:** `stanstan-sta/tool` branch `beta` includes the P3 W5/W6/W7 merge plus the follow-up review/fix pass. The follow-up independently found and fixed additional cross-feature regressions rather than accepting the worker report at face value.

**Verified after the follow-up:**
- clean `npm ci` with all six `patch-package` patches applying;
- **352/352 Node tests pass** when the protected legacy `test/server_data.test.js` is excluded, matching the established test convention for this branch; current regressions include vLLM SDK construction, typed durable-memory migration/validation, closed-loop skill attribution, and categorized/resolvable failure lessons;
- `npx eslint src test main.js` is clean after resolving the historical repo-wide lint backlog; `bridge_agent.js` is clean independently;
- Fabric bridge Java tests complete successfully under **JDK 21**; an earlier CI failure was only the runner using Java 17, not a Java test failure;
- the official `openai` dependency is now pinned to **7.23.0** so the code's `Responses` API and `AzureOpenAI` import actually exist. The upgrade workflow smoke-tested AzureOpenAI, Responses, Chat Completions and Embeddings surfaces, then reran the Node suite and lint;
- post-review startup regression fix: `src/models/vllm.js` now supplies a non-empty local placeholder API key (`vllm-local`) because OpenAI SDK 7.x rejects an empty key at client construction; clean `npm ci`, the focused constructor test, the full 334-test Node suite and repo-wide lint all pass;
- runtime requirement is now explicit and consistent: README, `package.json` and the root lockfile require **Node >=22.0.0**, matching `openai@7.23.0`'s declared engine;
- no live Minecraft/model session was performed. Source/unit/build verification is not a substitute for live-game validation.

**P3 source issues closed in the current beta follow-up:**
- N1 outcome verification maps common block drops and no longer false-fails mine→craft plans by demanding intermediates that a later recipe consumed. Recipe consumption is traced transitively for generated intermediates; RNG/tool-dependent drops are treated as unverifiable instead of manufactured failures.
- N2 equivalent-target curriculum completion, F2 persisted milestone completion, F3 goal-done verification/cooldown, N6 quiet-period enforcement and N7/F12 active safety gating are implemented by the W5 merge; F3 now verifies target-bearing claims against a fresh non-destructive post-inference state.
- F4 transient embedding failures no longer permanently disable embeddings; mixed lexical/vector retrieval now applies the threshold that matches the score actually used.
- F5 learned plans discard location/entity-dependent actions that cannot be replayed after coordinates/IDs are removed.
- F6 stored skill/task text is bounded/normalised on load and is replayed only inside an explicit untrusted user-role fence. Corrupt `skills.json` is preserved under a `.corrupt.<timestamp>` name and persistence failures are logged/returned instead of silently disappearing.
- F10 skill persistence uses atomic temp+rename writes, omits lesson embeddings and quantizes stored vectors; F11 `recordOutcome` updates are serialised; N12 skill context is task-only.
- F16 companion events are visible to the observation consumer without being hidden by the `since` fast path, are consumed once, and are discarded rather than injected when `bridge_server_data_enabled` is false.
- The SES lockdown wrapper, dependency/patch reproducibility, generated repository artifacts, machine-specific launcher paths, line endings and lockfile issues from the broader repository review are fixed.
- Two legacy correctness bugs exposed during lint cleanup were fixed: exception stack handling in `action_manager.js` and an undeclared `res` in `npc/item_goal.js`. Detached async work now has explicit await/rejection ownership instead of floating promises.
- G2 closed-loop retrieval is now implemented: task records retain the IDs of skills actually surfaced to the model; first-prompt retrieval is transferred into the later task identity; active-task retrieval attaches directly; terminal fresh verification credits/debits only retrieved skills with at least 0.8 `type:item` overlap; direct exact-skill updates are excluded from reuse attribution to prevent double-counting. Definite verified failures now also reach the existing lesson writer instead of disappearing. Retrieval evidence persists and contributes to skill trust. Focused tests plus clean `npm ci`, the full 347-test Node suite and repo-wide lint pass.
- G4 failure lessons are now categorized and lifecycle-aware: server failures map to `not_found|no_path|invalid_args|no_tool|interrupted|timeout|unknown`; interruption/cancellation is neutral; repeated identical failures coalesce while preserving occurrence counts; exact failed plans and matching retrieved plans are debited once; successful completion resolves prior lessons for that task, and resolved lessons are excluded from retrieval and curriculum failure counts. Focused tests plus clean `npm ci`, the full 352-test Node suite and repo-wide lint pass.

**Items that remain open by design/evidence rather than known unfixed source regressions:**
- A9 is now source-complete: durable memory persists a versioned typed fact schema (`preference|reminder|correction|fact|outcome`), legacy string memories migrate safely, malformed/hostile facts fail closed, and existing prompts receive only a compact rendered compatibility string. Focused memory regressions, clean `npm ci`, the full 338-test Node suite and repo-wide lint pass.
- N8/N9 retrieval thresholds (lexical 0.2 / cosine 0.35) remain measurement/tuning questions; no arbitrary threshold change was made without score-distribution evidence.
- live Minecraft integration, live model behavior and Windows NTFS ACL isolation remain unvalidated.
- the optional open-ended Voyager-style G-series roadmap and large-file refactor of `bridge_agent.js` are architectural work, not correctness fixes required for this checkpoint.

**Baritone fork status at this checkpoint:** published to `stanstan-sta/morebaritone` branch `1.21.11` at `d1eb37df482fbcd547ee70d6accfedc4834f813f` by non-force fast-forward. The W8 base (`cf20fc42b643b63b688b75dd343621872f3c51ef`, additionally exercised through `62e2e8935fa6a090fd33263d34c88f0fdd82acd6`) passed its Gradle test task and full Gradle build under JDK 21 in an independent GitHub runner. Final review then closed two missed W8 leftovers before publication: direct fallback scans use bounded loaded-chunk search instead of the historical 256-chunk path, bed fallback scans loaded chunks rather than a ~2M-block cube, generated `*.log`/`**/bin/` debris is removed/ignored, and the required `BaritoneAPI` import is present. The first follow-up rerun compiled main code and failed only on that missing import; the next compiled and reached tests, where only a newly-added harness test failed because loading Minecraft-heavy `TaskPlanProcess` is invalid in the plain unit harness, so that harness-only test was removed. The exact final-tree rerun for `d1eb37df482fbcd547ee70d6accfedc4834f813f` completed successfully: Gradle tests passed, the full Gradle build passed under JDK 21, and the SHA/diff verification gate passed. Live-game validation remains outstanding.

---

# Handoff: Voyager self-improvement layer + review findings

## Status index — 2026-09-29

**Publication checkpoint:** the accumulated control-plane fixes are committed on `beta`; see `AUDIT_CHECKPOINT.md` for the current scope. The preceding integration commit recorded 299/300 Node tests (sole protected pre-existing failure), earlier Fabric verification recorded 147 Java tests, and the matching Baritone fork is on `morebaritone/1.21.11`. Earlier no-commit/no-push statements are historical.

**Done locally** means implemented with the recorded tests/build checks. It does not mean committed, deployed, or validated in a live Minecraft session. Historical descriptions below are retained for traceability; use this index and the explicit item statuses when selecting remaining work.

| Area | Current status |
|---|---|
| P0 (B1, B18, skill-library default off) | Done; B1/B18 are in the recorded fork commit |
| P1 / B2–B8 | Done locally, independently reviewed; 132 bridge tests, 48 focused fixture checks, full fork build, 4 packaged-API checks |
| Latest audit's prompt/vision, construction, retrieval, world-memory and queue fixes | Implemented locally with recorded checks; nested-craft R3 and P1 received bounded independent reviews, but the broader earlier Java/fork diff still lacks a complete fresh independent review |
| P2 task record / outcome verification | Done locally; independently reviewed; 197 Node tests in the main checkout and 11 root regressions pass; live-game validation outstanding |
| P3 skill library / curriculum | Source fix pass complete for the recorded N/F correctness findings; feature remains off by default pending live-game validation and N8/N9 empirical tuning. See the final checkpoint above. |
| A5 dashboard state/memory acknowledgements | Fixed locally; bounded concurrent polling, replacement-socket safety and listener lifecycle independently reviewed and tested |
| A3 persisted startup-profile paths | Fixed locally 2026-09-29; real-target confinement, schema checks and per-file startup recovery; 12 focused regressions and 236 Node tests pass |
| A7 profile/agent identity consistency | Fixed locally 2026-09-29; mismatched renames rejected, live updates/in-use checks normalize paths, failed saves preserve live settings; six regressions pass |
| A14 queue skip/resume generation checks | Fixed locally 2026-09-29; same generation guard as cancellation, client forwards generation; 8 Java HTTP and 2 Node regressions; full suites 140 Java / 244 Node pass |
| A6 uncaught parent failures / socket error boundary | Fixed locally 2026-09-29; socket sync/async failures contained, uncaught parent failures stop children and exit nonzero; 24 focused tests pass |
| A1 MindServer authentication | Fixed locally 2026-09-29; HTTP bearer and Socket.IO credential checks, per-parent access link, browser refresh and real child startup verified |
| A4 child impersonation / authorization | Fixed locally 2026-09-29; one-use per-launch credentials, fixed agent identity, scoped events, restart rotation/revocation; 9 authentication tests pass |
| A2 API-key validation / storage | Application fix done locally 2026-09-29; known-name/string validation, constant masks, corrupt-file preservation and atomic replacement; 6 regressions and full 262-test Node suite pass; Windows ACL isolation not certified |
| A11 Fabric raw-command policy bypass | Fixed locally 2026-09-29; single-command endpoint applies the existing allowlist to every non-chat command; 7 Java handler tests pass |
| A8 unparsed player-chat system-role injection | Fixed locally; replay is fenced user-role data |
| A9 persistent-memory injection | Done at source level: replay-role boundary plus versioned typed fact schema/content validation, legacy migration, fail-closed hostile/malformed handling, and persistence regressions |
| A13 dashboard profile/agent-name XSS | Fixed locally via one Muse Spark 1.3 Contributor Free CLI worker, then root review; original exploit reproduced and 7 browser regression scenarios passed |
| Dashboard API-key name/mask HTML injection follow-up | Fixed locally 2026-09-29; original attribute execution and mask markup reproduced in Edge; six adversarial key cases plus save/error/refresh flows pass |
| Baritone short-smelt success / reached-goal interaction hang | Fixed locally on 2026-09-29; independently reviewed, 21 focused cases, full build and packaged API checks pass |
| Other A-series findings and remaining Baritone medium/low findings | Not closed; verify each historical finding against current code before fixing |
| Complete prompt semantics/performance review and live integration | Outstanding; runtime boundary fixes do not establish full coverage |
| G-series design/feature roadmap | Optional future work, outside the current bug-fix finish line |

## Sequential main-agent bug fixes — 2026-09-29

The user explicitly requested the remaining bugs be fixed one at a time by the main agent. No subagents are used in this pass; review evidence is a self-review, not an independent review. Optional features remain deferred.

- **A11 — DONE LOCALLY:** `/command` normalizes before dispatch and applies RawCommandPolicy to every non-chat command. Slash commands, bare Minecraft command strings and the unsupported `whisper:` pseudo-command no longer bypass the queue policy. Public chat remains immediate; permitted Baritone commands remain queued. Seven Java handler tests pass with client execution mocked; source comparison confirms the old bypass, but the old Java baseline was not executed. Actual Minecraft dispatch remains untested.

- **A2 — APPLICATION FIX DONE LOCALLY:** owner authentication from A1/A4 protects the key API. It accepts only known names from `keys.example.json` and string values, reports set/unset with constant masks, preserves existing custom entries without exposing them, rejects malformed storage rather than overwriting it, and uses an exclusive temporary file plus rename. Five pre-fix regressions fail; all six key tests and the complete **262-test Node suite** pass (excluding only protected `server_data.test.js`), with no new lint diagnostics. Partial-write and rename failures preserve the original bytes and remove the temporary file. Mode 0600 is requested on creation; Windows inherits directory ACLs, so cross-account NTFS isolation is not certified. No real key file was read or modified during testing.

- **A4 — DONE LOCALLY:** child credentials are now separate from the dashboard owner credential, issued on every spawn and consumed once at handshake. Children can retrieve only their own settings, bind only their authenticated identity, emit their own output and exchange peer chat. Administrative actions and HTTP APIs require the owner credential. Rotation/re-registration revokes the prior child; disconnect during initialization clears its association. Explicit parent-assigned benchmark tasks retain their historical run-shutdown capability. Two pre-fix authorization checks fail; the earlier 32-case combined suite and final nine authentication cases pass, including actual child startup and benchmark completion. No added lint diagnostics. The owner intentionally retains full control. Self-review evidence: `solo-rest/REVIEW.md`.

- **A1 — DONE LOCALLY:** APIs and Socket.IO require a random per-parent credential. The console/browser launch supplies an access link; the dashboard removes its fragment and retains the credential in tab session storage. Spawned children receive the credential through their environment. Three pre-fix authentication regressions fail; 28 focused tests pass, including the real parent/launcher/proxy startup path. Browser authentication/reload/cross-origin-header checks, seven profile scenarios and six key-rendering cases pass. No added JS or HTML lint diagnostics. This closes the unauthenticated boundary; agent role separation (A4), key validation/storage (A2) and the Fabric control plane remain separate findings.

- **A3 — DONE LOCALLY:** startup lists now validate every path and profile before persistence, resolve real targets to reject junction/symlink escapes, exclude reserved root configuration/credential files, normalize and deduplicate entries, and update memory only after a successful write. The profile library and valid legacy root profiles such as `miku.json` remain supported. Old saved lists are checked at boot; bad entries are skipped, and a missing/malformed profile no longer prevents later valid profiles from starting. Explicit CLI/environment profile selection retains its existing external-file support. The original code fails all 12 regression checks; the fixed focused suite passes 12 and the full Node suite passes **236** (excluding only the preserved pre-existing `server_data.test.js`). No added lint diagnostics. Evidence/checklist: `F:/tool_test/audit-2026-09-26/fixes/solo-rest/REVIEW.md`.
- **A7 — DONE LOCALLY:** HTTP updates require the URL, supplied profile and existing stored profile to agree on identity. Socket settings cannot rename an existing agent. Relative/absolute paths (including Windows case differences) use one comparison for live settings updates and deletion protection. Failed profile writes leave live settings unchanged. Six original-code failures now pass; the combined A3/A7 suite passes 18; no new lint diagnostics. Existing files whose names/identities were already inconsistent are rejected, not silently renamed. The historical root-profile editor routing note is now reconciled: current routing resolves legacy root profiles by filename or stored name, rejects ambiguous duplicates, and `profile_library_routing.test.js` covers GET/PUT/DELETE/launch behavior.
- **A14 — DONE LOCALLY:** skip/resume apply the existing cancellation generation guard before mutation; Node methods forward a supplied safe-integer generation. Eight real Java HTTP cases verify stale/invalid requests leave the active paused task untouched, current/newer generations are accepted, and legacy requests without a generation retain the cancellation endpoint's compatibility semantics. Two Node HTTP regressions pass. Full suites now pass **244 Node tests** and **140 Fabric bridge tests**; no added lint diagnostics. This is staleness protection for generation-bearing requests, not authentication; unversioned legacy requests remain outside that protection.
- **A6 — DONE LOCALLY:** every application socket handler now has a shared sync/async error boundary, including returning the asynchronous memory-request chain. A malformed request receives a controlled failure when it supplied an acknowledgement; other requests keep working. Truly uncaught parent exceptions/rejections stop child agents and exit nonzero instead of serving from unknown state. Normal requested shutdown retains exit code zero. Three original-code failures now pass; all 24 focused server/startup/profile tests pass; no new lint diagnostics. CLAUDE.md's process-boundary description was updated. No live process was stopped: regressions used isolated subprocesses and stub child agents.

## Dashboard API-key rendering follow-up — 2026-09-29 fallback

The one-time fallback found the prior runtime batch complete and no worker actively implementing this follow-up. The remaining API-key rendering sink was confirmed against `GET /api/keys`: arbitrary key names are returned and configured strings produce a first-four/last-four mask. In `loadApiKeys`, `esc(k)` did not escape quotes inside the input's HTML attribute, and the mask was inserted as raw HTML. A real headless Edge reproduction executed a crafted key-name focus handler and created an `<hr>` element from a mask matching the actual producer's format.

The minimal fix changes only two expressions on one production line in `src/mindcraft/public/index.html`: key IDs use the existing `attr()` helper and mask text uses `esc()`. Six ordinary/hostile key cases now retain exact names/IDs and literal mask text without executing handlers or inserting elements. Save/no-change, trimmed value submission, clearing after success, preservation after failure, controlled load failure and refresh recovery all pass with HTTP/Socket.IO mocked. No live key data or control-plane traffic was used. Inline syntax passes and ESLint has zero new diagnostics relative to the 14 existing dashboard diagnostics. The protected `test/server_data.test.js` remains unchanged. No dependencies, agent swarm, deployment, commit or push.

Evidence and required review checklist: `F:/tool_test/audit-2026-09-26/fixes/key-rendering/REVIEW.md`. This closes the separate rendering follow-up left by A13; A2 authentication/key-storage concerns and other open handoff findings remain open. No fresh independent reviewer was required: this changes rendering only, not persistence, prompts, learning or queue behavior.

## Local-only runtime bug pass — 2026-09-29

Release scope is a local-only build for the owner's use, with bug fixes prioritized over features. `bridge_skill_library_enabled` and `bridge_curriculum_enabled` remain false; no `settings_local.json` is present in this checkout. Source fixes were delegated to Muse Spark 1.3 Contributor Free through OpenCode after the requested Luna workers hit usage limits. Separate fresh Muse sessions reviewed the dashboard, curriculum gate and fork changes; all three final reviews pass. The root integrated only the reviewed three production files and six regression test files after checking the starting hashes, preserving other dirty work and the pre-existing untracked `test/server_data.test.js`.

- **A5 — DONE LOCALLY:** state requests run concurrently with bounded Socket.IO acknowledgements and one active poll. Memory requests also time out. Late/replaced-socket responses cannot overwrite current state; an old socket disconnect cannot clear its replacement. Repeated listener subscriptions are idempotent and disconnect removes them safely.
- **Curriculum off/stop boundary — DONE LOCALLY for this scope:** saved curriculum-origin goals are cleared while the curriculum or proactive switch is off, including when the general goal engine is off. The gate and goal identity are checked again after inference. Explicit stop retires the captured curriculum goal without clearing a newer or explicit user goal; matching player task labels do not confer curriculum ownership. Switching curriculum off does not cancel an already-accepted batch: that batch may drain; explicit stop remains the immediate cancellation path. Remaining P3 behaviors are not certified.
- **Baritone short-smelt success — DONE LOCALLY:** a bounded request that exhausts inputs below its requested output count now reports failure, including the multi-furnace terminal path. Unbounded smelting and ordinary progress retain their existing behavior.
- **Baritone reached-goal/no-face hang — DONE LOCALLY:** interaction fails as unreachable after the existing 40-tick budget when already at the goal but no block face is reachable. Legitimate approach does not consume that budget; successful reachability and step transitions reset it.

Main-checkout verification: **224 Node tests passed**, **11 additional P2 regressions passed**, **21 independent extracted-method Baritone cases passed**, and the **full Baritone Gradle build passed on JDK 21**, including **36 Java tests with no failures/errors**. Both optimized API JARs retain both bridge task-token setters (**4 checks**). Targeted ESLint adds **zero diagnostics**; MindServer retains nine pre-existing `require-await` diagnostics under the documented lint accommodations. Both working trees pass `git diff --check`. The protected test's hash is unchanged; its missing export remains the reason it is excluded from the Node command.

Exact commands, logs, guarded integration hashes, independent review dispositions and limits: `F:/tool_test/audit-2026-09-26/fixes/ship-runtime/MAIN-INTEGRATION-REPORT.md`. No live Minecraft or live model validation, deployment, commit or push occurred. P3, other A-series/fork findings, and the broader earlier Java/prompt audit remain open; this pass does not complete the whole handoff.

## Dashboard A13 fix pass — 2026-09-28

One OpenCode Zen worker (`opencode/muse-spark-1.3-contributor-free`) implemented the fix in a source-only copy. The root agent reviewed the full diff, corrected four newly introduced floating-promise lint findings, and applied only `src/mindcraft/public/index.html` after checking the main file still matched the starting snapshot. Profile/agent names and startup paths no longer enter inline event-handler source; DOM listeners carry the original data, HTML attributes are escaped, DOM ID lookups use the raw names, and profile URL path segments are encoded.

The original profile-name JavaScript injection was reproduced in a real headless Edge browser. All **7 browser regression scenarios passed** on the final main-checkout file, covering ordinary/hostile quote, HTML, entity, Unicode and URL-path names; profile select/save/clone/launch/delete; startup toggles; agent controls, Enter-to-send, settings, state updates and disabled states. The browser harness mocked HTTP/Socket.IO and did not contact the live control plane. Extracted inline JavaScript parses; ESLint reports **no new diagnostics** relative to the 14 pre-existing dashboard diagnostics. Evidence and required review checklist: `F:/tool_test/audit-2026-09-26/fixes/muse-a13/REVIEW.md`.

This closes the profile/agent-name and startup-path injection described by A13. The separate API-key-name attribute sink and raw mask rendering left open at this pass were fixed in the 2026-09-29 fallback above; the dashboard is not certified free of XSS. A1/A2/A7 and P3 remain open; P2 was subsequently completed locally. No new dependencies, deployment, commit or push.

## Baritone P1 fix pass — 2026-09-28

The 4:47am fallback heartbeat fired while work was already active and did not start a duplicate run. Local fixes now cover the older Baritone **B2–B8** backlog: split-transfer ordering and partial moves; quick-move-safe furnace fuel; container/key cleanup; self-cancellation inside ticks; rejection/sleep/cancellation reporting; and task-token propagation across queued/compound plans. The bridge installs tokens for task/craft/sleep commands and requires their explicit outcomes instead of treating idleness as success. Command dispatch leaves the queue lock before waiting for the client thread.

Final verification: **132 bridge tests passed**, with zero failures/errors; **48 extracted-method cases passed** (7 transfer, 17 lifecycle/fuel, 24 logging). The original split-transfer code failed the same regression. The full Baritone build passed under JDK 21. One bounded GPT-5.6 Sol review found two issues, both addressed: terminal tokens now bypass toast-only display routing, and missing token APIs explicitly reject before command execution. A further actual-JAR check found ProGuard removed/renamed the reflected setters; the existing KeepName annotation now preserves them. `javap` confirms both setters in both final optimized API JARs (4 checks). Evidence, commands, checklist, review dispositions, and limitations: `F:/tool_test/audit-2026-09-26/fixes/baritone-p1.md` and `baritone-p1-independent.md`.

P2/P3, older medium/low Baritone findings, and unresolved independent security findings below remain open. The main agent has begun reading the P2 verification/continuation paths but has not changed them in this continuation. Do not claim the full handoff is fixed. No live deployment, commit, or push has been performed.

## Local fix pass — 2026-09-27

Uncommitted fixes are present for the latest audit's queue, construction, prompt/vision, retrieval and world-memory findings. The tracked Node suite plus new regression files passes **169/169** tests; the independent Node adversarial suite passes **5/5**. Prompt context stays outside policy, vision waits for preceding actions, recipe planning respects inventory and dependency order, schematics preserve door/bed states, and world-memory writes are batched with atomic replacement.

The Fabric bridge suite at this earlier pass passed **124 tests with no failures/errors** (the later P1 pass raised the total to 132). The nested-crafting portion of **R3 is implemented and independently reviewed**: obtain waits for the craft's complete inventory goal, failed substeps propagate failure, and timeout/cancellation retires nested work before the parent resumes. Seven lifecycle regressions plus an independent 32-step handoff test cover this follow-up. Evidence: `F:/tool_test/audit-2026-09-26/fixes/nested-craft.md` and `nested-craft-independent.md`. The earlier Baritone fork build passed under JDK 21; this follow-up did not change it.

This does **not** close all older P1–P3/B/N/F items below. No live Minecraft validation or deployment was performed. One bounded independent review covered the nested-craft follow-up; the broader earlier Java/fork diff has not received a complete fresh independent review. Preserve the pre-existing untracked `test/server_data.test.js`; its missing `describeServerData` export remains separate from this fix pass. The local evidence and remaining limitations are in `F:/tool_test/audit-2026-09-26/fixes/CLOSEOUT.md` and adjacent reports. The historical notes below describe the earlier baseline.

Last reconciled 2026-09-29. The original audit below was written on 2026-09-26; its line references describe that baseline unless a later status is supplied. Read `CLAUDE.md` first — its **Completion signals** note and **Reviewing changes** checklist are required reading.

## Historical baseline — 2026-09-26

- `develop` contains: `server_data.js` restore (gitignore fix), the Voyager layer (`src/bridge/skill_library.js`, `src/bridge/curriculum.js`, integration in `bridge_agent.js`), tests in `test/skill_library.test.js`, the review checklist in `CLAUDE.md`, and this file.
- `node --test test/*.test.js` → 141 pass. **The tests are not evidence the layer works** — several pass on idealised fixtures (see F4/test notes).
- Nothing has been run against a live game.
- The skill library remains **OFF by default** (`bridge_skill_library_enabled: false`) while P2/P3 are unfinished. The original F6 system-role replay issue has been addressed at the runtime boundary; the remaining learning issues still prevent enabling the layer. The curriculum is also off by default.

## Root cause (read this before fixing individual items)

The original audit traced unreliable learning to the following chain. Both the Baritone P1 layer and the Node P2 task-record/verifier work are now fixed locally; the historical chain explains why the remaining P3 work cannot be judged from idealized tests alone:

1. **Baritone fork — FIXED LOCALLY (B6, B7, B8):** the original failures were silent, and superseded plans appeared as failures. The 2026-09-28 pass supplies explicit task-token outcomes and distinct cancellation signals.
2. **Bridge**: `All queued tasks complete` fires per task, not per batch.
3. **Node verifier — historical defect, addressed by P2:** settled on that first event, with pre-settle state, against block names instead of drops, on the un-pruned batch. Current verification uses the accumulated accepted task record and a fresh terminal-idle state; see the P2 evidence.

Remaining: reconcile the P3 skill/curriculum findings and perform integration validation before re-enabling the skill library. These disabled optional behaviors are outside the current local-only bug-fix batch.

## Priorities

**P0 — one-line fixes (safe, do first)**
- ~~B1~~ DONE (baritone 5c12cf5) `ToolSet.java:192`: `/ avoidanceMultiplier(b)` → `* avoidanceMultiplier(b)` (currently makes Baritone prefer breaking chests/furnaces/crafting tables).
- ~~B18~~ DONE (baritone 5c12cf5) `gradle.properties`: remove `org.gradle.java.home=C:/...` (move to `~/.gradle/gradle.properties`).
- ~~Set `bridge_skill_library_enabled: false`~~ — done.

**P1 — DONE LOCALLY: Baritone fork failure reporting** (`stanstan-sta/baritone`; `git diff c70ce564 HEAD` describes committed fork changes only, so also inspect the working-tree diff)

Implemented locally in the 2026-09-28 pass described above; each finding ID below records its completed behavior. No live-game validation or deployment has occurred.

- [x] **B6 — DONE LOCALLY:** missing targets/smelt inputs and task/craft command exceptions emit explicit rejection outcomes, including the matching bridge token. Pending tokens are consumed or cleared on rejection.
- [x] **B7 — DONE LOCALLY:** sleep success/failure/cancellation emits explicit outcomes, and control release preserves the last result.
- [x] **B8 — DONE LOCALLY:** superseded/cancelled plans use CANCELLED state and a distinct event; bridge task control commands use IMMEDIATE completion policy.
- [x] **B5 — DONE LOCALLY:** interaction/bed ticks request a pause instead of cancelling their own process through `cancelEverything()`.
- [x] **B4 — DONE LOCALLY:** abort/cancel closes an open container and releases forced input keys.
- [x] **B3 — DONE LOCALLY:** quick-move fuel selection excludes logs/wood and nonflammable crimson/warped planks.
- [x] **B2 — DONE LOCALLY:** active split phases finish before searching for another source or rejecting the cursor; partial quick moves count only the observed amount transferred.

**P2 — DONE LOCALLY: Node task-record lifecycle and outcome verification**

Player requests and goals retain one task identity across continuation and append; the record accumulates accepted post-expansion batches and learns only once after a fresh eligible terminal-idle read. Missing queue state is resolved through `/queue/state`; stale per-command completion, rejected/partial dispatches, inference failures and incomplete verification cannot produce a successful skill. Vision prefix actions remain accounted for across inspection, terminal refresh failures retire or retry safely, and dynamic verifier details stay in fenced user-role history. Validation: 197 Node tests in the main checkout and 11 root regressions passed; focused tests, lint output, independent review and guarded integration hashes are recorded in `F:/tool_test/audit-2026-09-26/fixes/muse-p2/workspace/evidence/`, `F:/tool_test/audit-2026-09-26/fixes/muse-p2/workspace/INDEPENDENT_REVIEW.md` and `F:/tool_test/audit-2026-09-26/fixes/muse-p2/workspace/REPAIR_REPORT.md`. Live-game validation remains outstanding.

**P3 — SOURCE FIX PASS COMPLETE; LIVE/TUNING VALIDATION REMAINS: skill library / curriculum fixes**

- **F6 — PARTIALLY ADDRESSED:** runtime retrieval, including stored skill/task text, now uses a bounded, escaped data fence in a user-role message (`bridge_agent.js` dynamic-context path; tested in `bridge_runtime_regression.test.js`). Full stored-text newline/length normalization still needs reconciliation in the skill-library pass.
- N1: verifier expects mined block names; map blocks to drops (`iron_ore→raw_iron`, `stone→cobblestone`, `coal_ore→coal`, `diamond_ore→diamond`, deepslate variants…).
- N2: curriculum targets are exact items (`oak_log`, `cooked_beef`) while `have()` accepts equivalents → goals never complete. Let the goal accept the same equivalents.
- F2: curriculum re-derives milestones from current inventory, so crafting consumes earlier milestones and it goes backwards. Persist completed milestones.
- F3: LLM `goal_done` on an unmet curriculum goal → immediate re-propose loop with reset counters and chat spam. Defer/cool down per milestone.
- **N5 — DONE LOCALLY 2026-09-29:** explicit stop/cancel clears the captured curriculum-origin goal, preserving newer/user goals. **N6 — OPEN:** quiet period must also apply while a curriculum goal runs. **N7/F12 — PARTIALLY ADDRESSED 2026-09-29:** the proactive/curriculum gates now cover saved goals and post-inference dispatch; disabled goals cannot resume from `goal.json`. Low HP/death/open GUI guards remain to be reconciled. An already-accepted batch may drain after switching curriculum off; explicit stop cancels it.
- N12: inject skills only into task prompts (inbound/continuation/goal/failure), not ambient/event.
- Embeddings: store with `intent: 'document'`, query with `'query'` (like `bridge_prompt_retriever.js`); don't null the model for the whole session on one failure (F4); re-embed on dimension change; raise the 0.35 cosine threshold after measuring real Qwen3 scores (N9); lexical fallback threshold 0.2 retrieves wrong skills (N8).
- F5: `canonicalActions` strips x/y/z, but `move` requires them — drop coordinate-only actions from stored skills instead.
- F10: `skills.json` reaches ~8 MB (full 1024-d vectors); sync non-atomic write per settle. Use tmp+rename, smaller vectors or a sidecar, don't embed lessons.
- F11: concurrent `recordOutcome` duplicates skills and ids.

## Optional design roadmap — outside the current bug-fix scope

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
- G2 **DONE:** task records store surfaced `retrievedSkillIds`; fresh definite outcomes credit/debit matching retrieved skills at ≥0.8 `type:item` overlap, persistence/trust includes reuse evidence, and exact direct updates are excluded from reuse double-counting.
- G3 No fast path: mastered tasks still cost a full System Two call. → if a skill has ≥3 successes/0 failures and stored preconditions hold, dispatch directly (`bridge_skill_fastpath_enabled`, off by default); one failure disables it for that skill. Fewer LLM calls per repeated task is the measurable definition of improvement.
- G4 **DONE for deterministic causes/lifecycle:** Baritone/typed-worker failure reasons are parsed into `not_found|no_path|invalid_args|no_tool|interrupted|timeout|unknown`; interruptions are neutral, repeated lessons coalesce with occurrence counts, successful task completion resolves prior lessons, and resolved lessons stop being retrieved/counted. The optional one-sentence critic for `unknown` remains intentionally unimplemented.
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

## Historical Baritone medium/low findings (source fixes published; live validation remains)

**DONE LOCALLY 2026-09-29:** goal reached but block face not reachable → infinite loop (historical TaskPlanProcess:545-563); `#task smelt X 16` with 8 reports success. The first now uses a bounded in-goal reachability timeout; the second requires the requested count at terminal exhaustion. See the runtime bug pass and independent fork review above.

**Current disposition:** the listed source findings are addressed in the published `morebaritone/1.21.11` line: bounded loaded-chunk fallbacks replace the 256-chunk/~2M-block scans; cache writes retain V1 compatibility while reading prior V2; runtime cache users share the settings-backed tracked-block registry including trapped chests; planks/stone aliases are corrected; cached-region lookups are synchronized; live-scan fallback replaces the ineffective immediate repack retry; tracked logs/bin output are removed/ignored; smelt rewinds return before `succeedStep`; multi-furnace targets are retained; vanilla sleep bounds are explicit/tested; duplicate standalone Baritone suppression is explicit/loud rather than silent. Live-game coexistence/behavior remains unvalidated.

## Other notes
- Other companion `server_events` are drained every poll but never read (F16).
- Next feature after fixes: make System One (Decider-2b) the gate for reflexes/events/ambient speech instead of hard-coded rules; add relationship/persona memory. See `system_one.js`.
- Baritone branch: `1.21.11`. P1 is implemented locally and the full Gradle build passed on JDK 21; optimized API JAR signatures were checked. Changes have not been deployed, committed, or pushed. `npm run build:mods` also copies JARs into the live mods directory; the recorded validation used Gradle directly.

## Independent audit findings — GPT-5.6 Sol (2026-09-26)

**Status reconciliation, 2026-09-29:** A5 is fixed locally with bounded acknowledgements, concurrent guarded polling and loopback Socket.IO regressions. A8 is fixed locally (`bridge_agent.js` now stores unparsed player chat as fenced user-role data). A9's replay-role boundary is fixed, but its typed memory schema/content-validation recommendations remain unimplemented. A13's profile/agent-name and startup-path injection is fixed locally with browser regression evidence above. The other A-series entries are not closed. Their original diagnostic descriptions below are historical findings, not claims that every described line remains unchanged.

These findings were identified independently from the earlier N#/F#/B# reviews. They are based on full-file reads of the files cited below, not grep-only sampling. The wider repository audit is still incomplete, so this section should be treated as **new confirmed findings, not an exhaustive security review**.

- **A1 — FIXED LOCALLY 2026-09-29 (historical finding: local control plane has no authentication/authorization):** `src/mindcraft/mindserver.js:115-362` exposes the Socket.IO control plane without any authentication or per-action authorization. Any client that can reach the MindServer can create agents, read/change settings, restart/stop/destroy agents, send messages, clear/compact/replace agent memory, stop all agents, or shut down the process. The server currently binds to `localhost` (`mindserver.js:639-645`), which limits remote exposure, but any local process or tunnel/proxy that can reach the port gets administrative control. Add an authenticated session/token boundary and authorize privileged events explicitly.

- **A2 — APPLICATION FIX DONE LOCALLY 2026-09-29; Windows ACL isolation unverified (historical finding: unauthenticated API-key management):** `src/mindcraft/mindserver.js:364-426` exposes `GET /api/keys` and `POST /api/keys` with no authentication. GET discloses which provider credentials exist and reveals the first/last four characters of configured values; POST accepts arbitrary key/value names and rewrites `keys.json`. This is a credential-management surface and should require strong local authentication, reject unknown key names, avoid returning credential fragments, and use atomic writes with restrictive permissions.

- **A3 — HIGH — FIXED LOCALLY 2026-09-29 (persistent arbitrary local JSON file selection via startup profiles; historical diagnosis follows):** `src/mindcraft/mindserver.js:577-598` accepts and persists an arbitrary array of startup-profile path strings without constraining them to the profile directory. On the next launch, `main.js:105-108` blindly calls `readFileSync(profile, 'utf8')` and `JSON.parse` on every persisted path. A caller with MindServer access can therefore cause the application to read/parse an arbitrary local JSON file on restart, and can persist a path that crashes startup when it is missing, unreadable, or invalid JSON. Canonicalize paths, require them to resolve inside an approved profile directory, and validate each target before persisting.

- **A4 — FIXED LOCALLY 2026-09-29 (historical finding: agent-process socket impersonation / channel takeover):** `src/mindcraft/mindserver.js:202-219` trusts a caller-supplied `agentName` in `connect-agent-process` / `login-agent` and replaces the registered agent's socket with the caller's socket. There is no per-agent nonce, parent/child secret, or authenticated process handshake. A client that can reach the MindServer can impersonate an existing agent, intercept messages/state requests intended for it, and participate in trusted inter-agent/control flows. Parent-spawned children should receive an unguessable one-time credential that must be presented before the socket is associated with an agent identity.

- **A5 — MEDIUM/HIGH — FIXED LOCALLY 2026-09-29 (one unresponsive agent can stall state broadcasting indefinitely; historical diagnosis follows):** `src/mindcraft/mindserver.js:667-689` requests each agent's full state serially once per second and awaits a Socket.IO acknowledgement with **no timeout**. If one connected agent never calls the acknowledgement callback, that interval invocation never advances, so state updates for every listener/agent stop. `get-agent-memory` at `mindserver.js:191-200` has the same unbounded acknowledgement pattern. The runtime bug pass above implements bounded timeouts, concurrent isolated requests, and stale-socket/listener cleanup with independent regressions.

- **A6 — MEDIUM — FIXED LOCALLY 2026-09-29 (global uncaught-error handlers continue after unknown process corruption; historical diagnosis follows):** `main.js:26-37` installs `uncaughtException` and `unhandledRejection` handlers specifically to keep the parent alive. This prevents one listener exception from killing all agents, but continuing after an arbitrary uncaught exception can leave shared process state inconsistent. Catch errors at the Socket.IO/handler boundary instead; for genuinely uncaught failures, restart the affected process or exit cleanly rather than assuming invariants still hold.

- **A7 — MEDIUM — FIXED LOCALLY 2026-09-29 (profile file identity can diverge from profile/agent identity; historical diagnosis follows):** `src/mindcraft/mindserver.js:529-548` selects the file to overwrite from the URL parameter but accepts a body whose `profile.name` can differ from that filename. The code then uses the body name when deciding which in-memory agent to update (`540-543`). This allows filename identity, profile identity, startup-path identity and `agent_connections` identity to diverge, creating stale or misdirected updates. Require the URL name and body name to match, or make one canonical and ignore/rewrite the other.

- **A8 — HIGH (player-derived text can be promoted directly to system role):** `src/bridge/bridge_agent.js:1630-1700` processes player chat events. When an event has a sender but the message format fails `parsePlayerChatMessage`, and the text does not mention the bot, the raw stripped message is written with `this.history.add('system', stripped)`. On servers with custom chat formats that the parser does not recognize, a normal player's text can therefore enter future LLM calls as a **system-role instruction**, bypassing the explicit rule that player chat is untrusted data. Do not use system role for unparsed server/player text; preserve its provenance as user/world data and fence/label it.

- **A9 — HIGH (persistent prompt-injection boundary: user-derived memory is replayed as system text):** `src/agent/history.js:5-94` summarizes user turns through an LLM into `history.memory` with only a natural-language filter instruction; the resulting free-form text is not structurally validated. `src/bridge/bridge_agent.js:940-970` passes that memory into `buildBridgeDynamicBlock`, and `src/bridge/bridge_prompt.js:242-258` places it in the trailing dynamic block that is appended as a **system** message. A malicious or merely mis-summarized user statement can thus persist and later gain system-role authority even with the skill library disabled. Keep memories in a lower-trust role/data envelope, use a typed schema for facts/preferences, and reject instruction-like memory content.

- **A10 — FIXED IN BETA 2026-09-29 (historical finding: Fabric bridge itself was an unauthenticated local control plane):** `fabric-bridge-mod/.../BridgeHttpServer.java:29-44` binds a second administrative HTTP service on localhost and exposes state, command, action, batch, queue mutation, block reads, and screenshots with no authentication. This is separate from A1: compromising any process that can reach port 8765 gives direct control of the live Minecraft client even if the Node MindServer is secured. Add a per-session bearer capability generated by the Node parent/mod handshake and require it on every non-liveness endpoint.

- **A11 — FIXED LOCALLY 2026-09-29 (historical finding: slash commands bypass RawCommandPolicy entirely):** `fabric-bridge-mod/.../BridgeHttpServer.java:91-126` sends any command beginning with `/` directly to `CommandExecutor.execute` before `RawCommandPolicy` is consulted. `BridgeConfig.java:65-77` presents the raw-command allowlist as the command restriction mechanism, but it only protects normalized `#...` Baritone commands. Any client that can reach the Fabric bridge can therefore submit arbitrary Minecraft slash commands available to that player/session. Route slash commands through an explicit deny-by-default policy or remove slash-command execution from the generic endpoint.

- **A12 — FIXED IN BETA 2026-09-29 (historical finding: cross-origin exposure of live state/screenshots, plus destructive event drain):** all `BridgeHttpServer` responses set `Access-Control-Allow-Origin: *` (`BridgeHttpServer.java:612-630`). Read-only-looking GET endpoints include `/state`, `/commands`, `/capabilities`, `/queue/state`, `/read_blocks`, and `/screenshot`. `/state` defaults to `drainEvents=true` unless `peek` is supplied (`BridgeHttpServer.java:61-86`). Any browser origin permitted by the browser's localhost/private-network policy to reach this service can therefore read gameplay state/screenshots; simply polling `/state` can also consume chat/events before the real agent sees them. Remove wildcard CORS, make event reads non-destructive by default, and require authentication even for GET endpoints.

- **A13 — HIGH — FIXED LOCALLY 2026-09-28 (stored dashboard XSS through profile names; historical diagnosis follows):** profile validation only requires a non-empty string (`src/mindcraft/mindserver.js:38-46`). `POST /api/profiles` sanitizes that string only for the **filename** but stores the original profile object unchanged (`mindserver.js:511-523`). The dashboard later interpolates `p.name` into inline JavaScript handlers such as `onclick="selectProfile('...')"` using `esc()`, which HTML-escapes text but does not JavaScript-string-escape apostrophes (`src/mindcraft/public/index.html:311-313, 738-766`). A crafted/imported profile name can therefore break out of the quoted JS string when the profile list renders. Because dashboard JavaScript can call the unauthenticated keys/control APIs, this XSS has high impact. Remove inline event-handler string construction; bind listeners with DOM APIs and treat names as data.

- **A14 — MEDIUM — FIXED LOCALLY 2026-09-29 (generation/staleness protection is incomplete on queue mutation; historical diagnosis follows):** the bridge has a generation mechanism intended to reject stale work (`BridgeHttpServer.java:834-855`), and `/queue/cancel` applies it, but `/queue/skip` and `/queue/resume` at `856-875` accept no generation at all. A delayed/stale caller can therefore skip or resume whatever task is current at arrival time, even after a newer user request advanced the generation. Apply the same generation check to every queue-mutating endpoint and include generation in the Node client methods.

- **A15 — FIXED IN BETA 2026-09-29 (historical finding: configurable bridge URL created an SSRF/network-pivot primitive):** `bridge_url` is a persisted UI setting (`src/mindcraft/public/settings_spec.json:64-69`); unauthenticated `set-agent-settings` persists accepted global settings (`src/mindcraft/mindserver.js:242-269`); and `src/bridge/fabric_bridge.js` treats the configured URL as a trusted base for repeated server-side `fetch` calls. A client with access to the MindServer can set the URL to an arbitrary HTTP(S) origin and cause the Node process to probe/request predictable paths there when the agent starts. This extends a local-control compromise into SSRF against services reachable from the Node host. Restrict `bridge_url` to loopback by default or enforce an explicit host allowlist.

**Audit scope for A1-A15:** `main.js` and `src/mindcraft/mindserver.js` were read in full. Process/bootstrap files were also read in full while tracing the control plane. These findings are additional to the issues already documented above; no claim is made here that the rest of `src/`, the web UI, Java/Fabric bridge, provider wrappers, or task tooling is clean.

## Historical review coverage — 2026-09-26

The tables below preserve the original audit's coverage. Later coverage and validation are recorded in the dated fix-pass sections above and `F:/tool_test/audit-2026-09-26/fixes/CLOSEOUT.md`, `nested-craft-independent.md`, and `baritone-p1.md`. Do not interpret an old "Not opened" row as the current status of a file touched in those passes. The Baritone fork has since been built successfully; **live-game validation remains outstanding**. Unreviewed areas must not be assumed clean.

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

## Bot prompt review — PARTIAL; broader review remains open

Runtime prompt-boundary, Codex transcript, retrieved-context ordering, vision-context and capability-refresh fixes have since been implemented and tested (see the 2026-09-27 fix pass). A complete prompt semantics, persona, size and model-performance review is still outstanding. The following paragraph records the original 2026-09-26 review attempt; its prompt-size measurements are historical.

An independent review of the bot's own prompts (`bridge_prompt.js`, `bridge_examples.js`, prompt packs, persona `miku.json`, and the prompt-building parts of `bridge_agent.js`) was started but stopped when usage credits ran out; it produced no findings. The only result: assembled prompts measured **~33.6–40.8 KB each (≈8–10k tokens)** for every call type (inbound, active-task, continuation, goal, ambient, event, failure). Even an idle ambient line sends ~8k tokens, mostly the static rulebook. Still to check: contradictions between prompt/action specs/examples and what `parseBridgeResponse` and the mod accept; whether the persona survives the tool-heavy prompt; whether the "stable" prefix really stays byte-identical (KV cache); untrusted text in system-role content and raw_command exposure; size and robustness for 7B local models.

## Finding IDs
N# = author self-review, F# = independent Node review, B# = independent Baritone review. Full reports are summarised above; file:line references are against `develop` @ `7438452` and baritone @ `e7a22d3`.
