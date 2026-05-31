import test from 'node:test';
import assert from 'node:assert/strict';

import {
    BridgeProbeError,
    assertBlockEquals,
    assertInventoryAtLeast,
    assertPositionNear,
    countInventoryItem,
    normalizeItemId,
    parseArgs,
    runScenario,
    scenarioNamesForArgs,
    scenarioRegistry,
    waitForQueueIdle,
} from '../scripts/bridge_action_probe.mjs';

test('normalizes and counts inventory items', () => {
    const state = {
        inventory: [
            { item: 'minecraft:stick', count: 2 },
            { item: 'stick', count: 3 },
            { item: 'minecraft:oak_planks', count: 4 },
        ],
    };

    assert.equal(normalizeItemId('stick'), 'minecraft:stick');
    assert.equal(normalizeItemId('minecraft:stick'), 'minecraft:stick');
    assert.equal(countInventoryItem(state, 'stick'), 5);
    assert.equal(assertInventoryAtLeast(state, 'stick', 4), 5);
    assert.throws(
        () => assertInventoryAtLeast(state, 'diamond', 1),
        /Expected inventory minecraft:diamond >= 1/
    );
});

test('position and block assertions report failures clearly', () => {
    assert.ok(assertPositionNear({ x: 10, y: 64, z: 10 }, { x: 11, y: 64, z: 10 }, 2) <= 2);
    assert.throws(
        () => assertPositionNear({ x: 10, y: 64, z: 10 }, { x: 20, y: 64, z: 10 }, 2),
        /Expected position within 2 blocks/
    );

    assert.equal(assertBlockEquals({ blocks: ['minecraft:gold_block'] }, 0, 'gold_block'), 'minecraft:gold_block');
    assert.throws(
        () => assertBlockEquals({ blocks: ['minecraft:air'] }, 0, 'gold_block'),
        /Expected block minecraft:gold_block/
    );
});

test('queue polling succeeds when queue becomes idle', async () => {
    const states = [
        { status: 'executing', active: '#goto 1 64 1', pending: 0, paused: false },
        { status: 'idle', active: null, pending: 0, paused: false },
    ];
    const bridge = {
        getQueueState() {
            return Promise.resolve(states.shift() || { status: 'idle', pending: 0, paused: false });
        },
    };

    const result = await waitForQueueIdle(bridge, { timeoutMs: 1000, pollMs: 1 });
    assert.equal(result.ok, true);
    assert.equal(result.status, 'idle');
});

test('queue polling fails on paused state', async () => {
    const bridge = {
        getQueueState() {
            return Promise.resolve({ status: 'paused', lastFailure: 'mine failed', pending: 0, paused: true });
        },
    };

    await assert.rejects(
        waitForQueueIdle(bridge, { timeoutMs: 1000, pollMs: 1 }),
        /Queue paused: mine failed/
    );
});

test('queue polling times out with last queue state in details', async () => {
    const bridge = {
        getQueueState() {
            return Promise.resolve({ status: 'executing', active: '#mine 1 coal_ore', pending: 0, paused: false });
        },
    };

    await assert.rejects(
        waitForQueueIdle(bridge, { timeoutMs: 5, pollMs: 1 }),
        err => {
            assert.equal(err instanceof BridgeProbeError, true);
            assert.equal(err.details.queue.active, '#mine 1 coal_ore');
            return /Queue did not become idle/.test(err.message);
        }
    );
});

test('scenario wrapper cancels queue after failure', async () => {
    let cancelled = false;
    const scenario = await runScenario('failing', () => {
        throw new Error('boom');
    }, {
        bridge: {
            cancelQueue() {
                cancelled = true;
                return Promise.resolve({ success: true });
            },
        },
    });

    assert.equal(scenario.ok, false);
    assert.equal(cancelled, true);
    assert.equal(scenario.error.message, 'boom');
});

test('argument parser selects scenario sets', () => {
    const smoke = parseArgs([]);
    assert.deepEqual(scenarioNamesForArgs(smoke), ['preflight']);

    const full = parseArgs(['--full', '--timeout-ms', '1234']);
    assert.equal(full.full, true);
    assert.equal(full.queueTimeoutMs, 1234);
    assert.ok(scenarioNamesForArgs(full).includes('build_schematic'));

    const selected = parseArgs(['--scenario', 'move', '--scenario=craft']);
    assert.deepEqual(scenarioNamesForArgs(selected), ['preflight', 'move', 'craft']);
});

test('preflight rejects wrong protocol version', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            protocol_version: 2,
            action_types: ['move'],
        }),
        getState: () => Promise.resolve({ connected: true, player_name: 'test' }),
        getQueueState: () => Promise.resolve({ status: 'idle' }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, false);
    assert.ok(result.error.message.includes('protocol_version=1'));
});

test('preflight rejects missing protocol version', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            action_types: ['move'],
        }),
        getState: () => Promise.resolve({ connected: true, player_name: 'test' }),
        getQueueState: () => Promise.resolve({ status: 'idle' }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, false);
    assert.ok(result.error.message.includes('protocol_version=1'));
});

test('preflight rejects missing actions array', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            protocol_version: 1,
            action_types: ['move'],
        }),
        getState: () => Promise.resolve({ connected: true, player_name: 'test' }),
        getQueueState: () => Promise.resolve({ status: 'idle' }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, false);
    assert.ok(result.error.message.includes('capabilities.actions must be an array'));
});

test('preflight rejects missing state_fields array', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            protocol_version: 1,
            action_types: ['move'],
            actions: [],
        }),
        getState: () => Promise.resolve({ connected: true, player_name: 'test' }),
        getQueueState: () => Promise.resolve({ status: 'idle' }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, false);
    assert.ok(result.error.message.includes('capabilities.state_fields must be an array'));
});

test('preflight rejects missing queue_fields array', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            protocol_version: 1,
            action_types: ['move'],
            actions: [],
            state_fields: ['selected_slot', 'held_items', 'equipment', 'effects', 'xp', 'open_screen'],
        }),
        getState: () => Promise.resolve({
            connected: true,
            player_name: 'test',
            selected_slot: 0,
            held_items: { main_hand: null, offhand: null },
            equipment: { head: null, chest: null, legs: null, feet: null },
            effects: [],
            xp: { level: 0, progress: 0 },
            open_screen: { open: false, screen_class: null, handler_class: null, sync_id: 0 },
        }),
        getQueueState: () => Promise.resolve({ status: 'idle', active_id: null, active_action_type: null }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, false);
    assert.ok(result.error.message.includes('capabilities.queue_fields must be an array'));
});

test('preflight passes with all Phase 1 fields present', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            protocol_version: 1,
            action_types: ['move'],
            actions: [{
                type: 'move', lifecycle: 'queued',
                required: ['x', 'y', 'z'], optional: [],
                description: 'Walk to coordinates',
            }],
            state_fields: ['selected_slot', 'held_items', 'equipment', 'effects', 'xp', 'open_screen'],
            queue_fields: ['active_id', 'active_action_type', 'active', 'kind', 'completion', 'pending', 'paused', 'lastFailure', 'status'],
        }),
        getState: () => Promise.resolve({
            connected: true,
            player_name: 'test',
            x: 0, y: 64, z: 0,
            selected_slot: 0,
            held_items: { main_hand: null, offhand: null },
            equipment: { head: null, chest: null, legs: null, feet: null },
            effects: [],
            xp: { level: 0, progress: 0 },
            open_screen: { open: false, screen_class: null, handler_class: null, sync_id: 0 },
        }),
        getQueueState: () => Promise.resolve({
            status: 'idle',
            active_id: null,
            active_action_type: null,
            active: null,
        }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, true);
});

test('preflight rejects queue state missing active_id', async () => {
    const bridge = {
        ping: () => Promise.resolve({ ok: true }),
        getCapabilities: () => Promise.resolve({
            supports_typed_actions: true,
            protocol_version: 1,
            action_types: ['move'],
            actions: [],
            state_fields: ['selected_slot', 'held_items', 'equipment', 'effects', 'xp', 'open_screen'],
            queue_fields: ['active_id', 'active_action_type'],
        }),
        getState: () => Promise.resolve({
            connected: true,
            player_name: 'test',
            x: 0, y: 64, z: 0,
            selected_slot: 0,
            held_items: { main_hand: null, offhand: null },
            equipment: { head: null, chest: null, legs: null, feet: null },
            effects: [],
            xp: { level: 0, progress: 0 },
            open_screen: { open: false, screen_class: null, handler_class: null, sync_id: 0 },
        }),
        getQueueState: () => Promise.resolve({
            status: 'idle',
            active: null,
        }),
    };

    const result = await runScenario('preflight', scenarioRegistry.preflight, { bridge });
    assert.equal(result.ok, false);
    assert.ok(result.error.message.includes("Queue state missing field 'active_id'"));
});
