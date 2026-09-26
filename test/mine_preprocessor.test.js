import test from 'node:test';
import assert from 'node:assert/strict';
import { preprocessMineActions } from '../src/bridge/mine_preprocessor.js';

const overworld = inventory => ({ dimension: 'minecraft:overworld', x: 0, y: 64, z: 0, inventory });

test('mine prepends the missing pickaxe tier', async () => {
    const out = await preprocessMineActions([{ type: 'mine', target: 'iron_ore', count: 3 }], overworld([{ item: 'minecraft:dirt', count: 3 }]), null);
    assert.deepEqual(out, [{ type: 'craft', item: 'stone_pickaxe', count: 1 }, { type: 'mine', target: 'iron_ore', count: 3 }]);
});

test('mine keeps the batch when the pickaxe is good enough or inventory is unknown', async () => {
    const mine = [{ type: 'mine', target: 'iron_ore', count: 3 }];
    assert.deepEqual(await preprocessMineActions(mine, overworld([{ item: 'minecraft:iron_pickaxe', count: 1 }]), null), mine);
    assert.deepEqual(await preprocessMineActions(mine, { dimension: 'minecraft:overworld' }, null), mine);
});
