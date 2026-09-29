import assert from 'node:assert/strict';
import test from 'node:test';

import { BridgeAgent } from '../src/bridge/bridge_agent.js';
import { BridgeExampleRetriever } from '../src/bridge/bridge_examples.js';
import { extractNotablePositions, WorldMemory } from '../src/bridge/world_memory.js';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

test('dynamic retrieval context does not replace the actual request as the final user turn', async () => {
    const agent = Object.create(BridgeAgent.prototype);
    agent.history = { memory: '' };
    agent._buildBridgeDynamicBlock = () => Promise.resolve('retrieved crafting guidance');
    const history = [{ role: 'user', content: 'Please make a stone pickaxe.' }];

    await agent._appendBridgeDynamicBlock(history);

    assert.equal(history.at(-1).content, 'Please make a stone pickaxe.');
});

test('example retrieval falls back when replacement model vectors remain incompatible', async () => {
    let queryDimension = 2;
    const model = {
        embed(text, options = {}) {
            if (options.intent === 'query') {
                return Promise.resolve(queryDimension === 2 ? [0, 1] : [0, 1, 0]);
            }
            return Promise.resolve(String(text).toLowerCase().includes('sleep') ? [0, 1] : [1, 0]);
        },
    };
    const retriever = new BridgeExampleRetriever(model);
    await retriever.init();
    assert.equal((await retriever.getRelevantSnippets('go to sleep', 1))[0]?.intent, 'sleep');

    queryDimension = 3;
    assert.equal((await retriever.getRelevantSnippets('go to sleep', 1))[0]?.intent, 'sleep');
});

test('failed queued look_at prevents actions after look_and_inspect', async () => {
    const dispatched = [];
    const agent = Object.create(BridgeAgent.prototype);
    Object.assign(agent, {
        name: 'Audit',
        _generation: 1,
        _lastState: { connected: true, queue: { status: 'idle' } },
        stopped: false,
        prompter: { vision_model: { sendVisionRequest() {} } },
        history: { add() {} },
        bridge: {
            sendBatch(actions) {
                dispatched.push(actions.map(action => action.type));
                return Promise.resolve({ success: true, queued: 1 });
            },
            getState() {
                this.stateReads = (this.stateReads || 0) + 1;
                return Promise.resolve({ connected: true, queue: { status: this.stateReads === 1 ? 'idle' : 'paused' } });
            },
            getScreenshot() {
                return Promise.reject(new Error('capture must not run after look failure'));
            },
        },
    });

    const result = await agent._sendBatchWithBuildExpansion([
        { type: 'look_and_inspect', x: 1, y: 64, z: 1 },
        { type: 'follow', target: 'Alex' },
    ], 1);

    assert.equal(result.success, false);
    assert.deepEqual(dispatched, [['look_at']]);
});

test('corrupt persisted waypoint coordinates are rejected instead of becoming origin coordinates', () => {
    const dir = mkdtempSync(join(tmpdir(), 'node-review-world-memory-'));
    const file = join(dir, 'memory.json');
    try {
        writeFileSync(file, JSON.stringify({
            waypoints: { base: { name: 'base', x: 'not-a-number', y: 64, z: 2 } },
            home: { name: 'base', x: 'not-a-number', y: 64, z: 2 },
        }));
        const memory = new WorldMemory(file);
        memory.load();
        assert.equal(memory.getWaypoint('base'), null);
        assert.equal(memory.data.home, null);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('repeated producer-shaped sightings do not rewrite memory on every observation', () => {
    const memory = new WorldMemory('unused.json');
    let saves = 0;
    memory.save = () => { saves++; };
    const state = {
        dimension: 'minecraft:overworld',
        nearby_block_entities: [{ block: 'minecraft:chest', x: 2, y: 64, z: 3 }],
    };
    for (let cycle = 0; cycle < 100; cycle++) {
        for (const { kind, pos } of extractNotablePositions(state)) {
            memory.recordSighting(kind, pos, state.dimension);
        }
    }
    assert.equal(saves, 1);
});
