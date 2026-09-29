import test from 'node:test';
import assert from 'node:assert/strict';
import { wiki } from '../src/utils/MinecraftWiki.js';
import {
    packageMaterialSchematic,
    planBuildMaterialActions,
    readbackToSchematic,
    rollUpMaterials,
    rotateSchematicY,
    tallySchematicMaterials,
    validateHouse,
} from '../src/bridge/house_builder.js';
import { cabinTemplate, pitTemplate } from '../src/bridge/house_templates.js';

function stateAt(schematic, x, y, z) {
    const offset = ((y * schematic.size.z + z) * schematic.size.x + x) * 2;
    return schematic.palette[schematic.blocksU16.readUInt16LE(offset)];
}

function exactReadback(schematic) {
    const count = schematic.size.x * schematic.size.y * schematic.size.z;
    return {
        size: [schematic.size.x, schematic.size.y, schematic.size.z],
        blocks: Array.from({ length: count }, (_, i) => schematic.palette[schematic.blocksU16.readUInt16LE(i * 2)]),
    };
}

test('real iron and gold conversion cycles choose their smelting routes', () => {
    for (const [block, raw] of [['iron_block', 'raw_iron'], ['gold_block', 'raw_gold']]) {
        const plan = rollUpMaterials(new Map([[block, 1]]), wiki.data);
        assert.equal(plan.raw.get(raw), 9);
        assert.equal(plan.crafts.get(block), 1);
        assert.ok(!plan.raw.has(block));
    }
});

test('owned finished and intermediate items are allocated before recipe expansion', () => {
    const schematic = {
        name: 'owned_chest',
        size: { x: 1, y: 1, z: 1 },
        palette: ['minecraft:chest'],
        blocksU16: Buffer.alloc(2),
    };
    const stocked = packageMaterialSchematic(
        schematic,
        { x: 0, y: 64, z: 0 },
        [{ item: 'minecraft:chest', count: 1 }],
        wiki.data,
    );
    assert.deepEqual(stocked.prereqs, []);

    const intermediate = rollUpMaterials(
        new Map([['chest', 1]]),
        wiki.data,
        [{ item: 'minecraft:oak_planks', count: 8 }],
    );
    assert.deepEqual([...intermediate.shortages], [['chest', 1]]);
});

test('craft actions use total inventory targets while shortages remain deltas', () => {
    const plan = rollUpMaterials(
        new Map([['glass', 4]]),
        wiki.data,
        [{ item: 'minecraft:glass', count: 3 }],
    );
    assert.equal(plan.shortages.get('glass'), 1);
    const actions = planBuildMaterialActions(plan.shortages, [{ item: 'minecraft:glass', count: 3 }]);
    assert.equal(actions.find(action => action.item === 'glass').count, 4);
});

test('stock-satisfied dependencies still run before later production targets', () => {
    const inventory = [{ item: 'minecraft:oak_planks', count: 10 }];
    const plan = rollUpMaterials(
        new Map([['chest', 1], ['oak_planks', 64]]),
        wiki.data,
        inventory,
    );
    const actions = planBuildMaterialActions(plan.shortages, inventory);
    const planks = actions.findIndex(action => action.item === 'oak_planks');
    const chest = actions.findIndex(action => action.item === 'chest');
    assert.ok(planks >= 0 && planks < chest);
    assert.equal(actions[planks].count, 72);
    assert.equal(actions[chest].count, 1);
});

test('generated cabins encode distinct door and bed states and retain item tallies', () => {
    const cabin = cabinTemplate({ size: 'small', biome: 'plains' });
    const doorX = cabin.doorX;
    assert.match(stateAt(cabin, doorX, 1, 0), /oak_door\[facing=south,half=lower\]/);
    assert.match(stateAt(cabin, doorX, 2, 0), /oak_door\[facing=south,half=upper\]/);
    assert.match(stateAt(cabin, 2, 1, 2), /red_bed\[facing=east,part=foot\]/);
    assert.match(stateAt(cabin, 3, 1, 2), /red_bed\[facing=east,part=head\]/);

    const materials = tallySchematicMaterials(cabin);
    assert.equal(materials.get('oak_door'), 1);
    assert.equal(materials.get('red_bed'), 1);
});

test('rotation updates directional palette states and scans preserve state descriptors', () => {
    const rotated = rotateSchematicY(cabinTemplate({ size: 'small', biome: 'plains' }));
    assert.ok(rotated.palette.some(value => /oak_door\[facing=west,half=lower\]/.test(value)));
    assert.ok(rotated.palette.some(value => /red_bed\[facing=south,part=head\]/.test(value)));

    const states = [
        'minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]',
        'minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]',
    ];
    const scanned = readbackToSchematic({ size: [1, 2, 1], blocks: states }, 'door_scan');
    assert.equal(stateAt(scanned, 0, 0, 0), states[0]);
    assert.equal(stateAt(scanned, 0, 1, 0), states[1]);
});

test('an exact generated pit passes pit-specific validation', () => {
    const pit = pitTemplate({ biome: 'plains' });
    const report = validateHouse(exactReadback(pit), { size: pit.size, template: 'pit' });
    assert.equal(report.pass, true, report.failures.join(', '));
    assert.equal(report.stats.requirements.furnace, false);
    assert.equal(report.stats.requirements.chest, false);
    assert.equal(report.stats.requirements.window, false);
});
