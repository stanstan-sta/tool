// Voyager-style automatic curriculum for the bridge runtime.
//
// When the bot is idle, has no goal and nobody is talking to it, the curriculum proposes
// the next milestone on a survival tech tree. The proposal becomes an ordinary goal with a
// verifiable {item, count} target, so the existing goal engine, outcome verifier and skill
// library do the rest. Milestones the bot already satisfies are skipped, and ones it keeps
// failing are deferred so it does not grind on a task it cannot yet do.

const normItem = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();

function countMatching(inventory, pattern) {
    let total = 0;
    for (const s of inventory || []) {
        if (!s || !s.item) continue;
        const item = normItem(s.item);
        if (pattern instanceof RegExp ? pattern.test(item) : item === pattern) total += Number(s.count) || 0;
    }
    return total;
}

// Ordered survival progression. `have` decides whether a milestone is already satisfied
// (it can accept equivalents, e.g. any log); `target` is what the goal engine verifies.
export const MILESTONES = [
    { text: 'collect 8 oak logs', target: { item: 'oak_log', count: 8 }, have: inv => countMatching(inv, /_log$|_stem$/) >= 8 },
    { text: 'craft 1 crafting table', target: { item: 'crafting_table', count: 1 }, have: inv => countMatching(inv, 'crafting_table') >= 1 },
    { text: 'craft 1 wooden pickaxe', target: { item: 'wooden_pickaxe', count: 1 }, have: inv => countMatching(inv, /_pickaxe$/) >= 1 },
    { text: 'mine 16 cobblestone', target: { item: 'cobblestone', count: 16 }, have: inv => countMatching(inv, /^(cobblestone|cobbled_deepslate)$/) >= 16 },
    { text: 'craft 1 stone pickaxe', target: { item: 'stone_pickaxe', count: 1 }, have: inv => countMatching(inv, /^(stone|iron|diamond|netherite)_pickaxe$/) >= 1 },
    { text: 'craft 1 stone sword', target: { item: 'stone_sword', count: 1 }, have: inv => countMatching(inv, /^(stone|iron|diamond|netherite)_sword$/) >= 1 },
    { text: 'craft 1 furnace', target: { item: 'furnace', count: 1 }, have: inv => countMatching(inv, 'furnace') >= 1 },
    { text: 'collect 8 coal', target: { item: 'coal', count: 8 }, have: inv => countMatching(inv, /^(coal|charcoal)$/) >= 8 },
    { text: 'craft 16 torches', target: { item: 'torch', count: 16 }, have: inv => countMatching(inv, 'torch') >= 16 },
    { text: 'collect 6 cooked food', target: { item: 'cooked_beef', count: 6 }, have: inv => countMatching(inv, /^(cooked_|bread$|baked_potato$)/) >= 6 },
    { text: 'smelt 8 iron ingots', target: { item: 'iron_ingot', count: 8 }, have: inv => countMatching(inv, 'iron_ingot') >= 8 },
    { text: 'craft 1 iron pickaxe', target: { item: 'iron_pickaxe', count: 1 }, have: inv => countMatching(inv, /^(iron|diamond|netherite)_pickaxe$/) >= 1 },
    { text: 'craft 1 iron sword', target: { item: 'iron_sword', count: 1 }, have: inv => countMatching(inv, /^(iron|diamond|netherite)_sword$/) >= 1 },
    { text: 'craft 1 shield', target: { item: 'shield', count: 1 }, have: inv => countMatching(inv, 'shield') >= 1 },
    { text: 'craft 1 iron chestplate', target: { item: 'iron_chestplate', count: 1 }, have: inv => countMatching(inv, /^(iron|diamond|netherite)_chestplate$/) >= 1 },
    { text: 'craft 1 bucket', target: { item: 'bucket', count: 1 }, have: inv => countMatching(inv, /bucket$/) >= 1 },
    { text: 'mine 3 diamonds', target: { item: 'diamond', count: 3 }, have: inv => countMatching(inv, 'diamond') >= 3 },
    { text: 'craft 1 diamond pickaxe', target: { item: 'diamond_pickaxe', count: 1 }, have: inv => countMatching(inv, /^(diamond|netherite)_pickaxe$/) >= 1 },
];

export class Curriculum {
    /**
     * @param {{maxFailures?: number, milestones?: object[]}} opts
     */
    constructor(opts = {}) {
        this.maxFailures = opts.maxFailures ?? 3;
        this.milestones = opts.milestones || MILESTONES;
        this.deferred = new Map(); // milestone text -> timestamp when it may be retried
        this.deferMs = opts.deferMs ?? 30 * 60 * 1000;
    }

    /**
     * @param {object} state bridge /state snapshot
     * @param {{failureCount?: (text: string) => number}} library skill library (optional)
     * @returns {{text: string, target: {item: string, count: number}}|null}
     */
    proposeNext(state, library = null) {
        const inv = state?.inventory || [];
        const now = Date.now();
        for (const m of this.milestones) {
            if (m.have(inv)) continue;
            const until = this.deferred.get(m.text);
            if (until && until > now) continue;
            if (library?.failureCount && library.failureCount(m.text) >= this.maxFailures && !until) {
                this.defer(m.text);
                continue;
            }
            return { text: m.text, target: { ...m.target } };
        }
        return null;
    }

    // Park a milestone after the goal engine gives up on it.
    defer(text) {
        this.deferred.set(text, Date.now() + this.deferMs);
    }

    progress(state) {
        const inv = state?.inventory || [];
        const done = this.milestones.filter(m => m.have(inv)).length;
        return { done, total: this.milestones.length };
    }
}
