import test from 'node:test';
import assert from 'node:assert/strict';

import {
    BRIDGE_PROMPT_PACKS,
    expandBridgePromptPackSelection,
    formatBridgePromptPacks,
    getBridgePromptPack,
    getBridgePromptPackEmbeddingText,
    listBridgePromptPackEmbeddingDocuments,
    listBridgePromptPacks,
} from '../src/bridge/bridge_prompt_packs.js';

const REQUIRED_PACK_IDS = [
    'mining',
    'crafting',
    'smelting',
    'smithing',
    'containers',
    'dimension_travel',
    'return_waypoints',
    'vision_inspection',
    'combat',
    'brewing',
    'farming',
];

test('bridge prompt pack corpus contains all required packs', () => {
    const ids = new Set(listBridgePromptPacks().map(pack => pack.id));

    for (const id of REQUIRED_PACK_IDS) {
        assert(ids.has(id), `missing prompt pack: ${id}`);
    }
});

test('bridge prompt pack metadata shape is valid', () => {
    const ids = new Set(BRIDGE_PROMPT_PACKS.map(pack => pack.id));

    for (const pack of BRIDGE_PROMPT_PACKS) {
        assert.equal(typeof pack.id, 'string');
        assert(pack.id.length > 0, 'id must not be empty');
        assert.equal(typeof pack.title, 'string');
        assert(pack.title.length > 0, `${pack.id} title must not be empty`);
        assert(Array.isArray(pack.actions), `${pack.id} actions must be an array`);
        assert(pack.actions.length > 0, `${pack.id} must list at least one action`);
        assert(Array.isArray(pack.triggers), `${pack.id} triggers must be an array`);
        assert(pack.triggers.length > 0, `${pack.id} must list triggers`);
        assert(Array.isArray(pack.requires), `${pack.id} requires must be an array`);
        assert.equal(typeof pack.priority, 'number', `${pack.id} priority must be a number`);
        assert(Number.isInteger(pack.token_budget), `${pack.id} token_budget must be an integer`);
        assert(pack.token_budget > 0, `${pack.id} token_budget must be positive`);
        assert.equal(typeof pack.content, 'string');
        assert(pack.content.length > 0, `${pack.id} content must not be empty`);

        for (const action of pack.actions) {
            assert.equal(typeof action, 'string', `${pack.id} action must be a string`);
            assert(action.length > 0, `${pack.id} action must not be empty`);
        }
        for (const trigger of pack.triggers) {
            assert.equal(typeof trigger, 'string', `${pack.id} trigger must be a string`);
            assert(trigger.length > 0, `${pack.id} trigger must not be empty`);
        }
        for (const dependency of pack.requires) {
            assert(ids.has(dependency), `${pack.id} depends on unknown pack ${dependency}`);
        }
    }
});

test('formatBridgePromptPacks is compact and bounded', () => {
    const selected = ['smithing', 'containers', 'dimension_travel', 'return_waypoints'];
    const formatted = formatBridgePromptPacks(selected, { maxChars: 900 });

    assert(formatted.length <= 900);
    assert(formatted.includes('BRIDGE GUIDANCE PACKS'));
    assert(formatted.includes('[smithing]'));
    assert(formatted.includes('actions:smith,obtain'));
    assert(!formatted.includes('\n\n'), 'formatter should not add blank blocks');
});

test('formatBridgePromptPacks handles empty selections', () => {
    assert.equal(formatBridgePromptPacks([]), '');
    assert.equal(formatBridgePromptPacks(null), '');
    assert.equal(formatBridgePromptPacks(undefined), '');
});

test('embedding documents expose retriever text and metadata', () => {
    const docs = listBridgePromptPackEmbeddingDocuments();
    assert.equal(docs.length, BRIDGE_PROMPT_PACKS.length);

    const miningDoc = docs.find(doc => doc.id === 'mining');
    assert(miningDoc, 'mining embedding document missing');
    assert(miningDoc.text.includes('triggers:'));
    assert(miningDoc.text.includes('netherrack'));
    assert.deepEqual(miningDoc.metadata.requires, ['dimension_travel', 'return_waypoints']);

    const smithingText = getBridgePromptPackEmbeddingText('smithing');
    assert(smithingText.includes('template'));
    assert(smithingText.includes('requires: containers'));
});

test('pack dependencies represent smithing containers and nether return guidance', () => {
    const smithing = getBridgePromptPack('smithing');
    assert(smithing.requires.includes('containers'));

    const mining = getBridgePromptPack('mining');
    assert(mining.triggers.includes('netherrack'));
    assert(mining.requires.includes('dimension_travel'));
    assert(mining.requires.includes('return_waypoints'));

    const expanded = expandBridgePromptPackSelection(['mining', 'smithing']);
    const expandedIds = new Set(expanded.map(pack => pack.id));
    assert(expandedIds.has('dimension_travel'));
    assert(expandedIds.has('return_waypoints'));
    assert(expandedIds.has('containers'));
});
