# Local-use bug-fix checkpoint — 2026-09-29

This branch publishes the accumulated handoff fixes for review and recovery. It is not a declaration that HANDOFF.md is complete or a live-game release certification. Optional learning/curriculum features remain disabled.

The companion fork changes are published at [morebaritone commit 8260e63b](https://github.com/stanstan-sta/morebaritone/commit/8260e63b422435d955a04f3a3dd6000d776d132b). Use the matching fork when evaluating the bridge's task-token completion protocol.

## Included work

- Earlier queue ownership/nested crafting, prompt boundaries, construction, retrieval/memory and P2 task/outcome fixes.
- Dashboard acknowledgement/lifecycle fixes and profile/key rendering fixes.
- Startup-profile confinement and recovery; profile identity and persistence ordering.
- Parent fatal-error handling and per-socket error containment.
- MindServer owner authentication, per-launch child credentials and scoped child operations.
- API-key input validation, constant masks, corrupt-file preservation and atomic replacement.
- Queue skip/resume generation checks and the single-command raw-policy bypass fix.

Open the dashboard using the access link printed by MindServer (or its automatic browser launch). The owner credential changes with each parent process. Spawned children receive separate single-use credentials automatically. Existing scripts calling MindServer APIs must supply the owner bearer token. Fabric bridge authentication is still outstanding.

## Verification available at this checkpoint

- Node: **262 passed, zero failures/cancellations/skips**. Enumerate `test/*.test.js` and exclude only the pre-existing untracked `server_data.test.js`, which references a missing export and was preserved unchanged.
- Fabric bridge: **147 Java tests, zero failures/errors**. JDK 21, `gradlew.bat :test --offline --console=plain` (with the local JDK selected explicitly).
- Dashboard: real Edge regression harnesses passed authentication/refresh/cross-origin-header checks, seven profile scenarios and six adversarial key-rendering cases, with mocked network requests.
- Changed JavaScript adds no lint diagnostics relative to its pre-fix snapshots under the recorded existing lint accommodations. This is not a clean-repository lint claim.
- Companion fork: the earlier successful JDK 21 full build, 36 Java tests, 21 extracted-method adversarial cases and four optimized-JAR API checks cover the unchanged fork source published here.

No live Minecraft or live model integration was run. Several earlier bounded fixes received independent reviews; the latest sequential fixes received main-agent self-review at the user's request. A full fresh review of the accumulated diff remains outstanding. Detailed local evidence is indexed by HANDOFF.md.

## Remaining work

- A10 Fabric HTTP authentication and A12 cross-origin/destructive-event-read concerns. An incomplete A12 draft was set aside before this checkpoint and is not included.
- A15 bridge URL confinement; A9 typed memory/content validation.
- Root-profile editor routing and remaining confirmed Baritone medium/low runtime findings.
- Live integration and broader prompt/Java/fork review. P3 remains open but disabled; optional G-series roadmap work is outside this release scope.
- Windows key-file creation requests mode 0600, but inherited NTFS ACLs were not certified as an owner-only boundary.

Local fixture/profile data, credentials, runtime memories, compiled artifacts and the protected untracked test were not added by this checkpoint.
