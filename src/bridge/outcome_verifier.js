const norm = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();
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
        if (t === 'craft' || t === 'obtain' || t === 'mine') {
            const item = norm(a.item || a.target);
            if (item) merged.set(item, (merged.get(item) || 0) + (Number(a.count) || 1));
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
