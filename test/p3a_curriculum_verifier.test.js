// W5 P3a regressions: verifier block→drop mapping + curriculum goal logic.
// Each test fails on the pre-fix source and passes after the bounded fix.
// Uses the real Curriculum / outcome_verifier modules and the real
// BridgeAgent goal-tick methods; only LLM/bridge/history I/O is stubbed.
import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';
import { GoalManager } from '../src/bridge/goal_manager.js';
import { Curriculum, MILESTONES } from '../src/bridge/curriculum.js';
import { expectFromActions, verifyOutcome, snapshotInventory } from '../src/bridge/outcome_verifier.js';
import { default as settings } from '../src/agent/settings.js';
import { serverProxy } from '../src/agent/mindserver_proxy.js';

serverProxy.socket = { emit() {} };

const tmpRoot = mkdtempSync(join(tmpdir(), 'p3a-'));
after(() => rmSync(tmpRoot, { recursive: true, force: true }));

let fileSeq = 0;
function tempPath(name) {
    fileSeq += 1;
    return join(tmpRoot, `${name}-${Date.now()}-${fileSeq}.json`);
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
function enableAll() {
    settings.bridge_curriculum_enabled = true;
    settings.bridge_proactive_enabled = true;
    settings.bridge_goal_enabled = true;
}

const idleState = () => ({
    connected: true,
    inventory: [],
    x: 0, y: 64, z: 0, dimension: 'minecraft:overworld',
    queue: { status: 'idle', pending: 0 },
});

// Minimal harness running the REAL goal/curriculum/tick methods.
function makeAgent({ goalManager, prompt = () => Promise.resolve(''), curriculum = null, freshState = null } = {}) {
    const agent = Object.create(BridgeAgent.prototype);
    const historyEntries = [];
    const llmCalls = [];
    const dispatchCalls = [];
    const announced = [];
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
        survivalReflex: { fleeHp: 6 },
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
        announced,
        _buildStateContext: () => '',
        _announceGoal(msg) { announced.push(String(msg)); },
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
            async getState() { return freshState || agent._lastState; },
            async getQueueState() { return { status: 'idle', pending: 0, paused: false }; },
        },
    });
    return agent;
}

function seedCurriculumGoal(path, milestone, origin = 'curriculum') {
    const gm = new GoalManager(path);
    gm.set(milestone.text, { ...milestone.target });
    gm.goal.origin = origin;
    gm.save();
    return gm;
}

// ── N1: mining expectations must name drops, via one shared map ──────────────

test('N1: expectFromActions maps mined blocks to their drops', () => {
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'iron_ore', count: 3 }]),
        [{ item: 'raw_iron', expectedGain: 3 }],
    );
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'stone', count: 2 }]),
        [{ item: 'cobblestone', expectedGain: 2 }],
    );
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'coal_ore' }]),
        [{ item: 'coal', expectedGain: 1 }],
    );
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'diamond_ore' }]),
        [{ item: 'diamond', expectedGain: 1 }],
    );
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'deepslate_iron_ore', count: 2 }]),
        [{ item: 'raw_iron', expectedGain: 2 }],
    );
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'deepslate_diamond_ore' }]),
        [{ item: 'diamond', expectedGain: 1 }],
    );
});

test('N1: one shared block→drop map drives both verifier and curriculum goal check', async () => {
    const ov = await import('../src/bridge/outcome_verifier.js');
    assert.ok(ov.BLOCK_DROPS && typeof ov.BLOCK_DROPS === 'object', 'shared map is exported');
    assert.ok(ov.BLOCK_DROPS.iron_ore === 'raw_iron', 'map covers the audited cases');
    const agent = makeAgent({ goalManager: new GoalManager(tempPath('goal')) });
    assert.equal(typeof agent.curriculum.countForGoal, 'function', 'curriculum exposes goal-side counting');
    // Agreement over every shared entry: the verifier expects the drop, and a
    // goal naming the mined block is satisfied by that same drop.
    for (const [block, drop] of Object.entries(ov.BLOCK_DROPS)) {
        assert.deepEqual(
            expectFromActions([{ type: 'mine', target: block }]),
            [{ item: drop, expectedGain: 1 }],
            `verifier maps ${block} to ${drop}`,
        );
        const got = agent.curriculum.countForGoal(
            { text: `mine 1 ${block}`, target: { item: block, count: 1 } },
            [{ item: drop, count: 1 }],
        );
        assert.equal(got, 1, `goal check for ${block} counts its drop ${drop}`);
    }
});

test('N1: mined-drop verification passes end to end', () => {
    const actions = [{ type: 'mine', target: 'iron_ore', count: 3 }];
    const v = verifyOutcome(
        snapshotInventory({ inventory: [] }),
        { inventory: [{ item: 'raw_iron', count: 3 }] },
        expectFromActions(actions),
    );
    assert.equal(v.met, true, 'mining iron_ore verifies against raw_iron gains');
});

test('N1: mine then craft subtracts consumed intermediates instead of false-failing', () => {
    const actions = [
        { type: 'mine', target: 'oak_log', count: 3 },
        { type: 'craft', item: 'oak_planks', count: 4 },
    ];
    const expectations = expectFromActions(actions);
    assert.deepEqual(expectations, [
        { item: 'oak_log', expectedGain: 2 },
        { item: 'oak_planks', expectedGain: 4 },
    ]);
    const v = verifyOutcome(
        snapshotInventory({ inventory: [] }),
        { inventory: [{ item: 'oak_log', count: 2 }, { item: 'oak_planks', count: 4 }] },
        expectations,
    );
    assert.equal(v.met, true, 'correct mine -> craft execution must be learnable');
});

test('N1: craft consumption is traced through transitive recipe intermediates', () => {
    const actions = [
        { type: 'mine', target: 'oak_log', count: 1 },
        { type: 'craft', item: 'crafting_table', count: 1 },
    ];
    const expectations = expectFromActions(actions);
    assert.deepEqual(expectations, [{ item: 'crafting_table', expectedGain: 1 }]);
    const v = verifyOutcome(
        snapshotInventory({ inventory: [] }),
        { inventory: [{ item: 'crafting_table', count: 1 }] },
        expectations,
    );
    assert.equal(v.met, true);
});

test('N1: common non-self block drops and variable gravel drops verify correctly', () => {
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'grass_block', count: 2 }]),
        [{ item: 'dirt', expectedGain: 2 }],
    );
    const gravel = expectFromActions([{ type: 'mine', target: 'gravel', count: 2 }]);
    assert.deepEqual(gravel, [{
        item: 'gravel|flint',
        items: ['gravel', 'flint'],
        expectedGain: 2,
    }]);
    assert.equal(verifyOutcome(
        snapshotInventory({ inventory: [] }),
        { inventory: [{ item: 'gravel', count: 1 }, { item: 'flint', count: 1 }] },
        gravel,
    ).met, true);
    assert.deepEqual(
        expectFromActions([{ type: 'mine', target: 'oak_leaves', count: 8 }]),
        [],
        'RNG/tool-dependent leaf drops are unverifiable rather than false failures',
    );
});

// ── N2: curriculum goals accept the same equivalents have() does ─────────────

test('N2: curriculum goal completion accepts equivalent items', () => {
    const c = new Curriculum();
    assert.equal(typeof c.isGoalMet, 'function', 'curriculum exposes equivalent-aware goal check');
    assert.equal(
        c.isGoalMet({ text: 'collect 8 oak logs', target: { item: 'oak_log', count: 8 } },
            [{ item: 'birch_log', count: 8 }]),
        true,
    );
    assert.equal(
        c.isGoalMet({ text: 'collect 6 cooked food', target: { item: 'cooked_beef', count: 6 } },
            [{ item: 'cooked_chicken', count: 6 }]),
        true,
    );
    assert.equal(
        c.isGoalMet({ text: 'craft 1 stone pickaxe', target: { item: 'stone_pickaxe', count: 1 } },
            [{ item: 'iron_pickaxe', count: 1 }]),
        true,
    );
    assert.equal(
        c.isGoalMet({ text: 'collect 8 oak logs', target: { item: 'oak_log', count: 8 } },
            [{ item: 'birch_log', count: 7 }]),
        false,
        'short counts still fail',
    );
});

test('N2: active curriculum goal with equivalents completes on tick without LLM', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const agent = makeAgent({ goalManager: seedCurriculumGoal(tempPath('goal'), MILESTONES[0]) });
        const state = { ...idleState(), inventory: [{ item: 'minecraft:birch_log', count: 8 }] };
        await agent._runGoalTick(state, 1);
        assert.equal(agent.llmCalls.length, 0, 'no LLM call once equivalents satisfy the goal');
        assert.equal(agent.goalManager.goal, null, 'equivalent-satisfied goal completes');
        assert.ok(agent.announced.some(m => /Goal complete/.test(m)));
    } finally {
        restoreSettings(s);
    }
});

// ── F2: completed milestones persist so consumption cannot regress ────────────

test('F2: completed milestones persist across Curriculum instances', () => {
    const filePath = tempPath('curriculum');
    const c = new Curriculum({ filePath });
    assert.equal(typeof c.markComplete, 'function', 'curriculum exposes completion persistence');
    c.markComplete(MILESTONES[0].text);
    const reloaded = new Curriculum({ filePath });
    assert.ok(reloaded.completed.has(MILESTONES[0].text), 'completion survives reload');
    // Even with an empty inventory (logs consumed by crafting), the milestone
    // stays done and the next milestone is proposed.
    assert.equal(reloaded.proposeNext({ inventory: [] }).text, MILESTONES[1].text);
    assert.deepEqual(reloaded.progress({ inventory: [] }).done >= 1, true);
});

test('F2: consuming a resource does not move curriculum progression backwards', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const filePath = tempPath('curriculum');
        const curriculum = new Curriculum({ filePath });
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal'), MILESTONES[1]),
            curriculum,
        });
        // Earn the log milestone, then consume the logs: progress must hold.
        curriculum.markComplete(MILESTONES[0].text);
        const before = curriculum.progress({ inventory: [] });
        agent.curriculum.setFilePath && agent.curriculum.setFilePath(filePath);
        const after = agent.curriculum.progress({ inventory: [{ item: 'crafting_table', count: 1 }] });
        assert.ok(after.done >= before.done, `progress holds (${before.done} -> ${after.done})`);
        assert.equal(agent.curriculum.proposeNext({ inventory: [] }).text !== MILESTONES[0].text, true);
    } finally {
        restoreSettings(s);
    }
});

// ── F3: goal_done must verify the target; false claims cool down ──────────────

test('F3: false goal_done clears the goal but defers the milestone', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal'), MILESTONES[0]),
            prompt: async () => '{"reply":"done","goal_done": true}',
        });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.goalManager.goal, null, 'unmet goal still clears (no stuck loop)');
        assert.equal(agent.dispatchCalls.length, 0, 'nothing dispatched on an unverified claim');
        const until = agent.curriculum.deferred.get(MILESTONES[0].text);
        assert.ok(until && until > Date.now(), 'unmet milestone is deferred');
        assert.notEqual(
            agent.curriculum.proposeNext(idleState(), null)?.text,
            MILESTONES[0].text,
            'deferred milestone is not immediately re-proposed',
        );
    } finally {
        restoreSettings(s);
    }
});

test('F3: verified goal_done is not deferred', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal'), MILESTONES[0]),
            prompt: async () => '{"reply":"done","goal_done": true}',
        });
        const state = { ...idleState(), inventory: [{ item: 'oak_log', count: 8 }] };
        await agent._runGoalTick(state, 1);
        assert.equal(agent.goalManager.goal, null, 'met goal completes deterministically');
        assert.equal(agent.curriculum.deferred.get(MILESTONES[0].text), undefined, 'met goals are never deferred');
    } finally {
        restoreSettings(s);
    }
});

test('F3: goal_done verifies against fresh post-inference state', async () => {
    const saved = savedSettings();
    enableAll();
    try {
        const fresh = { ...idleState(), inventory: [{ item: 'oak_log', count: 8 }] };
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal-fresh'), MILESTONES[0]),
            prompt: () => Promise.resolve('{"reply":"done","goal_done":true}'),
            freshState: fresh,
        });
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.goalManager.goal, null, 'freshly satisfied goal completes');
        assert.equal(agent.curriculum.deferred.get(MILESTONES[0].text), undefined);
    } finally {
        restoreSettings(saved);
    }
});

test('F3: unavailable fresh verification preserves a target goal', async () => {
    const saved = savedSettings();
    enableAll();
    try {
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal-unavailable'), MILESTONES[0]),
            prompt: () => Promise.resolve('{"reply":"done","goal_done":true}'),
        });
        agent.bridge.getState = () => Promise.resolve(null);
        await agent._runGoalTick(idleState(), 1);
        assert.ok(agent.goalManager.goal, 'missing verification state is not treated as a false claim');
        assert.equal(agent.curriculum.deferred.get(MILESTONES[0].text), undefined);
    } finally {
        restoreSettings(saved);
    }
});

// ── N6: quiet period applies to active curriculum ticks ───────────────────────

test('N6: active curriculum goal pauses while the player is talking', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal'), MILESTONES[0]),
            prompt: async () => '{"reply":"working","actions":[]}',
        });
        agent.episodicMemory.lastPlayerChatAnsweredAt = Date.now();
        await agent._runGoalTick(idleState(), 1);
        assert.equal(agent.llmCalls.length, 0, 'no LLM call during the quiet period');
        assert.equal(agent.dispatchCalls.length, 0, 'no dispatch during the quiet period');
        assert.ok(agent.goalManager.goal, 'paused curriculum goal is preserved, not cleared');
    } finally {
        restoreSettings(s);
    }
});

// ── N7/F12: low-HP / death / open-GUI guards on the active curriculum loop ────

test('N7/F12: active curriculum goal pauses on death, low HP and open GUI', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const states = {
            dead: { ...idleState(), health: 0 },
            lowHpFlag: { ...idleState(), health: 8, low_hp_flag: true },
            lowHpNumeric: { ...idleState(), health: 5 },
            guiOpen: { ...idleState(), health: 20, open_screen: { open: true } },
        };
        for (const [kind, state] of Object.entries(states)) {
            const agent = makeAgent({
                goalManager: seedCurriculumGoal(tempPath(`goal-${kind}`), MILESTONES[0]),
                prompt: async () => '{"reply":"working","actions":[]}',
            });
            await agent._runGoalTick(state, 1);
            assert.equal(agent.llmCalls.length, 0, `no LLM call while ${kind}`);
            assert.equal(agent.dispatchCalls.length, 0, `no dispatch while ${kind}`);
            assert.ok(agent.goalManager.goal, `curriculum goal preserved while ${kind}`);
        }
    } finally {
        restoreSettings(s);
    }
});

test('N7/F12: healthy closed-GUI curriculum goal still ticks', async () => {
    const s = savedSettings();
    enableAll();
    try {
        const agent = makeAgent({
            goalManager: seedCurriculumGoal(tempPath('goal'), MILESTONES[0]),
            prompt: async () => '{"reply":"working","actions":[]}',
        });
        await agent._runGoalTick({ ...idleState(), health: 20 }, 1);
        assert.equal(agent.llmCalls.length, 1, 'healthy tick still reaches the LLM');
    } finally {
        restoreSettings(s);
    }
});
