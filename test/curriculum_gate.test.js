// Curriculum off-switch gate (local-only narrow fix): a previously saved or
// in-memory curriculum-origin goal must NOT autonomously resume when the
// curriculum is disabled. Uses the real GoalManager load/save (temp files) and
// the real BridgeAgent goal methods; only I/O (LLM, bridge, history) is stubbed.
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

const tmpRoot = mkdtempSync(join(tmpdir(), 'curriculum-gate-'));
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

// Minimal agent harness running the REAL goal/curriculum/task-record methods.
// Only LLM/bridge/history/world-memory I/O is stubbed.
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
            async getQueueState() { return { status: 'idle', pending: 0, paused: false }; },
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

// ── persistence: real GoalManager load/save round-trips origin ──────────────

test('GoalManager persists curriculum origin across load/save', () => {
    const path = tempGoalPath();
    seedGoal(path, 'collect 8 oak logs', 'curriculum');
    const reloaded = new GoalManager(path);
    reloaded.load();
    assert.equal(reloaded.goal.text, 'collect 8 oak logs');
    assert.equal(reloaded.goal.origin, 'curriculum');
    assert.equal(reloaded.isActive(), true);
});

test('GoalManager clear() persists so a restart cannot resume the goal', () => {
    const path = tempGoalPath();
    seedGoal(path, 'collect 8 oak logs', 'curriculum');
    const gm = new GoalManager(path);
    gm.load();
    gm.clear();
    assert.equal(gm.goal, null);
    const raw = readFileSync(path, 'utf8').trim();
    assert.equal(raw, 'null');
    const afterRestart = new GoalManager(path);
    afterRestart.load();
    assert.equal(afterRestart.goal, null);
    assert.equal(afterRestart.isActive(), false);
});

// ── _maybeStartCurriculumGoal gating ─────────────────────────────────────────

test('_maybeStartCurriculumGoal stays off when curriculum is disabled', () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    try {
        const agent = makeAgent({ goalManager: new GoalManager(tempGoalPath()) });
        agent.episodicMemory.lastPlayerChatAnsweredAt = 0;
        assert.equal(agent._maybeStartCurriculumGoal(idleState()), false);
        assert.equal(agent.goalManager.goal, null);
    } finally {
        restoreSettings(s);
    }
});

test('_maybeStartCurriculumGoal honors the proactive master switch', () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = true;
    settings.bridge_proactive_enabled = false;
    try {
        const agent = makeAgent({ goalManager: new GoalManager(tempGoalPath()) });
        agent.episodicMemory.lastPlayerChatAnsweredAt = 0;
        assert.equal(agent._isCurriculumAllowed(), false);
        assert.equal(agent._maybeStartCurriculumGoal(idleState()), false);
        assert.equal(agent.goalManager.goal, null);
    } finally {
        restoreSettings(s);
    }
});

test('_maybeStartCurriculumGoal still proposes when both switches are on', () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = true;
    settings.bridge_proactive_enabled = true;
    try {
        const agent = makeAgent({ goalManager: new GoalManager(tempGoalPath()) });
        agent.episodicMemory.lastPlayerChatAnsweredAt = 0;
        assert.equal(agent._maybeStartCurriculumGoal(idleState()), true);
        assert.equal(agent.goalManager.goal.origin, 'curriculum');
    } finally {
        restoreSettings(s);
    }
});

// ── _runGoalTick: curriculum goal retires with no LLM/dispatch when off ─────

test('_runGoalTick retires a persisted curriculum goal with no LLM or dispatch when curriculum is off', async () => {
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
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0, 'no LLM call for a disabled-curriculum goal');
        assert.equal(agent.dispatchCalls.length, 0, 'no action/command dispatch');
        assert.equal(agent.goalManager.goal, null, 'curriculum goal retired');
        assert.equal(readFileSync(path, 'utf8').trim(), 'null', 'retirement persisted');
        // Second tick must not revive anything.
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0);
        assert.equal(agent.goalManager.goal, null);
    } finally {
        restoreSettings(s);
    }
});

test('_runGoalTick also retires a curriculum goal when proactive master is off', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = true;
    settings.bridge_proactive_enabled = false;
    settings.bridge_goal_enabled = true;
    try {
        const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0);
        assert.equal(agent.dispatchCalls.length, 0);
        assert.equal(agent.goalManager.goal, null);
    } finally {
        restoreSettings(s);
    }
});

test('_runGoalTick retires the open curriculum task record with no learning', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
        const record = agent._startTaskRecord('collect 8 oak logs', 'curriculum', 1);
        assert.ok(record);
        await agent._runGoalTick(idleState(), 1);
        assert.equal(record.closed, true);
        assert.equal(record.closeReason, 'curriculum-disabled');
        assert.equal(agent.llmCalls.length, 0);
        assert.equal(agent.dispatchCalls.length, 0);
        assert.equal(agent.goalManager.goal, null);
    } finally {
        restoreSettings(s);
    }
});

test('explicit user goals keep existing behavior while curriculum is off', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const path = tempGoalPath();
        const gm = seedGoal(path, 'build me a hut', null);
        delete gm.goal.origin;
        gm.save();
        const agent = makeAgent({
            goalManager: gm,
            prompt: async () => '{"reply":"on it","actions":[]}',
        });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 1, 'explicit goal still reaches the LLM');
        assert.ok(agent.goalManager.goal, 'explicit goal is preserved');
        assert.equal(agent.goalManager.goal.text, 'build me a hut');
    } finally {
        restoreSettings(s);
    }
});

// ── F4: retirement runs even while the goal engine is off ────────────────────

test('disabled-curriculum residue is cleared even when the goal engine is off', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = false;
    try {
        const path = tempGoalPath();
        seedGoal(path, 'collect 8 oak logs', 'curriculum');
        const gm = new GoalManager(path);
        gm.load();
        const agent = makeAgent({ goalManager: gm });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0);
        assert.equal(agent.dispatchCalls.length, 0);
        assert.equal(agent.goalManager.goal, null, 'residue retired, not left for re-enable');
        assert.equal(readFileSync(path, 'utf8').trim(), 'null', 'retirement persisted');

        const userPath = tempGoalPath();
        const userGm = seedGoal(userPath, 'build me a hut', null);
        delete userGm.goal.origin;
        userGm.save();
        const userAgent = makeAgent({ goalManager: userGm });
        await userAgent._runGoalTick(idleState(), 1);
        assert.equal(userAgent.llmCalls.length, 0, 'engine off still means no LLM');
        assert.ok(userAgent.goalManager.goal, 'explicit goal preserved while engine off');
    } finally {
        restoreSettings(s);
    }
});

// ── F2: goal replaced mid-LLM-await is never dispatched or cleared ───────────

test('a newer explicit goal set during the goal-tick LLM await is neither dispatched nor cleared', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = true;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const agent = makeAgent({
            goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum'),
            prompt: async () => {
                // A concurrent explicit `goal:` set replaces the goal object.
                agent.goalManager.set('build me a hut', null);
                return '{"reply":"on it","actions":[{"type":"move","x":1,"y":64,"z":1}]}';
            },
        });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.dispatchCalls.length, 0, 'stale curriculum response never dispatched');
        assert.ok(agent.goalManager.goal, 'newer explicit goal not cleared');
        assert.equal(agent.goalManager.goal.text, 'build me a hut');
        assert.equal(agent.goalManager.goal.origin, undefined);
    } finally {
        restoreSettings(s);
    }
});

// ── F5: active-task STOP/cancel paths clear only the captured curriculum goal ─

test('_interruptActiveQueue clears a poised curriculum goal on explicit stop', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    try {
        const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
        const out = await agent._interruptActiveQueue('stop', 1, 'stop-request');
        assert.ok(out, 'cancel succeeded');
        assert.equal(agent.goalManager.goal, null, 'poised curriculum goal cleared');
    } finally {
        restoreSettings(s);
    }
});

test('_interruptActiveQueue preserves an explicit user goal', async () => {
    const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'build me a hut', null) });
    delete agent.goalManager.goal.origin;
    agent.goalManager.save();
    const out = await agent._interruptActiveQueue('stop', 1, 'stop-request');
    assert.ok(out, 'cancel succeeded');
    assert.ok(agent.goalManager.goal, 'explicit user goal preserved');
    assert.equal(agent.goalManager.goal.text, 'build me a hut');
});

test('_interruptActiveQueue never clears a newer goal set during the cancel await', async () => {
    const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
    agent.bridge.cancelQueue = async () => {
        agent.goalManager.set('build me a hut', null);
        return { success: true };
    };
    const out = await agent._interruptActiveQueue('stop', 1, 'stop-request');
    assert.ok(out, 'cancel succeeded');
    assert.ok(agent.goalManager.goal, 'newer goal preserved after delayed cancellation');
    assert.equal(agent.goalManager.goal.text, 'build me a hut');
});

test('active-task STOP message clears a poised curriculum goal via the real path', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
    try {
        const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
        const active = { ...idleState(), queue: { status: 'executing', pending: 1 } };
        await agent._handleActiveTaskMessage('Alex', 'stop', active, 1);
        assert.equal(agent.goalManager.goal, null, 'STOP cleared the poised curriculum goal');
    } finally {
        restoreSettings(s);
    }
});

test('explicit stop never clears a newer goal set during the cancel await', async () => {
    const agent = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
    agent.bridge.cancelQueue = async () => {
        agent.goalManager.set('build me a hut', null);
        return { success: true };
    };
    await agent._enqueueInboundMessage('Alex', 'stop', idleState());
    assert.ok(agent.goalManager.goal, 'newer goal preserved after delayed cancellation');
    assert.equal(agent.goalManager.goal.text, 'build me a hut');
});

test('explicit stop clears a poised curriculum goal but preserves an explicit user goal', async () => {
    const s = savedSettings();
    settings.bridge_curriculum_enabled = false;
    settings.bridge_proactive_enabled = true;
    try {
        const idle = idleState();
        const curr = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'collect 8 oak logs', 'curriculum') });
        await curr._enqueueInboundMessage('Alex', 'stop', idle);
        assert.equal(curr.goalManager.goal, null, 'curriculum goal cleared on explicit stop');

        const user = makeAgent({ goalManager: seedGoal(tempGoalPath(), 'build me a hut', null) });
        delete user.goalManager.goal.origin;
        user.goalManager.save();
        await user._enqueueInboundMessage('Alex', 'stop', idle);
        assert.ok(user.goalManager.goal, 'explicit user goal preserved on explicit stop');
        assert.equal(user.goalManager.goal.text, 'build me a hut');
    } finally {
        restoreSettings(s);
    }
});
