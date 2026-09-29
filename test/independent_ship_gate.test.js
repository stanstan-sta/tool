// Independent adversarial review tests for the curriculum off-switch gate.
// Scope: GATE-ONLY (bridge_agent.js curriculum delta + curriculum_gate.test.js).
// Uses real GoalManager temp persistence and real BridgeAgent goal/task-record
// methods; only I/O (LLM, bridge, history) is stubbed. No production or
// existing-test changes. Does not contact live processes or load secrets.
import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';
import { GoalManager } from '../src/bridge/goal_manager.js';
import { Curriculum } from '../src/bridge/curriculum.js';
import { default as settings } from '../src/agent/settings.js';
import { serverProxy } from '../src/agent/mindserver_proxy.js';

serverProxy.socket = { emit() {} };

const tmpRoot = mkdtempSync(join(tmpdir(), 'independent-ship-gate-'));
after(() => rmSync(tmpRoot, { recursive: true, force: true }));

let fileSeq = 0;
function tempGoalPath() {
    fileSeq += 1;
    return join(tmpRoot, `goal-${Date.now()}-${fileSeq}.json`);
}

function savedSettings() {
    return {
        curriculum: settings.bridge_curriculum_enabled,
        proactive: settings.bridge_proactive_enabled,
        goal: settings.bridge_goal_enabled,
    };
}
function restoreSettings(s) {
    settings.bridge_curriculum_enabled = s.curriculum;
    settings.bridge_proactive_enabled = s.proactive;
    settings.bridge_goal_enabled = s.goal;
}

const idleState = () => ({
    connected: true,
    inventory: [],
    x: 0, y: 64, z: 0, dimension: 'minecraft:overworld',
    queue: { status: 'idle', pending: 0 },
});

// Same lightweight fixture convention as curriculum_gate.test.js: the REAL
// goal/curriculum/task-record methods run; only LLM/bridge/history I/O is stubbed.
function makeAgent({ goalManager, prompt = async () => '', curriculum = null } = {}) {
    const agent = Object.create(BridgeAgent.prototype);
    const historyEntries = [];
    const llmCalls = [];
    const dispatchCalls = [];
    Object.assign(agent, {
        name: 'Miku',
        _generation: 1,
        _taskRecord: null,
        _taskSeq: 0,
        _lastTaskLabel: '',
        _lastTaskAttempt: 0,
        _pendingContinuation: false,
        _lastHadActions: false,
        _promptInFlight: false,
        _currentTaskText: '',
        _nextGoalTickAt: 0,
        _lastState: idleState(),
        goalManager,
        curriculum: curriculum || new Curriculum({ maxFailures: 3 }),
        skillLibrary: null,
        episodicMemory: { lastPlayerChatAnsweredAt: 0 },
        worldMemory: { describe: () => 'none' },
        history: {
            memory: '',
            add(role, content) { historyEntries.push({ role, content }); },
            getHistory() { return []; },
            async save() {},
        },
        historyEntries,
        llmCalls,
        dispatchCalls,
        _buildStateContext: () => '',
        _announceGoal() {},
        async _promptConvoLocked(kind, history) {
            llmCalls.push(kind);
            return prompt(kind, history);
        },
        async _sendBatchWithBuildExpansion(actions) {
            dispatchCalls.push({ kind: 'actions', actions: actions.map(a => ({ ...a })) });
            return { success: true, queued: actions.length };
        },
        async _sendBatchCommandsWithPreprocessing(commands) {
            dispatchCalls.push({ kind: 'commands', commands: [...commands] });
            return { success: true, queued: commands.length };
        },
        bridge: {
            async cancelQueue() { return { success: true }; },
            async getState() { return agent._lastState; },
        },
    });
    return agent;
}

function seedGoal(path, text, origin) {
    const gm = new GoalManager(path);
    gm.set(text, null);
    if (origin) {
        gm.goal.origin = origin;
        gm.save();
    }
    return gm;
}

// ── positive control: unrelated explicit user task survives the retire ──────
// Curriculum OFF. A stale persisted curriculum goal plus a live, independent
// player-origin record with DIFFERENT text. The retire must clear the goal
// while leaving the user's record open with no LLM/dispatch.
test('independent player record (different label) survives curriculum-off retire', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const path = tempGoalPath();
        seedGoal(path, 'collect 8 oak logs', 'curriculum');
        const gm = new GoalManager(path);
        gm.load();
        const agent = makeAgent({ goalManager: gm });
        const userRecord = agent._startTaskRecord('build me a hut', 'player', 1);
        assert.ok(userRecord, 'player record arms');
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0, 'no LLM while curriculum off');
        assert.equal(agent.dispatchCalls.length, 0, 'no dispatch while curriculum off');
        assert.equal(agent.goalManager.goal, null, 'stale curriculum goal retired');
        assert.equal(readFileSync(path, 'utf8').trim(), 'null', 'retirement persisted');
        assert.equal(agent._activeTaskRecord(), userRecord, 'independent user record stays open');
        assert.equal(userRecord.closed, false);
    } finally {
        restoreSettings(s);
    }
});

// ── F1: label-equality fallback retires an unrelated player record ──────────
// Same setup, but the user explicitly asked for the identical text
// ('collect 8 oak logs') as an independent player request. Intended: the
// player-origin record is NOT the curriculum goal and must survive.
// Actual (bridge_agent.js _retireDisabledCurriculumGoal label fallback):
// it is retired as 'curriculum-disabled', stalling the user's task.
test('F1: player record with identical label is preserved when curriculum is off (intended)', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const path = tempGoalPath();
        seedGoal(path, 'collect 8 oak logs', 'curriculum');
        const gm = new GoalManager(path);
        gm.load();
        const agent = makeAgent({ goalManager: gm });
        const userRecord = agent._startTaskRecord('collect 8 oak logs', 'player', 1);
        assert.ok(userRecord);
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.goalManager.goal, null, 'stale curriculum goal retired');
        assert.equal(userRecord.closed, false, 'INTENDED: independent player record stays open');
        assert.equal(agent._activeTaskRecord(), userRecord);
    } finally {
        restoreSettings(s);
    }
});

// ── F2: TOCTOU — switch flipped mid-tick still dispatches ───────────────────
// Tick starts with curriculum ON (gate passes), the setting flips OFF while
// the goal-tick LLM call is in flight, then the LLM returns actions.
// Intended per the _runGoalTick invariant comment: no dispatch while off.
// Actual: the gate is checked once at tick entry and never re-checked
// before _sendBatchWithBuildExpansion, so one batch dispatches while off.
test('F2: flipping curriculum off during an in-flight goal tick prevents dispatch (intended)', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = true;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const path = tempGoalPath();
        seedGoal(path, 'collect 8 oak logs', 'curriculum');
        const gm = new GoalManager(path);
        gm.load();
        const agent = makeAgent({
            goalManager: gm,
            prompt: async () => {
                settings.bridge_curriculum_enabled = false; // admin flips switch mid-LLM-await
                return '{"reply":"on it","actions":[{"type":"move","x":1,"y":64,"z":1}]}';
            },
        });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.dispatchCalls.length, 0, 'INTENDED: no dispatch once the switch is off');
    } finally {
        restoreSettings(s);
    }
});

// ── retire covers non-active (done-status) curriculum residue ───────────────
// A curriculum goal left in a terminal status still carries origin and must
// be cleared (not run) while the switch is off.
test('done-status curriculum residue is cleared with no LLM or dispatch when off', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const path = tempGoalPath();
        const gm = seedGoal(path, 'collect 8 oak logs', 'curriculum');
        gm.goal.status = 'done';
        gm.save();
        const agent = makeAgent({ goalManager: gm });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0);
        assert.equal(agent.dispatchCalls.length, 0);
        assert.equal(agent.goalManager.goal, null);
        assert.equal(readFileSync(path, 'utf8').trim(), 'null');
    } finally {
        restoreSettings(s);
    }
});
