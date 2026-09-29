import { wiki } from '../utils/MinecraftWiki.js';

const norm = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();

// Deterministic/default block drops used by inventory verification and
// curriculum item-target checks. Counts intentionally use a conservative
// minimum of one item per mined block; fortune may yield more. Silk Touch is
// handled separately as an explicit limitation because it can change the item.
export const BLOCK_DROPS = {
    stone: 'cobblestone',
    deepslate: 'cobbled_deepslate',
    grass_block: 'dirt',
    dirt_path: 'dirt',
    farmland: 'dirt',
    mycelium: 'dirt',
    podzol: 'dirt',
    clay: 'clay_ball',
    glowstone: 'glowstone_dust',
    bookshelf: 'book',
    melon: 'melon_slice',
    coal_ore: 'coal',
    deepslate_coal_ore: 'coal',
    iron_ore: 'raw_iron',
    deepslate_iron_ore: 'raw_iron',
    gold_ore: 'raw_gold',
    deepslate_gold_ore: 'raw_gold',
    copper_ore: 'raw_copper',
    deepslate_copper_ore: 'raw_copper',
    diamond_ore: 'diamond',
    deepslate_diamond_ore: 'diamond',
    emerald_ore: 'emerald',
    deepslate_emerald_ore: 'emerald',
    redstone_ore: 'redstone',
    deepslate_redstone_ore: 'redstone',
    lapis_ore: 'lapis_lazuli',
    deepslate_lapis_ore: 'lapis_lazuli',
    nether_gold_ore: 'gold_nugget',
    nether_quartz_ore: 'quartz',
};

// A mined gravel block becomes exactly one of these inventory items.
const BLOCK_DROP_ALTERNATIVES = {
    gravel: ['gravel', 'flint'],
};

// These blocks do not have a reliable one-block -> inventory-item observation
// without knowing tool/enchantment/RNG state. Treat them as unverifiable rather
// than manufacturing a false negative.
function hasUnverifiableDrop(key) {
    return /_leaves$/.test(key)
        || key === 'glass'
        || /_stained_glass$/.test(key)
        || /_glass_pane$/.test(key)
        || key === 'ice'
        || key === 'frosted_ice'
        || key === 'spawner'
        || key === 'budding_amethyst'
        || key === 'reinforced_deepslate';
}

export function dropItemsForBlock(name) {
    const key = norm(name);
    if (!key || hasUnverifiableDrop(key)) return [];
    if (BLOCK_DROP_ALTERNATIVES[key]) return [...BLOCK_DROP_ALTERNATIVES[key]];
    return [BLOCK_DROPS[key] || key];
}

export function dropForBlock(name) {
    const items = dropItemsForBlock(name);
    return items.length === 1 ? items[0] : null;
}

function countInventory(inventory) {
    const m = new Map();
    for (const s of inventory || []) {
        if (!s || !s.item) continue;
        m.set(norm(s.item), (m.get(norm(s.item)) || 0) + (Number(s.count) || 0));
    }
    return m;
}

function addExpectedGain(expected, items, count) {
    const normalized = [...new Set((items || []).map(norm).filter(Boolean))];
    const n = Math.max(0, Number(count) || 0);
    if (normalized.length === 0 || n <= 0) return;
    const key = normalized.join('|');
    const existing = expected.get(key);
    if (existing) {
        existing.expectedGain += n;
        return;
    }
    expected.set(key, {
        item: normalized.length === 1 ? normalized[0] : key,
        ...(normalized.length > 1 ? { items: normalized } : {}),
        expectedGain: n,
    });
}

function expectedEntryMatches(entry, ingredient, notes = '') {
    const want = norm(ingredient);
    const candidates = entry.items || [entry.item];
    if (candidates.includes(want)) return true;

    const note = String(notes || '').toLowerCase();
    if ((want === 'any_planks' || /any (?:wood )?planks?/.test(note))
        && candidates.some(x => x.endsWith('_planks'))) return true;
    if ((want === 'any_log' || /any (?:wood )?(?:log|stem)/.test(note))
        && candidates.some(x => x.endsWith('_log') || x.endsWith('_stem'))) return true;
    return false;
}

// Consume only gains that this plan itself expected to create. Baseline
// inventory is deliberately not modeled here: consuming pre-existing inputs
// must not create a negative expectation.
function consumeExpectedDirect(expected, ingredient, amount, notes = '') {
    let remaining = Math.max(0, Number(amount) || 0);
    for (const entry of expected.values()) {
        if (remaining <= 0) break;
        if (entry.expectedGain <= 0 || !expectedEntryMatches(entry, ingredient, notes)) continue;
        const used = Math.min(entry.expectedGain, remaining);
        entry.expectedGain -= used;
        remaining -= used;
    }
    return remaining;
}

function consumeExpectedRequirement(expected, ingredient, amount, depth = 0, stack = new Set(), parentNotes = '') {
    let remaining = consumeExpectedDirect(expected, ingredient, amount, parentNotes);
    if (remaining <= 0 || depth > 8) return;

    const key = norm(ingredient);
    if (!key || stack.has(key)) return;
    const recipe = wiki.getRecipe(key);
    if (!recipe) return;

    const nextStack = new Set(stack);
    nextStack.add(key);
    const output = Math.max(1, Number(recipe.output) || 1);
    const batches = Math.max(1, Math.ceil(remaining / output));

    if (recipe.recipeSection === 'crafting') {
        for (const [input, perBatch] of Object.entries(recipe.ingredients || {})) {
            consumeExpectedRequirement(
                expected,
                input,
                (Number(perBatch) || 0) * batches,
                depth + 1,
                nextStack,
                recipe.notes || '',
            );
        }
    } else if (recipe.recipeSection === 'smelting' && recipe.input) {
        for (const input of String(recipe.input).split('+').map(x => x.trim()).filter(Boolean)) {
            if (input === 'any_fuel') continue;
            consumeExpectedRequirement(expected, input, batches, depth + 1, nextStack, recipe.notes || '');
        }
    }
}

function accountForCraftConsumption(expected, item, count) {
    const key = norm(item);
    if (!key) return;
    const recipe = wiki.getRecipe(key);
    if (!recipe) return;

    const output = Math.max(1, Number(recipe.output) || 1);
    const batches = Math.max(1, Math.ceil((Number(count) || 1) / output));
    if (recipe.recipeSection === 'crafting') {
        for (const [input, perBatch] of Object.entries(recipe.ingredients || {})) {
            consumeExpectedRequirement(
                expected,
                input,
                (Number(perBatch) || 0) * batches,
                0,
                new Set([key]),
                recipe.notes || '',
            );
        }
    } else if (recipe.recipeSection === 'smelting' && recipe.input) {
        for (const input of String(recipe.input).split('+').map(x => x.trim()).filter(Boolean)) {
            if (input === 'any_fuel') continue;
            consumeExpectedRequirement(expected, input, batches, 0, new Set([key]), recipe.notes || '');
        }
    }
}

export function expectFromActions(actions) {
    const expected = new Map();
    for (const a of actions || []) {
        if (!a || typeof a !== 'object') continue;
        const t = String(a.type || '').toLowerCase();
        const count = Math.max(1, Number(a.count) || 1);

        if (t === 'craft') {
            const item = norm(a.item || a.target);
            if (!item) continue;
            // Crafting may consume materials gathered by an earlier action in
            // the same task. Remove those intermediates before checking final
            // deltas so valid mine -> craft plans do not fail verification.
            accountForCraftConsumption(expected, item, count);
            addExpectedGain(expected, [item], count);
        } else if (t === 'obtain') {
            const item = norm(a.item || a.target);
            if (item) addExpectedGain(expected, [item], count);
        } else if (t === 'mine') {
            const drops = dropItemsForBlock(a.item || a.target);
            if (drops.length > 0) addExpectedGain(expected, drops, count);
        }
    }

    return [...expected.values()].filter(e => e.expectedGain > 0);
}

export function snapshotInventory(state) {
    return countInventory(state?.inventory);
}

export function verifyOutcome(baseline, state, expectations) {
    const now = countInventory(state?.inventory);
    const results = [];
    let allMet = true;
    for (const expectation of expectations || []) {
        const items = expectation.items || [expectation.item];
        const gained = items.reduce((sum, item) =>
            sum + ((now.get(item) || 0) - (baseline.get(item) || 0)), 0);
        const expectedGain = Number(expectation.expectedGain) || 0;
        const met = gained >= expectedGain;
        if (!met) allMet = false;
        results.push({
            item: expectation.item,
            ...(items.length > 1 ? { items } : {}),
            expectedGain,
            gained,
            met,
        });
    }
    return {
        met: allMet,
        results,
        reward: allMet ? 1 : (results.some(r => r.gained > 0) ? 0 : -1),
    };
}

export function describeVerification(v) {
    return (v?.results || [])
        .map(r => `${r.item}: +${r.gained}/${r.expectedGain} ${r.met ? 'OK' : 'MISSING'}`)
        .join(', ');
}
