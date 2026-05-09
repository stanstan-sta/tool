import test from 'node:test';
import assert from 'node:assert/strict';

import {
    isActiveQueueState,
    parseActiveTaskDecision,
} from '../src/bridge/bridge_agent.js';

test('active queue state only includes executing and draining', () => {
    assert.equal(isActiveQueueState({ queue: { status: 'executing' } }), true);
    assert.equal(isActiveQueueState({ queue: { status: 'draining' } }), true);
    assert.equal(isActiveQueueState({ queue: { status: 'paused' } }), false);
    assert.equal(isActiveQueueState({ queue: { status: 'failed' } }), false);
});

test('continue decision parses with no queue work', () => {
    const decision = parseActiveTaskDecision('{"decision":"continue","reply":"nice","actions":[],"commands":[]}');

    assert.equal(decision.valid, true);
    assert.equal(decision.decision, 'continue');
    assert.equal(decision.reply, 'nice');
    assert.deepEqual(decision.actions, []);
    assert.deepEqual(decision.commands, []);
});

test('cancel_replace decision parses actions and commands', () => {
    const decision = parseActiveTaskDecision(JSON.stringify({
        decision: 'cancel_replace',
        reply: 'on it',
        actions: [
            { type: 'follow', provider: 'baritone_chat', target: 'Chengeration' },
            '#stop',
        ],
        commands: ['#come'],
    }));

    assert.equal(decision.valid, true);
    assert.equal(decision.decision, 'cancel_replace');
    assert.deepEqual(decision.actions, [
        { type: 'follow', provider: 'baritone_chat', target: 'Chengeration' },
        { type: 'raw_command', command: '#stop' },
    ]);
    assert.deepEqual(decision.commands, ['#come']);
});

test('append_after_current decision parses fenced JSON', () => {
    const decision = parseActiveTaskDecision('```json\n{"decision":"append_after_current","reply":"","actions":[{"type":"craft","item":"torch","count":4}],"commands":[]}\n```');

    assert.equal(decision.valid, true);
    assert.equal(decision.decision, 'append_after_current');
    assert.deepEqual(decision.actions, [
        { type: 'craft', item: 'torch', count: 4 },
    ]);
});

test('normal action JSON during active task infers append or cancel intent', () => {
    const append = parseActiveTaskDecision(
        '{"reply":"okay","actions":[{"type":"craft","item":"torch","count":4}]}',
        'after that craft torches',
    );
    const replace = parseActiveTaskDecision(
        '{"reply":"okay","actions":[{"type":"follow","target":"Chengeration"}]}',
        'come here instead',
    );

    assert.equal(append.valid, true);
    assert.equal(append.decision, 'append_after_current');
    assert.deepEqual(append.actions, [
        { type: 'craft', item: 'torch', count: 4 },
    ]);

    assert.equal(replace.valid, true);
    assert.equal(replace.decision, 'cancel_replace');
    assert.deepEqual(replace.actions, [
        { type: 'follow', target: 'Chengeration' },
    ]);
});

test('invalid active task response defaults to continue without work', () => {
    const invalidJson = parseActiveTaskDecision('not json');
    const unknownDecision = parseActiveTaskDecision('{"decision":"dance","actions":[{"type":"move"}]}');

    for (const decision of [invalidJson, unknownDecision]) {
        assert.equal(decision.valid, false);
        assert.equal(decision.decision, 'continue');
        assert.deepEqual(decision.actions, []);
        assert.deepEqual(decision.commands, []);
    }
});
