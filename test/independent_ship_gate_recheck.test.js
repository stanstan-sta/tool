// Phase-2 bounded recheck: focused verification of the repaired gate.
// Scope: GATE-ONLY. The original adversarial file
// (independent_ship_gate.test.js) is preserved UNCHANGED; these three cases
// verify the NEW repair code only: STOP-path identity ownership across the
// cancel await, post-inference goal-identity ownership, and F4 retire-before-
// goal-engine-check ordering. Real GoalManager temp persistence and real
// BridgeAgent methods; only I/O (LLM, bridge, history) is stubbed.
// No production or existing-test changes. No live processes or secrets.
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

const tmpRoot = mkdtempSync(join(tmpdir(), 'independent-ship-gate-recheck-'));
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

// ── F5 repair: delayed STOP cancellation cannot clear a newer goal ─────────
// A newer explicit goal landing during the cancelQueue await (new object, no
// origin) must survive; the captured poised curriculum goal is gone already.
test('STOP during cancel await clears only the captured curriculum goal; newer explicit goal survives', async () => {
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
        agent.bridge.cancelQueue = async () => {
            agent.goalManager.set('build me a hut', null); // newer explicit goal mid-await
            return { success: true };
        };
        await agent._enqueueInboundMessage('Alex', 'stop', idleState());
        assert.ok(agent.goalManager.goal, 'a goal remains');
        assert.equal(agent.goalManager.goal.text, 'build me a hut', 'newer explicit goal survives delayed clear');
        assert.ok(!agent.goalManager.goal.origin, 'survivor carries no curriculum origin');
    } finally {
        restoreSettings(s);
    }
});

// ── F2 repair, second half: goal replaced mid-inference is never dispatched
// nor attributed to the newer goal ──────────────────────────────────────────
// Tick starts on a curriculum goal (switch ON); an explicit user goal replaces
// it during the LLM await. No dispatch may occur and the new goal is kept.
test('goal replaced during an in-flight goal tick is never dispatched; newer explicit goal survives', async () => {
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
                agent.goalManager.set('build me a hut', null); // replacement mid-await
                return '{"reply":"on it","actions":[{"type":"move","x":1,"y":64,"z":1}]}';
            },
        });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.dispatchCalls.length, 0, 'stale curriculum response never dispatches');
        assert.ok(agent.goalManager.goal, 'goal remains');
        assert.equal(agent.goalManager.goal.text, 'build me a hut', 'newer explicit goal preserved');
    } finally {
        restoreSettings(s);
    }
});

// ── F4 repair: retire runs before the goal-engine-off early return ─────────
// With the goal engine itself OFF and curriculum OFF, a persisted curriculum
// goal must still be retired (not linger into a later re-enable).
test('persisted curriculum goal is retired even when the goal engine is off', async () => {
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
        assert.equal(agent.llmCalls.length, 0, 'no LLM while off');
        assert.equal(agent.dispatchCalls.length, 0, 'no dispatch while off');
        assert.equal(agent.goalManager.goal, null, 'residue retired, not lingered');
        assert.equal(readFileSync(path, 'utf8').trim(), 'null', 'retirement persisted');
    } finally {
        restoreSettings(s);
    }
});
