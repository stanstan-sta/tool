const norm = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();

// N1: single shared mined-block → inventory-drop map. Mining never yields the
// block itself for these ores/stone: the verifier expectation AND the
// curriculum goal-side check (countForGoal) both translate through this one
// object, so they cannot disagree. Unknown blocks map to themselves
// (direct pickup, e.g. cobblestone, logs, dirt). Silk-touch/fortune
// exceptions are out of scope and noted in IMPLEMENTATION_W5.md.
export const BLOCK_DROPS = {
    stone: 'cobblestone',
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
    deepslate: 'cobbled_deepslate',
    nether_gold_ore: 'gold_nugget',
};

export function dropForBlock(name) {
    const key = norm(name);
    return BLOCK_DROPS[key] || key;
}
function countInventory(inventory) {
    const m = new Map();
    for (const s of inventory || []) {
        if (!s || !s.item) continue;
        m.set(norm(s.item), (m.get(norm(s.item)) || 0) + (Number(s.count) || 0));
    }
    return m;
}

export function expectFromActions(actions) {
    const merged = new Map();
    for (const a of actions || []) {
        if (!a || typeof a !== 'object') continue;
        const t = String(a.type || '').toLowerCase();
        if (t === 'craft' || t === 'obtain') {
            const item = norm(a.item || a.target);
            if (item) merged.set(item, (merged.get(item) || 0) + (Number(a.count) || 1));
        } else if (t === 'mine') {
            // N1: a mined block yields its drop, never the block name.
            const drop = dropForBlock(a.item || a.target);
            if (drop) merged.set(drop, (merged.get(drop) || 0) + (Number(a.count) || 1));
        }
    }
    return [...merged.entries()].map(([item, expectedGain]) => ({ item, expectedGain }));
}

export function snapshotInventory(state) { return countInventory(state?.inventory); }

export function verifyOutcome(baseline, state, expectations) {
    const now = countInventory(state?.inventory);
    const results = [];
    let allMet = true;
    for (const { item, expectedGain } of expectations) {
        const gained = (now.get(item) || 0) - (baseline.get(item) || 0);
        const met = gained >= expectedGain;
        if (!met) allMet = false;
        results.push({ item, expectedGain, gained, met });
    }
    return { met: allMet, results, reward: allMet ? 1 : (results.some(r => r.gained > 0) ? 0 : -1) };
}

export function describeVerification(v) {
    return v.results.map(r => `${r.item}: +${r.gained}/${r.expectedGain} ${r.met ? 'OK' : 'MISSING'}`).join(', ');
}
