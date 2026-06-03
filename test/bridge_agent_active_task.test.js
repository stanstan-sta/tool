import test from 'node:test';
import assert from 'node:assert/strict';

import {
    describeBatchDispatchFailure,
    isActiveQueueState,
    parseBridgeResponse,
    parseActiveTaskDecision,
    pruneManualPrerequisiteCommandsBeforeCraft,
    pruneManualPrerequisitesBeforeCraft,
    shouldCancelAfterBatchDispatchFailure,
} from '../src/bridge/bridge_agent.js';
import { preprocessMineActions } from '../src/bridge/mine_preprocessor.js';

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

test('batch failure helper summarizes rejected result', () => {
    const result = {
        success: false,
        queued: 0,
        results: [
            {
                status: 'rejected',
                failure_code: 'queue_busy',
                message: 'Queue is busy',
            },
        ],
    };

    assert.equal(describeBatchDispatchFailure(result), 'queue_busy: Queue is busy');
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), true);
});

test('batch failure helper cancels timeout, fetch failure, and queue_busy errors', () => {
    // Bare {success:false} with no error/output/results does NOT nuke queue
    assert.equal(shouldCancelAfterBatchDispatchFailure({ success: false }), false);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        error: 'The operation was aborted due to timeout',
    }), true);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        error: 'Failed to fetch',
    }), true);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        error: 'invalid_action: Missing item',
    }), false);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        accepted: true,
        queued: 1,
        error: 'queue_busy: Queue is busy',
    }), false);
});

test('shouldCancel cancels on rejected results with failure codes even when queued > 0', () => {
    const result = {
        success: false,
        accepted: true,
        queued: 1,
        results: [
            { status: 'queued' },
            { status: 'rejected', failure_code: 'queue_busy', message: 'Queue is busy' },
        ],
    };
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), true);
});

test('shouldCancel returns false for recoverable non-dispatch error with queued === 0', () => {
    const result = {
        success: false,
        accepted: false,
        queued: 0,
        error: 'craft: could not plan - invalid item',
    };
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), false);
});

test('shouldCancel cancels on raw_command_forbidden in results', () => {
    const result = {
        success: false,
        accepted: false,
        queued: 0,
        results: [
            { status: 'rejected', failure_code: 'raw_command_forbidden', message: 'not allowed' },
        ],
    };
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), true);
});

test('shouldCancel cancels on unknown_action in results', () => {
    const result = {
        success: false,
        accepted: true,
        queued: 0,
        results: [
            { status: 'rejected', failure_code: 'unknown_action', message: 'Unknown action type: dance' },
        ],
    };
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), true);
});

test('shouldCancel cancels on QUEUE_FULL in results', () => {
    const result = {
        success: false,
        accepted: false,
        queued: 0,
        results: [
            { status: 'rejected', failure_code: 'QUEUE_FULL', message: 'Queue capacity reached' },
        ],
    };
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), true);
});

test('shouldCancel cancels on invalid_action in results', () => {
    const result = {
        success: false,
        accepted: false,
        queued: 0,
        results: [
            { status: 'rejected', failure_code: 'invalid_action', message: 'Invalid typed action' },
        ],
    };
    assert.equal(shouldCancelAfterBatchDispatchFailure(result), true);
});

test('structured bridge response preserves commands', () => {
    const parsed = parseBridgeResponse('{"reply":"","commands":["#sleep"],"actions":[]}', true);

    assert.equal(parsed.structured, true);
    assert.deepEqual(parsed.commands, ['#sleep']);
    assert.deepEqual(parsed.actions, []);
});

test('structured bridge response normalizes sleep command aliases', () => {
    const parsed = parseBridgeResponse('{"reply":"","commands":["sleep","#task sleep"],"actions":[]}', true);

    assert.deepEqual(parsed.commands, ['#sleep', '#sleep']);
});

test('multi-object bridge response preserves commands', () => {
    const parsed = parseBridgeResponse('{"reply":""}\n{"commands":["#sleep"]}', true);

    assert.equal(parsed.structured, true);
    assert.deepEqual(parsed.commands, ['#sleep']);
});

test('nether mine followed by overworld move inserts return action', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'netherrack', count: 15 },
            { type: 'move', x: 477, y: 102, z: 30 },
        ],
        { dimension: 'minecraft:overworld', x: 470, y: 102, z: 28 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'mine',
        'return_to_overworld',
        'move',
    ]);
    assert.equal(processed[1].target, 'netherrack');
});

test('nether mine followed by come-back coordinate saves overworld waypoint before movement', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'netherrack', count: 15 },
            {
                type: 'move',
                x: 477,
                y: 102,
                z: 30,
                playerName: 'Chengeration',
                createdAt: '2026-06-03T00:00:00.000Z',
            },
        ],
        { dimension: 'minecraft:overworld', x: 470, y: 102, z: 28 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'mine',
        'return_to_overworld',
        'move',
    ]);
    assert.deepEqual(processed[2].waypoint, {
        dimension: 'minecraft:overworld',
        x: 477,
        y: 102,
        z: 30,
        playerName: 'Chengeration',
        createdAt: '2026-06-03T00:00:00.000Z',
    });
});

test('raw mine command batches get dimension-safe return before goto', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'raw_command', command: '#mine 15 netherrack' },
            { type: 'raw_command', command: '#goto 477 102 30' },
        ],
        { dimension: 'minecraft:overworld', x: 470, y: 102, z: 28 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'raw_command',
        'return_to_overworld',
        'raw_command',
    ]);
    assert.equal(processed[1].command, '#mine 15 netherrack');
});

test('raw goto after nether mine carries saved overworld waypoint on return action', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'raw_command', command: '#mine 15 netherrack' },
            {
                type: 'raw_command',
                command: '#goto 477 102 30',
                playerName: 'Chengeration',
                createdAt: '2026-06-03T00:00:00.000Z',
            },
        ],
        { dimension: 'minecraft:overworld', x: 470, y: 102, z: 28 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'raw_command',
        'return_to_overworld',
        'raw_command',
    ]);
    assert.deepEqual(processed[2].waypoint, {
        dimension: 'minecraft:overworld',
        x: 477,
        y: 102,
        z: 30,
        playerName: 'Chengeration',
        createdAt: '2026-06-03T00:00:00.000Z',
    });
});

test('explicit return waypoint is preserved on inserted return action', async () => {
    const waypoint = {
        dimension: 'minecraft:overworld',
        x: 12,
        y: 70,
        z: -9,
        playerName: 'Alex',
        createdAt: '2026-06-03T01:02:03.000Z',
    };
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'netherrack', count: 2 },
            { type: 'move', x: 477, y: 102, z: 30, returnWaypoint: waypoint },
        ],
        { dimension: 'minecraft:overworld', x: 470, y: 102, z: 28 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'mine',
        'return_to_overworld',
        'move',
    ]);
    assert.deepEqual(processed[2].waypoint, waypoint);
});

test('manual mine and smelt prerequisites before craft are pruned', () => {
    const actions = pruneManualPrerequisitesBeforeCraft([
        { type: 'mine', target: 'iron_ore', count: 3 },
        { type: 'raw_command', command: '#task smelt raw_iron' },
        { type: 'craft', item: 'iron_pickaxe', count: 1 },
        { type: 'mine', target: 'coal_ore', count: 1 },
    ]);

    assert.deepEqual(actions, [
        { type: 'craft', item: 'iron_pickaxe', count: 1 },
        { type: 'mine', target: 'coal_ore', count: 1 },
    ]);
});

test('manual raw prerequisite commands are pruned when a craft action exists', () => {
    const commands = pruneManualPrerequisiteCommandsBeforeCraft(
        ['#mine iron_ore deepslate_iron_ore', '#task smelt raw_iron', '#sleep'],
        [{ type: 'craft', item: 'iron_pickaxe', count: 1 }],
    );

    assert.deepEqual(commands, ['#sleep']);
});

// ── mine_preprocessor additional tests ──────────────────────────────

test('overworld mine netherrack only appends return at end', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'netherrack', count: 15 },
        ],
        { dimension: 'minecraft:overworld', x: 470, y: 102, z: 28 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'mine',
        'return_to_overworld',
    ]);
    assert.equal(processed[1].target, 'netherrack');
});

test('nether mine overworld target with subsequent non-mine action preserves return', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'stone', count: 10 },
            { type: 'move', x: 0, y: 64, z: 0 },
        ],
        { dimension: 'minecraft:the_nether', x: -10, y: 80, z: 20 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'return_to_overworld',
        'mine',
        'portal_travel',
        'move',
    ]);
    // First action returns to overworld, last travel returns to nether
    assert.equal(processed[0].type, 'return_to_overworld');
    assert.equal(processed[2].dimension, 'nether');
});

test('end-origin mine overworld target returns to end after non-mine action', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'stone', count: 4 },
            { type: 'move', x: 8, y: 70, z: 8 },
        ],
        { dimension: 'minecraft:the_end', x: 0, y: 70, z: 0 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed.map(action => action.type), [
        'return_to_overworld',
        'mine',
        'portal_travel',
        'move',
    ]);
    assert.equal(processed[2].dimension, 'end');
});

test('adjacent duplicate mine actions are suppressed after canonicalization', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'raw_iron', count: 3 },
            { type: 'mine', target: 'iron_ore', count: 3 },
            { type: 'raw_command', command: '#mine 5 raw_iron' },
            { type: 'raw_command', command: '#mine 5 iron_ore' },
        ],
        { dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
        { readBlocks: async () => null },
    );

    assert.deepEqual(processed, [
        { type: 'mine', target: 'iron_ore', count: 3 },
        { type: 'raw_command', provider: 'baritone_chat', command: '#mine 5 iron_ore' },
    ]);
});

test('raw #mine command preserves count and canonicalizes target', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'raw_command', command: '#mine 5 raw_iron' },
        ],
        { dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
        { readBlocks: async () => null },
    );

    assert.equal(processed[0].type, 'raw_command');
    assert.equal(processed[0].command, '#mine 5 iron_ore');
});

test('raw #mine command without count preserves zero-count canonical command', async () => {
    const processed = await preprocessMineActions(
        [
            { type: 'raw_command', command: '#mine diamond_ore' },
        ],
        { dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
        { readBlocks: async () => null },
    );

    assert.equal(processed[0].type, 'raw_command');
    assert.equal(processed[0].command, '#mine diamond_ore');
});

test('invalid y/z does not call readBlocks', async () => {
    let readBlocksCalled = false;
    const processed = await preprocessMineActions(
        [
            { type: 'mine', target: 'netherrack', count: 5 },
        ],
        { dimension: 'minecraft:overworld', x: 100, y: null, z: undefined },
        {
            readBlocks: async () => {
                readBlocksCalled = true;
                return null;
            },
        },
    );

    assert.equal(readBlocksCalled, false);
    // Without valid position, nearby probe is skipped; portal travel is still prepended
    assert.deepEqual(processed.map(action => action.type), [
        'portal_travel',
        'mine',
        'return_to_overworld',
    ]);
});

test('shouldCancelAfterBatchDispatchFailure bare false no longer cancels', () => {
    assert.equal(shouldCancelAfterBatchDispatchFailure({ success: false }), false);
    assert.equal(shouldCancelAfterBatchDispatchFailure({ success: false, error: null }), false);
    assert.equal(shouldCancelAfterBatchDispatchFailure({ success: false, output: '' }), false);
});

test('shouldCancelAfterBatchDispatchFailure queue_busy and invalid_action still cancel', () => {
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        results: [{ status: 'rejected', failure_code: 'queue_busy' }],
    }), true);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        results: [{ status: 'rejected', failure_code: 'invalid_action' }],
    }), true);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        results: [{ status: 'rejected', failure_code: 'unknown_action' }],
    }), true);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        results: [{ status: 'rejected', failure_code: 'queue_full' }],
    }), true);
    assert.equal(shouldCancelAfterBatchDispatchFailure({
        success: false,
        results: [{ status: 'rejected', failure_code: 'raw_command_forbidden' }],
    }), true);
});
