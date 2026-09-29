import test from 'node:test';
import assert from 'node:assert/strict';

import {
    BridgeAgent,
    describeBatchDispatchFailure,
    isActiveQueueState,
    mergeBridgeState,
    parseBridgeResponse,
    parseActiveTaskDecision,
    pruneManualPrerequisiteCommandsBeforeCraft,
    pruneManualPrerequisitesBeforeCraft,
    shouldCancelAfterBatchDispatchFailure,
} from '../src/bridge/bridge_agent.js';
import { preprocessMineActions } from '../src/bridge/mine_preprocessor.js';

test('unchanged bridge state merges fresh events onto the previous full snapshot', () => {
    const previous = {
        connected: true,
        seq: 10,
        player_name: 'Miku',
        x: 12,
        y: 64,
        z: -8,
        health: 5,
        nearby_entities: [{ type: 'minecraft:zombie', distance: 3 }],
        inventory: [{ item: 'minecraft:torch', count: 16 }],
        queue: { status: 'executing', pending: 2 },
        chat: ['old'],
        chat_events: [{ type: 'player', message: '<Alex> old' }],
        recent_events: ['old event'],
    };
    const sparse = {
        connected: true,
        seq: 11,
        player_name: 'Miku',
        unchanged: true,
        chat: ['new'],
        chat_events: [{ type: 'player', message: '<Alex> new' }],
        recent_events: ['new event'],
    };

    const merged = mergeBridgeState(previous, sparse);

    assert.equal(merged.seq, 11);
    assert.equal(merged.x, 12);
    assert.equal(merged.y, 64);
    assert.equal(merged.z, -8);
    assert.equal(merged.health, 5);
    assert.deepEqual(merged.nearby_entities, previous.nearby_entities);
    assert.deepEqual(merged.inventory, previous.inventory);
    assert.deepEqual(merged.queue, previous.queue);
    assert.deepEqual(merged.chat, ['new']);
    assert.deepEqual(merged.chat_events, sparse.chat_events);
    assert.deepEqual(merged.recent_events, ['new event']);
});

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

test('active task response rejects fenced JSON', () => {
    const decision = parseActiveTaskDecision('```json\n{"decision":"append_after_current","actions":[{"type":"craft","item":"torch"}]}\n```');
    assert.equal(decision.valid, false);
    assert.deepEqual(decision.actions, []);
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

test('multi-object bridge response cannot dispatch commands', () => {
    const parsed = parseBridgeResponse('{"reply":""}\n{"commands":["#sleep"]}', true);

    assert.equal(parsed.structured, false);
    assert.deepEqual(parsed.commands, []);
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

test('observation keeps receiving chat while slow message reasoning is outstanding', async () => {
    const states = [
        {
            connected: true,
            seq: 1,
            player_name: 'Miku',
            x: 0,
            y: 64,
            z: 0,
            health: 20,
            inventory: [],
            chat_events: [{ type: 'player', sender: 'Alex', message: '<Alex> first request' }],
            recent_events: [],
        },
        {
            connected: true,
            seq: 2,
            player_name: 'Miku',
            x: 1,
            y: 64,
            z: 0,
            health: 20,
            inventory: [],
            chat_events: [{ type: 'player', sender: 'Alex', message: '<Alex> second request' }],
            recent_events: [],
        },
    ];
    let releaseFirst;
    let markFirstStarted;
    const firstBlocked = new Promise(resolve => { releaseFirst = resolve; });
    const firstStarted = new Promise(resolve => { markFirstStarted = resolve; });
    const handled = [];
    let polls = 0;
    const agent = Object.create(BridgeAgent.prototype);
    Object.assign(agent, {
        stopped: false,
        name: 'Miku',
        _generation: 100,
        _inboundQueue: [],
        _reasoningQueue: [],
        _reasoningKeys: new Set(),
        _reasoningWorkerPromise: null,
        _lastState: null,
        _lastStateSeq: null,
        _lastStateStr: '',
        _bridgeReachable: true,
        _pollIntervalMs: 2000,
        _recentSentChats: [],
        _nextWorldRecordAt: Number.POSITIVE_INFINITY,
        _pendingContinuation: false,
        _lastHadActions: false,
        bridge: {
            async getState() {
                const state = states[polls];
                polls += 1;
                return state;
            },
        },
        survivalReflex: { evaluate: () => null },
        eventDetector: { check: () => [] },
        history: { add() {}, save() {} },
        worldMemory: { recordSighting() {} },
        async _tickBuildValidation() {},
        async _runAmbientTick() {},
        async _runGoalTick() {},
        async _handleActiveTaskMessage() {},
        async _handleMessage(source, message) {
            handled.push({ source, message });
            if (message === 'first request') {
                markFirstStarted();
                await firstBlocked;
            }
        },
    });

    await agent._runObservationCycle();
    await firstStarted;
    await agent._runObservationCycle();

    assert.equal(polls, 2);
    assert.equal(agent._lastState.seq, 2);
    assert.equal(agent._inboundQueue.length, 1);
    assert.equal(agent._inboundQueue[0].message, 'second request');

    releaseFirst();
    await agent._reasoningWorkerPromise;
    assert.deepEqual(handled.map(item => item.message), ['first request', 'second request']);
});

test('late model response from a stale generation does not enqueue work', async () => {
    let releaseResponse;
    const delayedResponse = new Promise(resolve => { releaseResponse = resolve; });
    let dispatches = 0;
    const historyEntries = [];
    const agent = Object.create(BridgeAgent.prototype);
    Object.assign(agent, {
        name: 'Miku',
        _generation: 200,
        _knownItems: new Set(),
        _lastState: { connected: true, x: 0, y: 64, z: 0, inventory: [] },
        history: {
            memory: '',
            add(role, content) { historyEntries.push({ role, content }); },
            getHistory() { return []; },
            save() {},
        },
        episodicMemory: { lastPlayerChatAnsweredAt: 0 },
        worldMemory: null,
        _buildStateContext: () => '',
        _promptConvoLocked: async () => await delayedResponse,
        async _sendBatchWithBuildExpansion() {
            dispatches += 1;
            return { success: true, queued: 1 };
        },
        _updateEpisodicMemory() {},
    });

    const handling = agent._handleMessage(
        'Alex',
        'walk east',
        agent._lastState,
        200,
    );
    agent._generation = 201;
    releaseResponse('{"reply":"","actions":[{"type":"move","x":10,"y":64,"z":0}]}');
    await handling;

    assert.equal(dispatches, 0);
    assert.equal(historyEntries.some(entry => entry.role === 'Miku'), false);
});

test('superseded inbound message is kept in history', async () => {
    const handled = [];
    const historyAdds = [];
    let release; const blocked = new Promise(r => { release = r; });
    let started; const hasStarted = new Promise(r => { started = r; });

    const agent = Object.create(BridgeAgent.prototype);
    Object.assign(agent, {
        stopped: false, name: 'Miku', _generation: 100,
        _inboundQueue: [], _reasoningQueue: [], _reasoningKeys: new Set(),
        _reasoningWorkerPromise: null, _lastState: { connected: true }, _pollIntervalMs: 2000,
        bridge: {},
        history: { add(src, msg) { historyAdds.push(msg); }, save() {} },
        async _handleActiveTaskMessage() {},
        async _handleMessage(source, message) {
            handled.push(message);
            if (message === 'slow one') { started(); await blocked; }
        },
    });

    await agent._enqueueInboundMessage('Alex', 'slow one');
    await hasStarted;
    await agent._enqueueInboundMessage('Alex', 'follow me');
    await agent._enqueueInboundMessage('Alex', 'actually get wood');
    release();
    await agent._reasoningWorkerPromise;

    assert.deepEqual(handled, ['slow one', 'actually get wood'], 'latest request wins for dispatch');
    assert.deepEqual(historyAdds, ['follow me'], 'superseded message is still recorded');
});

function systemOneActiveAgent(choice, confidence, llmResponse, order) {
    const agent = Object.create(BridgeAgent.prototype);
    const others = (1 - confidence) / 2;
    const probs = { continue: others, cancel_replace: others, append_after_current: others, [choice]: confidence };
    Object.assign(agent, {
        name: 'Miku', _generation: 7, dispatched: [], episodicMemory: {},
        history: { add() {}, async save() {} },
        _systemOne: { async decide() { order.push('system-one'); return { choice, probs, ms: 80 }; } },
        _logSystemOneShadow() {},
        _buildStateContext() { return 'CURRENT STATE: test'; },
        async _pollForQueueIdle() { return true; },
        bridge: { async cancelQueue(generation) { order.push(`cancel@${generation}`); return { success: true }; } },
        async _promptConvoLocked() { order.push('llm'); return llmResponse; },
        _armVerification() {},
        async _sendBatchWithBuildExpansion(actions, generation) {
            order.push(`dispatch@${generation}`); agent.dispatched.push(...actions);
            return { success: true, queued: actions.length };
        },
        _recordQueueDispatch() {},
    });
    return agent;
}

async function withSystemOneActive(fn) {
    const { default: settings } = await import('../src/agent/settings.js');
    const { serverProxy } = await import('../src/agent/mindserver_proxy.js');
    const saved = [settings.bridge_system_one_active, serverProxy.socket];
    settings.bridge_system_one_active = true;
    serverProxy.socket = { emit() {} };
    try { await fn(); } finally { [settings.bridge_system_one_active, serverProxy.socket] = saved; }
}

test('active System One stops the task before the LLM answers, then dispatches the replacement', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('cancel_replace', 0.9,
            '{"decision":"cancel_replace","reply":"ok","actions":[{"type":"follow","target":"Alex"}]}', order);
        await agent._handleActiveTaskMessage('Alex', 'stop and follow me', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(order, ['system-one', 'cancel@8', 'llm', 'dispatch@8']);
        assert.equal(agent.dispatched[0].type, 'follow');
    });
});

test('active System One below the confidence floor defers to the LLM', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('cancel_replace', 0.4, '{"decision":"continue","reply":"","actions":[]}', order);
        await agent._handleActiveTaskMessage('Alex', 'hmm stop?', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(order, ['system-one', 'llm']);
    });
});

test('active System One continue rejects repeated conflicting LLM decisions', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('continue', 0.8,
            '{"decision":"append_after_current","reply":"","actions":[{"type":"follow","target":"Alex"}]}', order);
        await agent._handleActiveTaskMessage('Alex', 'nice work!', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(order, ['system-one', 'llm', 'llm']);
        assert.equal(agent.dispatched.length, 0);
    });
});

test('a bare stop phrase cancels before the LLM even when System One says continue', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('continue', 0.9, '{"decision":"continue","reply":"okay","actions":[]}', order);
        await agent._handleActiveTaskMessage('Alex', 'nvm', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(order, ['cancel@8']);
    });
});

test('quoted examples and malformed envelopes never become executable actions', () => {
    const samples = [
        'Do not execute this example:\n```json\n{"type":"mine","target":"diamond_ore","count":64}\n```',
        '{"type":"mine","target":"diamond_ore"}',
        '{"reply":"example"}\n{"actions":[{"type":"cancel"}]}',
        'COMMAND: #cancel',
        '{"reply":"okay","actions":{"type":"cancel"}}',
        '{"reply":"okay","actions":[null]}',
        '{"commands":[{"command":"#cancel"}]}',
    ];
    for (const text of samples) {
        for (const structured of [true, false]) {
            const parsed = parseBridgeResponse(text, structured);
            assert.equal(parsed.structured, false, text);
            assert.deepEqual(parsed.actions, []);
            assert.deepEqual(parsed.commands, []);
        }
        assert.equal(parseActiveTaskDecision(text).valid, false);
    }
});

test('both models continuing cannot turn a negated craft request into actions', async () => {
    await withSystemOneActive(async () => {
        const agent = systemOneActiveAgent('continue', 0.9, '{"decision":"continue","actions":[]}', []);
        await agent._handleActiveTaskMessage('Alex', 'Do not craft a crafting table', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(agent.dispatched, []);
        assert.equal(agent._generation, 7);
    });
});

test('bare stop never dispatches even if the unused model response contains actions', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('append_after_current', 0.9,
            '{"decision":"append_after_current","actions":[{"type":"follow","target":"Alex"}]}', order);
        await agent._handleActiveTaskMessage('Alex', 'nvm', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(order, ['cancel@8']);
        assert.deepEqual(agent.dispatched, []);
    });
});

test('append policy removes cancellation from typed and raw payloads', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('append_after_current', 0.99, JSON.stringify({
            decision: 'append_after_current', actions: [
                { type: 'cancel' }, { type: 'cancel_build' },
                { type: 'raw_command', command: '#stop' }, { type: 'raw_command', command: '#cancel_build' },
                { type: 'follow', target: 'Alex' },
            ], commands: ['#cancel', 'stop', '#cancel_build'],
        }), order);
        await agent._handleActiveTaskMessage('Alex', 'follow me after you finish', { queue: { status: 'executing' } }, 7);
        assert.deepEqual(agent.dispatched, [{ type: 'follow', target: 'Alex' }]);
        assert.deepEqual(order, ['system-one', 'llm', 'dispatch@7']);
    });
});

test('cancel handoff provides current policy, idle state, recent history and user-role request', async () => {
    await withSystemOneActive(async () => {
        const agent = systemOneActiveAgent('cancel_replace', 0.9, '', []);
        const turns = [{ role: 'user', content: 'Alex: My target is the red house at 10 64 20' }];
        agent.history = {
            add(role, content) { turns.push({ role: role === 'Alex' ? 'user' : role, content: role === 'Alex' ? `Alex: ${content}` : content }); },
            getHistory() { return turns; }, async save() {},
        };
        let sent;
        agent._promptConvoLocked = (label, messages) => {
            sent = messages;
            return '{"decision":"cancel_replace","actions":[{"type":"move","x":10,"y":64,"z":20}]}';
        };
        await agent._handleActiveTaskMessage('Alex', 'go back there instead', { queue: { status: 'executing', active: '#mine iron_ore' } }, 7);
        assert.match(sent[0].content, /Authoritative decision: cancel_replace/);
        assert.match(sent[0].content, /Cancellation succeeded/);
        assert.equal(sent[0].name, 'bridge_policy');
        assert.equal(sent[1].role, 'user');
        assert.match(sent[1].content, /\\"status\\":\\"idle\\"/);
        assert.ok(sent.some(turn => turn.content.includes('red house')));
        assert.deepEqual(sent.at(-1), { role: 'user', content: 'Alex: go back there instead' });
        assert.ok(!sent[0].content.includes('go back there instead'));
        const { buildPromptPackQuery } = await import('../src/bridge/bridge_prompt_retriever.js');
        assert.match(buildPromptPackQuery({ history: sent }), /go back there instead/);
        let exampleQuery;
        agent._bridgeExamples = { getRelevantSnippets(query) { exampleQuery = query; return []; } };
        await agent._retrieveBridgeExamplesForHistory(sent);
        assert.equal(exampleQuery, 'Alex: go back there instead');
    });
});

test('one repair turn resolves disagreement before publishing reply or work', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('cancel_replace', 0.9, '', order);
        let calls = 0;
        agent._promptConvoLocked = () => ++calls === 1
            ? '{"decision":"continue","reply":"I will keep mining","actions":[]}'
            : '{"decision":"cancel_replace","reply":"Switching","actions":[{"type":"follow","target":"Alex"}]}';
        await agent._handleActiveTaskMessage('Alex', 'follow me instead', { queue: { status: 'executing' } }, 7);
        assert.equal(calls, 2);
        assert.deepEqual(agent.dispatched, [{ type: 'follow', target: 'Alex' }]);
        assert.deepEqual(order, ['system-one', 'cancel@8', 'dispatch@8']);
    });
});

test('fast cancellation proceeds while the previous slow response is pending', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('continue', 0.9, '', order);
        Object.assign(agent, {
            stopped: false, _inboundQueue: [], _reasoningQueue: [], _reasoningKeys: new Set(),
            _reasoningWorkerPromise: null, _lastState: { queue: { status: 'executing', active: '#mine iron_ore' } },
        });
        let releaseFirst;
        let startedFirst;
        const firstStarted = new Promise(resolve => { startedFirst = resolve; });
        const firstResponse = new Promise(resolve => { releaseFirst = resolve; });
        let calls = 0;
        agent._systemOne.decide = state => {
            const choice = state.playerMessage.text.includes('instead') ? 'cancel_replace' : 'continue';
            order.push(`fast:${choice}`);
            return { choice, probs: { [choice]: 0.9 }, ms: 1 };
        };
        agent._promptConvoLocked = async () => {
            if (++calls === 1) { startedFirst(); return await firstResponse; }
            return '{"decision":"cancel_replace","actions":[{"type":"follow","target":"Alex"}]}';
        };
        await agent._enqueueInboundMessage('Alex', 'nice work');
        await firstStarted;
        await agent._enqueueInboundMessage('Alex', 'follow me instead');
        await agent._inboundQueue[0].prepared;
        assert.ok(order.includes('cancel@10'), 'cancel happens before the old model response is released');
        releaseFirst('{"decision":"continue","actions":[]}');
        await agent._reasoningWorkerPromise;
        assert.deepEqual(agent.dispatched, [{ type: 'follow', target: 'Alex' }]);
    });
});

test('superseded fast decision cannot cancel the newer request', async () => {
    await withSystemOneActive(async () => {
        const order = [];
        const agent = systemOneActiveAgent('cancel_replace', 0.9, '', order);
        let release;
        agent._systemOne.decide = () => new Promise(resolve => { release = resolve; });
        const preparing = agent._prepareActiveTaskMessage('Alex', 'follow instead', { queue: { status: 'executing' } }, 7);
        agent._generation = 8;
        release({ choice: 'cancel_replace', probs: { cancel_replace: 0.9 }, ms: 1 });
        assert.equal(await preparing, null);
        assert.deepEqual(order, []);
    });
});
