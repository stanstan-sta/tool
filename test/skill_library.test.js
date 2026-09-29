import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { SkillLibrary, canonicalActions, formatSkillContext, normaliseTask } from '../src/bridge/skill_library.js';
import { Curriculum, MILESTONES } from '../src/bridge/curriculum.js';
import { verifyOutcome, snapshotInventory, expectFromActions } from '../src/bridge/outcome_verifier.js';

function tempLibrary(embeddingModel = null) {
    const dir = mkdtempSync(join(tmpdir(), 'skills-'));
    const lib = new SkillLibrary(join(dir, 'skills.json'), embeddingModel);
    return { lib, dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function verify(before, after, actions) {
    return verifyOutcome(snapshotInventory({ inventory: before }), { inventory: after }, expectFromActions(actions));
}

const craftTable = [{ type: 'craft', item: 'minecraft:crafting_table', count: 1, x: 10, y: 64, z: 3 }];

test('canonicalActions drops coordinates and normalises item ids', () => {
    assert.deepEqual(canonicalActions(craftTable), [{ count: 1, item: 'crafting_table', type: 'craft' }]);
    assert.deepEqual(canonicalActions([null, { foo: 1 }]), []);
    assert.equal(normaliseTask('  Make me a Crafting-Table! '), 'make me a crafting table');
});

test('a verified batch becomes a trusted skill and is retrieved for similar tasks', async () => {
    const { lib, cleanup } = tempLibrary();
    try {
        const v = verify([], [{ item: 'crafting_table', count: 1 }], craftTable);
        assert.equal(v.met, true);
        const skill = await lib.recordOutcome('make me a crafting table', craftTable, v);
        assert.equal(skill.successes, 1);
        assert.ok(SkillLibrary.isTrusted(skill));

        const again = await lib.recordOutcome('Make me a crafting table', craftTable, v);
        assert.equal(again.id, skill.id, 'same task + same plan reuses the skill');
        assert.equal(again.successes, 2);

        const { skills } = await lib.retrieve('can you make a crafting table');
        assert.equal(skills.length, 1);
        assert.match(formatSkillContext({ skills }), /PROVEN PLANS[\s\S]*crafting_table/);

        const unrelated = await lib.retrieve('fight the zombies near the river');
        assert.equal(unrelated.skills.length, 0);
    } finally { cleanup(); }
});

test('failed batches become lessons and demote the matching skill', async () => {
    const { lib, cleanup } = tempLibrary();
    try {
        const ok = verify([], [{ item: 'crafting_table', count: 1 }], craftTable);
        const bad = verify([], [], craftTable);
        assert.equal(bad.met, false);
        await lib.recordOutcome('make me a crafting table', craftTable, ok);
        await lib.recordOutcome('make me a crafting table', craftTable, bad);
        const lesson = await lib.recordOutcome('make me a crafting table', craftTable, bad);
        assert.match(lesson.text, /crafting_table \+0\/1/);
        assert.equal(lib.failureCount('Make me a crafting table'), 2);

        const { skills, lessons } = await lib.retrieve('make me a crafting table');
        assert.equal(skills.length, 0, 'a skill that fails more than it succeeds is no longer trusted');
        assert.ok(lessons.length >= 1);
        assert.match(formatSkillContext({ skills, lessons }), /PAST FAILURES/);
    } finally { cleanup(); }
});

test('skills persist across load() and ignore empty input', async () => {
    const { lib, dir, cleanup } = tempLibrary();
    try {
        assert.equal(await lib.recordOutcome('', craftTable, { met: true, results: [] }), null);
        assert.equal(await lib.recordOutcome('task', [], { met: true, results: [] }), null);
        await lib.recordOutcome('make me a crafting table', craftTable, verify([], [{ item: 'crafting_table', count: 1 }], craftTable));
        assert.ok(existsSync(join(dir, 'skills.json')));
        const reloaded = new SkillLibrary(join(dir, 'skills.json'));
        reloaded.load();
        assert.equal(reloaded.data.skills.length, 1);
        assert.ok(reloaded.masteredTasks().has('make me a crafting table'));
    } finally { cleanup(); }
});

test('retrieval uses embeddings when available and falls back when embedding fails', async () => {
    const vecs = { a: [1, 0], b: [0, 1] };
    const model = { embed: text => Promise.resolve(/table/.test(text) ? vecs.a : vecs.b) };
    const { lib, cleanup } = tempLibrary(model);
    try {
        await lib.recordOutcome('crafting table please', craftTable, verify([], [{ item: 'crafting_table', count: 1 }], craftTable));
        assert.deepEqual(lib.data.skills[0].embedding, vecs.a);
        assert.equal((await lib.retrieve('need a table')).skills.length, 1);
        assert.equal((await lib.retrieve('go fishing')).skills.length, 0);

        lib.embeddingModel = { embed: () => Promise.reject(new Error('offline')) };
        const lexical = await lib.retrieve('crafting table please');
        assert.equal(lib.embeddingModel, null);
        assert.equal(lexical.skills.length, 1);
    } finally { cleanup(); }
});

test('curriculum proposes the first unmet milestone and accepts equivalents', () => {
    const c = new Curriculum();
    assert.equal(c.proposeNext({ inventory: [] }).text, MILESTONES[0].text);
    const withLogs = { inventory: [{ item: 'minecraft:birch_log', count: 9 }] };
    assert.equal(c.proposeNext(withLogs).text, 'craft 1 crafting table');
    const stoneAge = { inventory: [
        { item: 'spruce_log', count: 8 }, { item: 'crafting_table', count: 1 },
        { item: 'stone_pickaxe', count: 1 }, { item: 'cobblestone', count: 20 },
    ] };
    assert.equal(c.proposeNext(stoneAge).text, 'craft 1 stone sword');
    assert.deepEqual(c.progress(stoneAge), { done: 5, total: MILESTONES.length });
});

test('curriculum defers milestones that keep failing', () => {
    const c = new Curriculum({ maxFailures: 2 });
    const library = { failureCount: t => (t === MILESTONES[0].text ? 2 : 0) };
    assert.equal(c.proposeNext({ inventory: [] }, library).text, MILESTONES[1].text);
    c.defer(MILESTONES[1].text);
    assert.equal(c.proposeNext({ inventory: [] }, library).text, MILESTONES[2].text);
    const all = new Curriculum({ milestones: [MILESTONES[0]] });
    all.defer(MILESTONES[0].text);
    assert.equal(all.proposeNext({ inventory: [] }), null);
});

test('BridgeAgent turns a verified batch into a skill and starts curriculum goals when idle', async () => {
    const { BridgeAgent } = await import('../src/bridge/bridge_agent.js');
    const { default: settings } = await import('../src/agent/settings.js');
    const { GoalManager } = await import('../src/bridge/goal_manager.js');
    const { lib, dir, cleanup } = tempLibrary();
    const saved = { enabled: settings.bridge_curriculum_enabled, reward: settings.bridge_reward_enabled };
    try {
        settings.bridge_reward_enabled = true;
        const agent = Object.create(BridgeAgent.prototype);
        const historyEntries = [];
        Object.assign(agent, {
            name: 'Miku',
            _taskRecord: null,
            _taskSeq: 0,
            _lastTaskLabel: '',
            _lastTaskAttempt: 0,
            _generation: 1,
            _pendingContinuation: false,
            _lastHadActions: false,
            _rewardLogPath: join(dir, 'reward.log'),
            _currentTaskText: '',
            _lastState: { connected: true, inventory: [] },
            history: { add(role, content) { historyEntries.push({ role, content }); } },
            skillLibrary: lib,
            bridge: {
                async getState() { return { connected: true, queue: { status: 'idle', pending: 0 }, inventory: [{ item: 'minecraft:crafting_table', count: 1 }] }; },
                async getQueueState() { return { status: 'idle', pending: 0, paused: false }; },
            },
        });
        const record = agent._startTaskRecord('make me a crafting table', 'player', 1);
        assert.equal(record.label, 'make me a crafting table');
        assert.equal(agent._trackDispatchResult(record, craftTable, { success: true, queued: 1 }).accepted, true);
        agent._lastState = { connected: true, inventory: [{ item: 'minecraft:crafting_table', count: 1 }] };
        await agent._closeTaskWithVerification('plan-complete', agent._lastState, record.id);
        assert.equal(record.closed, true);
        assert.equal(lib.data.skills.length, 1);
        assert.ok(historyEntries.some(entry => entry.role === 'system' && /Outcome verification met/.test(entry.content)));
        agent._currentTaskText = 'craft a crafting table';
        assert.match(await agent._retrieveSkillsForTask(), /PROVEN PLANS/);

        // Curriculum: off by default, then on once idle and quiet.
        Object.assign(agent, {
            goalManager: new GoalManager(join(dir, 'goal.json')),
            curriculum: new Curriculum(),
            episodicMemory: { lastPlayerChatAnsweredAt: 0 },
            _pendingContinuation: false, _lastHadActions: false, _promptInFlight: false,
            _announceGoal() {},
        });
        const idle = { connected: true, inventory: [], queue: { status: 'idle' } };
        settings.bridge_curriculum_enabled = false;
        assert.equal(agent._maybeStartCurriculumGoal(idle), false);
        settings.bridge_curriculum_enabled = true;
        agent.episodicMemory.lastPlayerChatAnsweredAt = Date.now();
        assert.equal(agent._maybeStartCurriculumGoal(idle), false, 'recent player chat blocks practice');
        agent.episodicMemory.lastPlayerChatAnsweredAt = 0;
        assert.equal(agent._maybeStartCurriculumGoal({ ...idle, queue: { status: 'executing' } }), false);
        assert.equal(agent._maybeStartCurriculumGoal(idle), true);
        assert.equal(agent.goalManager.goal.text, MILESTONES[0].text);
        assert.equal(agent.goalManager.goal.origin, 'curriculum');
        assert.deepEqual(agent.goalManager.goal.target, MILESTONES[0].target);
    } finally {
        settings.bridge_curriculum_enabled = saved.enabled;
        settings.bridge_reward_enabled = saved.reward;
        cleanup();
    }
});
