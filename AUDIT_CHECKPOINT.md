# Local-use bug-fix checkpoint — 2026-09-29

This checkpoint describes the current `beta` source after the accumulated handoff fixes and the repository-hardening follow-up. It is not a live-game release certification. Optional learning/curriculum features remain disabled by default.

The companion fork is published on `stanstan-sta/morebaritone` branch `1.21.11` at commit `8260e63b422435d955a04f3a3dd6000d776d132b`.

## Implemented in current beta

- MindServer owner authentication, per-launch child credentials, scoped child operations, acknowledgement timeouts and restart-race guards.
- Startup-profile confinement, profile identity checks, safe profile persistence and API-key rendering/file replacement hardening.
- Fabric bridge bearer authentication, loopback binding, loopback-only CORS, observation-vs-peek event draining, queue-generation checks and raw-command policy enforcement.
- Bridge URL confinement to loopback HTTP endpoints with credentials/query/fragment rejection.
- Memory replay filtering/schema validation and prompt fencing.
- P2 task/outcome accounting improvements: task identity/generation tracking, terminal-state verification, and no success learning from rejected/stale/unverifiable dispatches.
- SES lockdown wrapper fixed so it calls the SES global rather than recursively calling itself.
- Legacy generated-code execution is now opt-in by default (`allow_insecure_coding: false`).
- Patched npm packages are pinned to the exact versions named by the patch files; future npm saves are configured to remain exact.
- Generated Fabric `bin/` output and stale extracted/local jars are ignored and removed from the tracked tree.
- System One launcher paths are supplied through environment variables instead of a committed user-specific Windows path.
- Repository text line endings are normalized through `.gitattributes`.

## Verification status

The integration commit immediately preceding this hardening pass recorded **299/300 Node tests**, with the sole failure being the protected pre-existing `server_data.test.js` case. Targeted suites and syntax checks passed there. Earlier Fabric verification recorded 147 Java tests passing.

This hardening pass adds a focused SES regression test. It has not been through a live Minecraft/model integration session, so source-level fixes should not be treated as runtime certification until the normal test/build workflow is run on a development machine.

## Remaining work

- P3 learning/curriculum correctness remains incomplete and those optional features stay disabled by default. Known items include block-to-drop verification, equivalent-target completion, persisted curriculum progress, active-goal safety/quiet-period gating, skill-context scoping, embedding failure/migration handling, atomic skill persistence and concurrency.
- A9 memory handling is substantially hardened but still uses a free-form summary rather than a fully typed fact schema.
- Windows key-file creation requests mode 0600, but inherited NTFS ACLs have not been certified as an owner-only boundary.
- Live Minecraft integration and broader runtime validation remain outstanding.
- Repository-wide ESLint debt and large-file refactoring are maintenance work rather than changes safe to fold into this correctness/security commit.
- A generated `package-lock.json` should be committed after the next trusted `npm install`; it is no longer ignored or deleted by the reinstall helper.
