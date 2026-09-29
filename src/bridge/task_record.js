// Explicit per-task ownership record for the bridge runtime (HANDOFF.md P2).
//
// One record originates with a player work request or a goal, accumulates every
// action batch actually accepted by the bridge AFTER pruning/command
// conversion/build expansion, and closes exactly once at a definite terminal
// point (verified idle, failure, cancel, replacement, stale/disconnect/stop).
// Casual chat, waypoint-only commands, ambient/event batches and failure
// narration never relabel or extend the active record.
//
// Producers (verified against the Java sources):
// - Baritone logs `All queued tasks complete` after EVERY plan task, not every
//   batch (`TaskPlanProcess.finishPlan`), and the mod forwards every Baritone
//   log line as a `baritone_queue` event (`StateCollector.installBaritoneLogger`)
//   before the settle wait (`BridgeConfig.getSettleForCommand`). A poll that
//   carries a completion event therefore carries PRE-SETTLE state: that line
//   must never verify. Only a fresh `getState` snapshot is eligible.
// - Typed-worker completion/failure carries no Baritone log at all
//   (`TaskQueue.completeActiveId`/`failActiveId`); the queue simply goes idle
//   (or paused on failure). The record closes on the fresh idle snapshot, not
//   on any log line.
// - `/batch` may partially accept work (`success:false, accepted:true,
//   queued>0` with mixed `results[]` in `BridgeHttpServer.handleBatch`).
//   Partial acceptance must never produce a success.
// - When the queue field is absent from `/state`, the queue is idle or
//   disabled (`StateCollector`: the `queue` object is only appended when the
//   status is neither idle nor disabled). Absence must therefore be resolved
//   through `FabricBridge.getQueueState()`; it never verifies on its own.

import { expectFromActions } from './outcome_verifier.js';

export function deepCopyActions(actions) {
    try {
        return JSON.parse(JSON.stringify(actions || []));
    } catch {
        return Array.isArray(actions) ? actions.map(a => ({ ...(a || {}) })) : [];
    }
}

// One task identity/label. `baseline` is the inventory Map captured BEFORE the
// first dispatch (so synchronous sendBatch completion/mutation counts as gain).
// `batches` holds one deep-copied accepted batch per successful dispatch.
export function createTaskRecord({ label, origin = 'player', target = null, baseline = null, generation = null, attempt = 1 } = {}) {
    return {
        id: 0,
        label: String(label || '').trim().slice(0, 200),
        origin: String(origin || 'player'),
        target: target && typeof target === 'object' ? deepCopyActions([target])[0] || null : null,
        baseline: baseline instanceof Map ? new Map(baseline) : new Map(),
        batches: [],
        // G2: IDs of skills that were actually surfaced to the model while
        // this task identity was active. These are attribution candidates
        // only; trust changes happen later, after a definite verified outcome.
        retrievedSkillIds: [],
        generation: Number.isSafeInteger(generation) ? generation : null,
        attempt: Number.isSafeInteger(attempt) && attempt > 0 ? attempt : 1,
        createdAt: Date.now(),
        closedAt: null,
        closed: false,
        closeReason: null,
        failureCategory: null,
        ledgerWritten: false,
    };
}

export function isRecordOpen(record) {
    return !!record && record.closed !== true && typeof record.label === 'string' && record.label.length > 0;
}

// Append a deep copy of an ACCEPTED batch. No-op on closed/missing records.
export function appendTaskBatch(record, actions) {
    if (!isRecordOpen(record)) return false;
    if (!Array.isArray(actions) || actions.length === 0) return false;
    record.batches.push(deepCopyActions(actions));
    return true;
}

export function addRetrievedSkillIds(record, ids, maxIds = 16) {
    if (!isRecordOpen(record) || !Array.isArray(ids) || ids.length === 0) return false;
    if (!Array.isArray(record.retrievedSkillIds)) record.retrievedSkillIds = [];
    const seen = new Set(record.retrievedSkillIds);
    let changed = false;
    const limit = Number.isSafeInteger(maxIds) && maxIds > 0 ? maxIds : 16;
    for (const raw of ids) {
        if (record.retrievedSkillIds.length >= limit) break;
        const id = typeof raw === 'string' ? raw.trim() : '';
        if (!id || seen.has(id)) continue;
        record.retrievedSkillIds.push(id);
        seen.add(id);
        changed = true;
    }
    return changed;
}

// Close exactly once. Returns the close reason on the first call, null after.
export function closeTaskRecord(record, reason) {
    if (!record || record.closed) return null;
    record.closed = true;
    record.closeReason = String(reason || 'closed');
    record.closedAt = Date.now();
    return record.closeReason;
}

export function flattenTaskActions(record) {
    const out = [];
    for (const batch of record?.batches || []) {
        for (const action of batch || []) out.push(action);
    }
    return out;
}

export function taskExpectations(record) {
    return expectFromActions(flattenTaskActions(record));
}

// A `/batch` result counts as clean acceptance only when the bridge reports
// success with no per-item rejection. Anything else retires the record.
export function batchHasRejection(batchResult) {
    if (!batchResult || !Array.isArray(batchResult.results)) return false;
    return batchResult.results.some(r => r && (r.status === 'rejected' || r.failure_code));
}

// Eligibility for terminal verification/close. `state` must be a fresh
// `getState` snapshot (never the state carried alongside a completion event):
// connected, queue PRESENT and idle, unpaused, zero pending, and no active
// task. A missing queue object is NOT eligible here: StateCollector omits the
// queue field for BOTH idle and disabled, so callers must resolve the
// ambiguity through the dedicated /queue/state endpoint (fail closed).
export function isEligibleIdleSnapshot(state) {
    if (!state || state.connected !== true) return false;
    const queue = state.queue;
    if (queue === undefined || queue === null) return false;
    if (typeof queue !== 'object') return false;
    const status = String(queue.status || '').toLowerCase();
    if (status !== 'idle') return false;
    if (queue.paused === true) return false;
    const pending = queue.pending === undefined ? 0 : Number(queue.pending);
    if (!Number.isFinite(pending) || pending !== 0) return false;
    const active = queue.active ?? queue.activeTask ?? queue.active_task ?? queue.current ?? queue.command ?? queue.activeId;
    if (active !== null && active !== undefined && String(active).trim() !== '') return false;
    return true;
}

// True when the queue object is absent from a /state snapshot, i.e. the
// snapshot alone cannot distinguish idle from disabled.
export function isQueueOmitted(state) {
    if (!state || state.connected !== true) return false;
    return state.queue === undefined || state.queue === null;
}
