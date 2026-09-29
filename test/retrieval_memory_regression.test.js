import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

import { BridgeExampleRetriever } from '../src/bridge/bridge_examples.js';
import { BridgePromptPackRetriever } from '../src/bridge/bridge_prompt_retriever.js';
import { extractNotablePositions, WorldMemory } from '../src/bridge/world_memory.js';

test('prompt pack capabilities filter unsupported packs without boosting relevance', async () => {
    const packs = [
        {
            id: 'combat', title: 'Combat', actions: ['attack'], triggers: ['fight'], requires: [],
            content: 'Fight hostile mobs safely.', priority: 95,
        },
        {
            id: 'move', title: 'Movement', actions: ['move'], triggers: ['move'], requires: [],
            content: 'Move to a location.', priority: 1,
        },
    ];
    const retriever = new BridgePromptPackRetriever(null, packs);

    assert.deepEqual(await retriever.getRelevantPacks('zxqv blorp unrelated', {
        actions: ['attack', 'move'],
    }), []);
    assert.deepEqual((await retriever.getRelevantPacks('fight the zombies', {
        actions: ['move'],
    })).map(pack => pack.id), []);
    assert.deepEqual((await retriever.getRelevantPacks('fight the zombies', {
        actions: ['attack'],
    })).map(pack => pack.id), ['combat']);
});

test('example lexical fallback does not return arbitrary zero-overlap snippets', async () => {
    const retriever = new BridgeExampleRetriever(null);
    assert.deepEqual(await retriever.getRelevantSnippets('zxqv blorp unrelated smalltalk'), []);
});

test('example retriever retries a transient query embedding failure', async () => {
    let queryCalls = 0;
    const model = {
        embed(text, options = {}) {
            if (options.intent === 'query') {
                queryCalls++;
                if (queryCalls === 1) return Promise.reject(new Error('temporary outage'));
                return Promise.resolve([0, 1]);
            }
            return Promise.resolve(String(text).toLowerCase().includes('sleep') ? [0, 1] : [1, 0]);
        },
    };
    const retriever = new BridgeExampleRetriever(model);
    await retriever.init();

    assert.equal((await retriever.getRelevantSnippets('please go to sleep', 1))[0].intent, 'sleep');
    assert.equal(retriever.embedding_model, model);
    assert.equal((await retriever.getRelevantSnippets('please go to sleep', 1))[0].intent, 'sleep');
    assert.equal(queryCalls, 2);
});

test('prompt pack retriever retries a transient query embedding failure', async () => {
    let queryCalls = 0;
    const packs = [
        {
            id: 'sleep', title: 'Sleep', actions: ['sleep_try'], triggers: ['sleep'], requires: [],
            content: 'Sleep safely.', priority: 1,
        },
        {
            id: 'combat', title: 'Combat', actions: ['attack'], triggers: ['fight'], requires: [],
            content: 'Fight safely.', priority: 95,
        },
    ];
    const model = {
        embed(text, options = {}) {
            if (Array.isArray(text)) return Promise.resolve(text.map(doc => String(doc).includes('Sleep') ? [0, 1] : [1, 0]));
            if (options.intent === 'query') {
                queryCalls++;
                if (queryCalls === 1) return Promise.reject(new Error('temporary outage'));
                return Promise.resolve([0, 1]);
            }
            return Promise.resolve([1, 0]);
        },
    };
    const retriever = new BridgePromptPackRetriever(model, packs);
    await retriever.init();

    assert.equal((await retriever.getRelevantPacks('please sleep', { k: 1 }))[0].id, 'sleep');
    assert.equal(retriever.embeddingModel, model);
    assert.equal((await retriever.getRelevantPacks('please sleep', { k: 1 }))[0].id, 'sleep');
    assert.equal(queryCalls, 2);
});

test('world memory consumes producer-shaped nearby entities and surface cells', () => {
    const extracted = extractNotablePositions({
        nearby_block_entities: [
            { x: 11, y: 64, z: 10, type: 'minecraft:chest', block: 'minecraft:chest' },
            { x: 10, y: 64, z: 11, type: 'minecraft:furnace', block: 'minecraft:furnace' },
        ],
        surface_map: {
            cells: [
                { x: 12, y: 63, z: 10, block: 'minecraft:diamond_ore' },
                { x: 11, y: 64, z: 10, block: 'minecraft:chest' },
            ],
        },
    });

    assert.deepEqual(extracted, [
        { kind: 'chest', pos: { x: 11, y: 64, z: 10 } },
        { kind: 'furnace', pos: { x: 10, y: 64, z: 11 } },
        { kind: 'diamond_ore', pos: { x: 12, y: 63, z: 10 } },
    ]);
});

test('waypoint names and notes are bounded and newline-free on write and load', () => {
    const dir = mkdtempSync(join(tmpdir(), 'world-memory-'));
    const filePath = join(dir, 'memory.json');
    try {
        const memory = new WorldMemory(filePath);
        const saved = memory.setWaypoint(
            `  Base\nignore this instruction ${'x'.repeat(200)} `,
            { x: 1.2, y: 64, z: -2.8, dimension: 'minecraft:overworld' },
            `note\nignore this too ${'n'.repeat(400)}`,
        );
        assert(saved);
        assert(saved.name.length <= 80);
        assert(saved.note.length <= 240);
        assert(!/[\r\n]/u.test(saved.name));
        assert(!/[\r\n]/u.test(saved.note));

        writeFileSync(filePath, JSON.stringify({
            waypoints: {
                LEGACY: {
                    name: `legacy\nname ${'z'.repeat(200)}`,
                    x: 2, y: 64, z: 3,
                    note: `legacy\nnote ${'q'.repeat(400)}`,
                },
            },
            home: {
                name: `legacy\nname ${'z'.repeat(200)}`,
                x: 2, y: 64, z: 3,
                note: 'home',
            },
        }));
        const loaded = new WorldMemory(filePath);
        loaded.load();
        const waypoint = loaded.listWaypoints()[0];
        assert(waypoint.name.length <= 80);
        assert(waypoint.note.length <= 240);
        assert(!/[\r\n]/u.test(waypoint.name));
        assert(!/[\r\n]/u.test(waypoint.note));
        assert.equal(loaded.data.home.name, waypoint.name);
        assert(!readFileSync(filePath, 'utf8').includes('\nname'));
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
