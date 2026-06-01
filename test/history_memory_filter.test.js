import test from 'node:test';
import assert from 'node:assert/strict';

import { sanitizeTurnsForMemory } from '../src/agent/history.js';

test('memory sanitizer drops bridge state and assistant action proposals', () => {
    const turns = [
        { role: 'system', content: 'CURRENT STATE:\nInventory: 5x netherite_ingot' },
        { role: 'user', content: 'ADMIN: can you smith my diamond armor?' },
        {
            role: 'assistant',
            content: '{"reply":"I will smith it.","actions":[{"type":"smith","template":"rib_armor_trim_smithing_template","base":"diamond_helmet","addition":"netherite_ingot"}]}',
        },
        { role: 'system', content: 'Batch dispatch failed: queue_busy' },
        { role: 'assistant', content: '{"reply":"Do you want a netherite upgrade or an armor trim?"}' },
    ];

    assert.deepEqual(sanitizeTurnsForMemory(turns), [
        { role: 'user', content: 'ADMIN: can you smith my diamond armor?' },
        { role: 'assistant', content: 'Do you want a netherite upgrade or an armor trim?' },
    ]);
});

test('memory sanitizer drops command style assistant output', () => {
    const turns = [
        { role: 'assistant', content: 'Sure. COMMAND: #mine 3 iron_ore' },
        { role: 'assistant', content: 'ACTION: {"type":"craft","item":"iron_pickaxe"}' },
        { role: 'assistant', content: 'That table is already nearby.' },
    ];

    assert.deepEqual(sanitizeTurnsForMemory(turns), [
        { role: 'assistant', content: 'That table is already nearby.' },
    ]);
});
