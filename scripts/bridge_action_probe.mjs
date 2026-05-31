#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

export const DEFAULT_BRIDGE_URL = 'http://localhost:8765';
export const DEFAULT_REPORT_DIR = 'test-results/bridge-action-probe';

const DEFAULT_REQUEST_TIMEOUT_MS = 8000;
const DEFAULT_QUEUE_TIMEOUT_MS = 180000;
const DEFAULT_POLL_MS = 500;

export class BridgeProbeError extends Error {
    constructor(message, details = {}) {
        super(message);
        this.name = 'BridgeProbeError';
        this.details = details;
    }
}

export class BridgeProbeClient {
    constructor(url = DEFAULT_BRIDGE_URL, options = {}) {
        this.url = String(url || DEFAULT_BRIDGE_URL).replace(/\/$/, '');
        this.requestTimeoutMs = options.requestTimeoutMs || DEFAULT_REQUEST_TIMEOUT_MS;
    }

    async request(endpoint, options = {}) {
        const method = options.method || 'GET';
        const timeoutMs = options.timeoutMs || this.requestTimeoutMs;
        const init = {
            method,
            headers: options.body == null ? undefined : { 'Content-Type': 'application/json' },
            body: options.body == null ? undefined : JSON.stringify(options.body),
            signal: AbortSignal.timeout(timeoutMs),
        };

        let response;
        try {
            response = await fetch(`${this.url}${endpoint}`, init);
        } catch (err) {
            throw new BridgeProbeError(`Request failed: ${method} ${endpoint}: ${err.message}`, {
                endpoint,
                method,
                cause: err.message,
            });
        }

        const text = await response.text();
        let data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch {
                data = text;
            }
        }

        if (!response.ok) {
            throw new BridgeProbeError(`HTTP ${response.status}: ${method} ${endpoint}`, {
                endpoint,
                method,
                status: response.status,
                body: data,
            });
        }
        return data;
    }

    ping() {
        return this.request('/ping', { timeoutMs: 2500 });
    }

    getCapabilities() {
        return this.request('/capabilities', { timeoutMs: 3000 });
    }

    getState(options = {}) {
        const query = [];
        if (options.since != null) query.push(`since=${encodeURIComponent(String(options.since))}`);
        if (options.surface === true) query.push('surface=true');
        if (options.surfaceRadius != null) query.push(`surface_radius=${encodeURIComponent(String(options.surfaceRadius))}`);
        return this.request(`/state${query.length ? `?${query.join('&')}` : ''}`, { timeoutMs: 4000 });
    }

    getQueueState() {
        return this.request('/queue/state', { timeoutMs: 3000 });
    }

    sendCommand(command) {
        return this.request('/command', {
            method: 'POST',
            body: { command },
            timeoutMs: 8000,
        });
    }

    sendAction(action) {
        return this.request('/action', {
            method: 'POST',
            body: { action },
            timeoutMs: 25000,
        });
    }

    sendBatch(actions) {
        return this.request('/batch', {
            method: 'POST',
            body: { actions },
            timeoutMs: 30000,
        });
    }

    cancelQueue() {
        return this.request('/queue/cancel', {
            method: 'POST',
            timeoutMs: 5000,
        });
    }

    readBlocks({ x, y, z, w, h, l }) {
        const query = new URLSearchParams({
            x: String(x),
            y: String(y),
            z: String(z),
            w: String(w),
            h: String(h),
            l: String(l),
        });
        return this.request(`/read_blocks?${query}`, { timeoutMs: 6000 });
    }
}

export function normalizeItemId(item) {
    if (!item) return '';
    return String(item).startsWith('minecraft:') ? String(item) : `minecraft:${item}`;
}

export function countInventoryItem(state, item) {
    const wanted = normalizeItemId(item);
    return (state?.inventory || [])
        .filter(entry => normalizeItemId(entry.item) === wanted)
        .reduce((sum, entry) => sum + Number(entry.count || 0), 0);
}

export function assertInventoryAtLeast(state, item, count) {
    const actual = countInventoryItem(state, item);
    if (actual < count) {
        throw new BridgeProbeError(`Expected inventory ${normalizeItemId(item)} >= ${count}, got ${actual}`, {
            item: normalizeItemId(item),
            expected: count,
            actual,
        });
    }
    return actual;
}

export function assertPositionNear(state, target, tolerance = 2.5) {
    const dx = Number(state.x) - target.x;
    const dy = Number(state.y) - target.y;
    const dz = Number(state.z) - target.z;
    const distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (!Number.isFinite(distance) || distance > tolerance) {
        throw new BridgeProbeError(`Expected position within ${tolerance} blocks, got ${distance.toFixed(2)}`, {
            target,
            actual: { x: state.x, y: state.y, z: state.z },
            distance,
        });
    }
    return distance;
}

export function assertBlockEquals(readResult, index, blockId) {
    const actual = readResult?.blocks?.[index];
    const expected = normalizeItemId(blockId);
    if (actual !== expected) {
        throw new BridgeProbeError(`Expected block ${expected} at index ${index}, got ${actual || 'missing'}`, {
            expected,
            actual,
            index,
            readResult,
        });
    }
    return actual;
}

export function hasNearbyEntity(state, fragment) {
    const needle = String(fragment).toLowerCase();
    return (state?.nearby_entities || []).some(entity =>
        String(entity.type || '').toLowerCase().includes(needle)
    );
}

export function anchorNear(state, dx = 6, dz = 6) {
    return {
        x: Math.floor(Number(state.x || 0)) + dx,
        y: Math.floor(Number(state.y || 64)),
        z: Math.floor(Number(state.z || 0)) + dz,
    };
}

export function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

export async function waitForQueueIdle(bridge, options = {}) {
    const timeoutMs = options.timeoutMs ?? DEFAULT_QUEUE_TIMEOUT_MS;
    const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
    const startedAt = Date.now();
    let lastState = null;

    while (Date.now() - startedAt < timeoutMs) {
        lastState = await bridge.getQueueState();
        const status = lastState?.status;
        if (status === 'paused') {
            throw new BridgeProbeError(`Queue paused: ${lastState.lastFailure || 'unknown failure'}`, {
                queue: lastState,
            });
        }
        if (status === 'idle' || status === 'disabled') {
            return {
                ok: true,
                status,
                queue: lastState,
                elapsedMs: Date.now() - startedAt,
            };
        }
        await sleep(pollMs);
    }

    throw new BridgeProbeError(`Queue did not become idle within ${timeoutMs}ms`, {
        queue: lastState,
        timeoutMs,
    });
}

export async function waitForCondition(probe, options = {}) {
    const timeoutMs = options.timeoutMs ?? 30000;
    const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
    const startedAt = Date.now();
    let lastValue;

    while (Date.now() - startedAt < timeoutMs) {
        lastValue = await probe();
        if (lastValue) {
            return {
                ok: true,
                value: lastValue,
                elapsedMs: Date.now() - startedAt,
            };
        }
        await sleep(pollMs);
    }

    throw new BridgeProbeError(`Condition was not satisfied within ${timeoutMs}ms`, {
        timeoutMs,
        lastValue,
    });
}

export async function runCommand(bridge, command, settleMs = 250) {
    const result = await bridge.sendCommand(command.startsWith('/') ? command : `/${command}`);
    if (result?.success === false) {
        throw new BridgeProbeError(`Command failed: ${command}`, { result });
    }
    if (settleMs > 0) await sleep(settleMs);
    return result;
}

export async function runCommands(bridge, commands, settleMs = 250) {
    const results = [];
    for (const command of commands) {
        results.push(await runCommand(bridge, command, settleMs));
    }
    return results;
}

export async function resetProbeArea(bridge, anchor, options = {}) {
    const radius = options.radius ?? 7;
    const floorY = anchor.y - 1;
    await bridge.cancelQueue().catch(() => null);
    await runCommands(bridge, [
        'gamerule commandBlockOutput false',

        'gamerule sendCommandFeedback false',

        'gamerule doMobSpawning false',
        'weather clear',
        'effect clear @s',
        'clear @s',
        `kill @e[type=!player,distance=..${radius + 8}]`,
        `fill ${anchor.x - radius} ${anchor.y} ${anchor.z - radius} ${anchor.x + radius} ${anchor.y + 5} ${anchor.z + radius} minecraft:air`,
        `fill ${anchor.x - radius} ${floorY} ${anchor.z - radius} ${anchor.x + radius} ${floorY} ${anchor.z + radius} minecraft:grass_block`,
    ], options.commandSettleMs ?? 150);
}

export async function runScenario(name, fn, context) {
    const startedAt = Date.now();
    const scenario = {
        name,
        ok: false,
        startedAt: new Date(startedAt).toISOString(),
        elapsedMs: 0,
        error: null,
        details: {},
    };

    try {
        scenario.details = await fn(context) || {};
        scenario.ok = true;
    } catch (err) {
        scenario.error = serializeError(err);
        try {
            await context.bridge.cancelQueue();
        } catch (cancelErr) {
            scenario.cancelError = serializeError(cancelErr);
        }
    } finally {
        scenario.elapsedMs = Date.now() - startedAt;
    }
    return scenario;
}

export function serializeError(err) {
    if (!err) return null;
    return {
        name: err.name || 'Error',
        message: err.message || String(err),
        details: err.details || undefined,
        stack: err.stack || undefined,
    };
}

async function scenarioPreflight({ bridge }) {
    const ping = await bridge.ping();
    const capabilities = await bridge.getCapabilities();
    const state = await bridge.getState();
    const queue = await bridge.getQueueState();

    if (ping?.ok !== true) {
        throw new BridgeProbeError('Bridge /ping did not return ok=true', { ping });
    }
    if (!capabilities?.supports_typed_actions) {
        throw new BridgeProbeError('Bridge capabilities do not advertise typed actions', { capabilities });
    }
    if (!state?.connected) {
        throw new BridgeProbeError('Fabric client is not connected to a world', { state });
    }
    if (!queue?.status) {
        throw new BridgeProbeError('Queue state did not include a status', { queue });
    }

    // Phase 1: protocol fields are a hard contract.
    if (capabilities.protocol_version !== 1) {
        throw new BridgeProbeError(`Expected protocol_version=1, got ${capabilities.protocol_version}`, { capabilities });
    }

    // Phase 1: action specs must have required fields
    const requiredActionFields = ['type', 'lifecycle', 'required', 'optional'];
    if (!Array.isArray(capabilities.actions)) {
        throw new BridgeProbeError('capabilities.actions must be an array', { capabilities });
    }
    for (const action of capabilities.actions) {
        for (const field of requiredActionFields) {
            if (!(field in action)) {
                throw new BridgeProbeError(`Action ${action.type} missing field '${field}'`, { action });
            }
        }
    }

    // Phase 1: state fields advertised must exist
    const requiredStateFields = ['selected_slot', 'held_items', 'equipment', 'effects', 'xp', 'open_screen'];
    if (!Array.isArray(capabilities.state_fields)) {
        throw new BridgeProbeError('capabilities.state_fields must be an array', { capabilities });
    }
    for (const field of requiredStateFields) {
        if (!capabilities.state_fields.includes(field)) {
            throw new BridgeProbeError(`capabilities.state_fields is missing '${field}'`, { capabilities });
        }
        if (!(field in state)) {
            throw new BridgeProbeError(`State missing field '${field}' (advertised in capabilities)`, { state });
        }
    }

    // Phase 1: queue state must have active_id and active_action_type
    const requiredQueueFields = ['active_id', 'active_action_type'];
    if (!Array.isArray(capabilities.queue_fields)) {
        throw new BridgeProbeError('capabilities.queue_fields must be an array', { capabilities });
    }
    for (const field of requiredQueueFields) {
        if (!capabilities.queue_fields.includes(field)) {
            throw new BridgeProbeError(`capabilities.queue_fields is missing '${field}'`, { capabilities });
        }
    }
    for (const field of requiredQueueFields) {
        if (!(field in queue)) {
            throw new BridgeProbeError(`Queue state missing field '${field}'`, { queue });
        }
    }
    // Verify null when idle
    if (queue.status === 'idle') {
        if (queue.active_id !== null) {
            throw new BridgeProbeError('Queue status=idle but active_id is not null', { queue });
        }
        if (queue.active_action_type !== null) {
            throw new BridgeProbeError('Queue status=idle but active_action_type is not null', { queue });
        }
    }

    return {
        player: state.player_name,
        position: { x: state.x, y: state.y, z: state.z },
        queueStatus: queue.status,
        actionTypes: capabilities.action_types || [],
        protocolVersion: capabilities.protocol_version,
        stateFields: capabilities.state_fields || [],
        queueFields: capabilities.queue_fields || [],
    };
}

async function scenarioQueueCancel({ bridge, state }) {
    const anchor = anchorNear(state, 10, 10);
    await resetProbeArea(bridge, anchor);
    await bridge.sendBatch([{ type: 'move', x: anchor.x, y: anchor.y, z: anchor.z }]);
    await sleep(700);
    await bridge.cancelQueue();
    const queue = await waitForQueueIdle(bridge, { timeoutMs: 15000 });
    return { queue };
}

async function scenarioMove({ bridge, state, queueTimeoutMs }) {
    const anchor = anchorNear(state, 7, 7);
    await resetProbeArea(bridge, anchor);
    await bridge.sendBatch([{ type: 'move', x: anchor.x, y: anchor.y, z: anchor.z }]);
    await waitForQueueIdle(bridge, { timeoutMs: queueTimeoutMs });
    const after = await bridge.getState();
    const distance = assertPositionNear(after, anchor, 3.0);
    return { target: anchor, final: { x: after.x, y: after.y, z: after.z }, distance };
}

async function scenarioMine({ bridge, state, queueTimeoutMs }) {
    const anchor = anchorNear(state, 5, 5);
    await resetProbeArea(bridge, anchor);
    const ore = { x: anchor.x + 1, y: anchor.y, z: anchor.z };
    await runCommands(bridge, [
        'give @s minecraft:diamond_pickaxe 1',
        `setblock ${ore.x} ${ore.y} ${ore.z} minecraft:coal_ore`,
    ]);
    await bridge.sendBatch([{ type: 'mine', target: 'coal_ore', count: 1 }]);
    await waitForQueueIdle(bridge, { timeoutMs: queueTimeoutMs });
    const blocks = await bridge.readBlocks({ ...ore, w: 1, h: 1, l: 1 });
    const after = await bridge.getState();
    const coal = countInventoryItem(after, 'coal');
    if (blocks.blocks?.[0] === 'minecraft:coal_ore' && coal < 1) {
        throw new BridgeProbeError('Mine scenario did not remove coal_ore or collect coal', { blocks, coal });
    }
    return { ore, blockAfter: blocks.blocks?.[0], coal };
}

async function scenarioCraft({ bridge, state, queueTimeoutMs }) {
    const anchor = anchorNear(state, 5, -5);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        'give @s minecraft:oak_planks 2',
        `setblock ${anchor.x + 1} ${anchor.y} ${anchor.z} minecraft:crafting_table`,
    ]);
    await bridge.sendBatch([{ type: 'craft', item: 'stick', count: 4 }]);
    await waitForQueueIdle(bridge, { timeoutMs: queueTimeoutMs });
    const after = await bridge.getState();
    const sticks = assertInventoryAtLeast(after, 'stick', 4);
    return { sticks };
}

async function scenarioSmeltViaCraft({ bridge, state, queueTimeoutMs }) {
    const anchor = anchorNear(state, -5, 5);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        'give @s minecraft:raw_iron 1',
        'give @s minecraft:coal 1',
        `setblock ${anchor.x + 1} ${anchor.y} ${anchor.z} minecraft:furnace`,
    ]);
    await bridge.sendBatch([{ type: 'craft', item: 'iron_ingot', count: 1 }]);
    await waitForQueueIdle(bridge, { timeoutMs: Math.max(queueTimeoutMs, 240000) });
    const after = await bridge.getState();
    const ingots = assertInventoryAtLeast(after, 'iron_ingot', 1);
    return { ingots };
}

async function scenarioAttack({ bridge, state }) {
    const anchor = anchorNear(state, 4, 4);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        'difficulty easy',
        'gamemode survival @s',
        'give @s minecraft:diamond_sword 1',
        `summon minecraft:zombie ${anchor.x + 2} ${anchor.y} ${anchor.z} {NoAI:1b,PersistenceRequired:1b}`,
    ], 200);
    await bridge.sendBatch([{ type: 'attack', target_type: 'zombie', count: 1, search_time_s: 0, retreat_hp: 4 }]);
    const result = await waitForCondition(async () => {
        const next = await bridge.getState();
        return !hasNearbyEntity(next, 'zombie') ? next : null;
    }, { timeoutMs: 60000, pollMs: 750 });
    return { hostileCleared: true, elapsedMs: result.elapsedMs };
}

async function scenarioSleepTry({ bridge, state, queueTimeoutMs }) {
    const anchor = anchorNear(state, -5, -5);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        'difficulty peaceful',
        'time set night',
        `setblock ${anchor.x} ${anchor.y} ${anchor.z} minecraft:red_bed[facing=south,part=foot]`,
        `setblock ${anchor.x} ${anchor.y} ${anchor.z + 1} minecraft:red_bed[facing=south,part=head]`,
    ], 200);
    await bridge.sendBatch([{ type: 'sleep_try' }]);
    const slept = await waitForCondition(async () => {
        const queue = await bridge.getQueueState();
        if (queue?.status === 'paused') {
            throw new BridgeProbeError(`Sleep queue paused: ${queue.lastFailure || 'unknown failure'}`, { queue });
        }
        const next = await bridge.getState();
        return next?.day_phase && next.day_phase !== 'night' ? next : null;
    }, { timeoutMs: Math.max(queueTimeoutMs, 90000), pollMs: 1000 });
    return {
        dayPhase: slept.value.day_phase,
        timeOfDayTicks: slept.value.time_of_day_ticks,
        elapsedMs: slept.elapsedMs,
    };
}

async function scenarioBuildSchematic({ bridge, state }) {
    const anchor = anchorNear(state, 3, -7);
    await resetProbeArea(bridge, anchor);
    const origin = { x: anchor.x, y: anchor.y, z: anchor.z };
    await runCommands(bridge, [
        'give @s minecraft:gold_block 1',
        `setblock ${origin.x} ${origin.y} ${origin.z} minecraft:air`,
    ]);
    await bridge.sendBatch([{
        type: 'build_schematic',
        name: 'probe_gold_block',
        origin,
        size: { x: 1, y: 1, z: 1 },
        palette: ['minecraft:air', 'minecraft:gold_block'],
        blocks: [1],
    }]);
    const placed = await waitForCondition(async () => {
        const blocks = await bridge.readBlocks({ ...origin, w: 1, h: 1, l: 1 });
        return blocks?.blocks?.[0] === 'minecraft:gold_block' ? blocks : null;
    }, { timeoutMs: 90000, pollMs: 1000 });
    assertBlockEquals(placed.value, 0, 'gold_block');
    await bridge.sendAction({ type: 'cancel_build' }).catch(() => null);
    return { origin, block: placed.value.blocks[0] };
}

async function scenarioRangedAttack({ bridge, state }) {
    const anchor = anchorNear(state, 4, 4);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        'difficulty easy',
        'gamemode survival @s',
        'give @s minecraft:bow 1',
        'give @s minecraft:arrow 5',
        `summon minecraft:zombie ${anchor.x + 2} ${anchor.y} ${anchor.z} {NoAI:1b,PersistenceRequired:1b}`,
    ], 200);
    await bridge.sendBatch([{ type: 'ranged_attack', target_type: 'zombie', count: 1 }]);
    const result = await waitForCondition(async () => {
        const next = await bridge.getState();
        return !hasNearbyEntity(next, 'zombie') ? next : null;
    }, { timeoutMs: 60000, pollMs: 750 });
    return { hostileCleared: true, elapsedMs: result.elapsedMs };
}

async function scenarioEnchant({ bridge, state }) {
    const anchor = anchorNear(state, 5, -5);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        `setblock ${anchor.x + 1} ${anchor.y} ${anchor.z} minecraft:enchanting_table`,
        'give @s minecraft:diamond_sword 1',
        'give @s minecraft:lapis_lazuli 3',
    ]);
    await bridge.sendAction({ type: 'enchant', item: 'diamond_sword' });
    const result = await waitForCondition(async () => {
        const after = await bridge.getState();
        const sword = (after.inventory || []).find(e =>
            normalizeItemId(e.item).includes('diamond_sword'));
        return sword?.nbt?.enchantments ? after : null;
    }, { timeoutMs: 30000, pollMs: 500 });
    return { enchanted: true };
}

async function scenarioBrew({ bridge, state }) {
    const anchor = anchorNear(state, 5, -5);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        `setblock ${anchor.x + 1} ${anchor.y} ${anchor.z} minecraft:brewing_stand`,
        'give @s minecraft:blaze_powder 1',
        'give @s minecraft:water_bottle 1',
        'give @s minecraft:nether_wart 1',
    ]);
    await bridge.sendAction({ type: 'brew', ingredient: 'nether_wart', input: 'water_bottle' });
    const result = await waitForCondition(async () => {
        const after = await bridge.getState();
        return countInventoryItem(after, 'awkward') > 0 ? after : null;
    }, { timeoutMs: 60000, pollMs: 500 });
    return { awkwardPotion: countInventoryItem(result.value, 'awkward') };
}

async function scenarioAnvil({ bridge, state }) {
    const anchor = anchorNear(state, 5, -5);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        `setblock ${anchor.x + 1} ${anchor.y} ${anchor.z} minecraft:anvil`,
        'give @s minecraft:iron_sword 1',
        'give @s minecraft:iron_ingot 1',
        'xp set @s 30',
    ]);
    await bridge.sendAction({ type: 'anvil', item: 'iron_sword', material: 'iron_ingot' });
    await sleep(3000);
    await bridge.sendAction({ type: 'close_screen' });
    return { anvilUsed: true };
}

async function scenarioDefend({ bridge, state }) {
    const anchor = anchorNear(state, 4, 4);
    await resetProbeArea(bridge, anchor);
    await runCommands(bridge, [
        'gamemode survival @s',
        'give @s minecraft:shield 1',
    ]);
    await bridge.sendAction({ type: 'defend', duration_s: 3 });
    await sleep(4000);
    return { defended: true };
}

export const scenarioRegistry = {
    preflight: scenarioPreflight,
    queue_cancel: scenarioQueueCancel,
    move: scenarioMove,
    mine: scenarioMine,
    craft: scenarioCraft,
    smelt_via_craft: scenarioSmeltViaCraft,
    attack: scenarioAttack,
    ranged_attack: scenarioRangedAttack,
    defend: scenarioDefend,
    enchant: scenarioEnchant,
    brew: scenarioBrew,
    anvil: scenarioAnvil,
    sleep_try: scenarioSleepTry,
    build_schematic: scenarioBuildSchematic,
};

export function parseArgs(argv) {
    const args = {
        url: process.env.BRIDGE_URL || DEFAULT_BRIDGE_URL,
        full: false,
        reportDir: DEFAULT_REPORT_DIR,
        queueTimeoutMs: DEFAULT_QUEUE_TIMEOUT_MS,
        scenarios: [],
        list: false,
    };

    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === '--full') args.full = true;
        else if (arg === '--list') args.list = true;
        else if (arg === '--url') args.url = argv[++i];
        else if (arg.startsWith('--url=')) args.url = arg.slice('--url='.length);
        else if (arg === '--report-dir') args.reportDir = argv[++i];
        else if (arg.startsWith('--report-dir=')) args.reportDir = arg.slice('--report-dir='.length);
        else if (arg === '--timeout-ms') args.queueTimeoutMs = Number(argv[++i]);
        else if (arg.startsWith('--timeout-ms=')) args.queueTimeoutMs = Number(arg.slice('--timeout-ms='.length));
        else if (arg === '--scenario') args.scenarios.push(argv[++i]);
        else if (arg.startsWith('--scenario=')) args.scenarios.push(arg.slice('--scenario='.length));
        else if (arg === '--help' || arg === '-h') args.help = true;
        else throw new BridgeProbeError(`Unknown argument: ${arg}`);
    }
    if (!Number.isFinite(args.queueTimeoutMs) || args.queueTimeoutMs <= 0) {
        throw new BridgeProbeError('--timeout-ms must be a positive number');
    }
    return args;
}

export function scenarioNamesForArgs(args) {
    if (args.scenarios.length) {
        for (const name of args.scenarios) {
            if (!scenarioRegistry[name]) {
                throw new BridgeProbeError(`Unknown scenario: ${name}`, {
                    validScenarios: Object.keys(scenarioRegistry),
                });
            }
        }
        return ['preflight', ...args.scenarios.filter(name => name !== 'preflight')];
    }
    if (!args.full) return ['preflight'];
    return [
        'preflight',
        'queue_cancel',
        'move',
        'mine',
        'craft',
        'smelt_via_craft',
        'attack',
        'ranged_attack',
        'defend',
        'enchant',
        'brew',
        'anvil',
        'sleep_try',
        'build_schematic',
    ];
}

export async function runProbe(args) {
    const bridge = new BridgeProbeClient(args.url);
    const report = {
        ok: false,
        url: args.url,
        full: args.full,
        startedAt: new Date().toISOString(),
        finishedAt: null,
        scenarios: [],
    };

    const names = scenarioNamesForArgs(args);
    let latestState = null;
    for (const name of names) {
        latestState = await bridge.getState().catch(() => latestState);
        const scenario = await runScenario(name, scenarioRegistry[name], {
            bridge,
            state: latestState,
            queueTimeoutMs: args.queueTimeoutMs,
        });
        report.scenarios.push(scenario);
        printScenarioResult(scenario);
        if (!scenario.ok) break;
        latestState = await bridge.getState().catch(() => latestState);
    }

    report.finishedAt = new Date().toISOString();
    report.ok = report.scenarios.every(scenario => scenario.ok);
    report.reportPath = await writeReport(args.reportDir, report);
    return report;
}

export async function writeReport(reportDir, report) {
    const root = path.resolve(process.cwd(), reportDir);
    await mkdir(root, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filePath = path.join(root, `bridge-action-probe-${stamp}.json`);
    await writeFile(filePath, JSON.stringify(report, null, 2) + '\n', 'utf8');
    return filePath;
}

function printScenarioResult(scenario) {
    const status = scenario.ok ? 'PASS' : 'FAIL';
    console.log(`[${status}] ${scenario.name} (${scenario.elapsedMs}ms)`);
    if (!scenario.ok && scenario.error) {
        console.error(`  ${scenario.error.message}`);
    }
}

function printHelp() {
    console.log(`Fabric Bridge Action Probe

Usage:
  node scripts/bridge_action_probe.mjs [options]

Options:
  --full                  Run destructive full behavior scenarios.
  --url <url>             Bridge URL. Default: ${DEFAULT_BRIDGE_URL}
  --scenario <name>       Run a named scenario. Can be repeated.
  --timeout-ms <ms>       Queue timeout for long scenarios. Default: ${DEFAULT_QUEUE_TIMEOUT_MS}
  --report-dir <path>     Report directory. Default: ${DEFAULT_REPORT_DIR}
  --list                  List scenario names.
  --help                  Show this help.
`);
}

async function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (err) {
        console.error(err.message);
        process.exitCode = 2;
        return;
    }

    if (args.help) {
        printHelp();
        return;
    }
    if (args.list) {
        console.log(Object.keys(scenarioRegistry).join('\n'));
        return;
    }

    console.log(`Bridge probe URL: ${args.url}`);
    if (args.full) {
        console.log('Mode: full destructive scenarios. Use a dedicated cheats-enabled test world.');
    } else {
        console.log('Mode: preflight only. Use --full for destructive scenarios.');
    }

    const report = await runProbe(args);
    console.log(`Report: ${report.reportPath}`);
    console.log(report.ok ? 'Bridge probe passed.' : 'Bridge probe failed.');
    if (!report.ok) process.exitCode = 1;
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isMain) {
    main().catch(err => {
        console.error(err?.stack || err?.message || String(err));
        process.exitCode = 1;
    });
}
