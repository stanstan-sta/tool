// Voyager-style automatic curriculum for the bridge runtime.
//
// When the bot is idle, has no goal and nobody is talking to it, the curriculum proposes
// the next milestone on a survival tech tree. The proposal becomes an ordinary goal with a
// verifiable {item, count} target, so the existing goal engine, outcome verifier and skill
// library do the rest. Milestones the bot already satisfies are skipped, and ones it keeps
// failing are deferred so it does not grind on a task it cannot yet do.

import { existsSync, readFileSync, writeFileSync } from 'fs';
import { BLOCK_DROPS, dropItemsForBlock } from './outcome_verifier.js';

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

function milestone(text, item, count, match) {
    return {
        text,
        target: { item, count },
        // N2: `match` is the single source for BOTH satisfaction (`have`) and
        // goal-side counting (`countForGoal`), so a goal accepts exactly the
        // same equivalents `have()` does.
        match,
        have: inv => countMatching(inv, match) >= count,
    };
}

// Ordered survival progression. `have` decides whether a milestone is already satisfied
// (it can accept equivalents, e.g. any log); `target` is what the goal engine verifies.
export const MILESTONES = [
    milestone('collect 8 oak logs', 'oak_log', 8, /_log$|_stem$/),
    milestone('craft 1 crafting table', 'crafting_table', 1, 'crafting_table'),
    milestone('craft 1 wooden pickaxe', 'wooden_pickaxe', 1, /_pickaxe$/),
    milestone('mine 16 cobblestone', 'cobblestone', 16, /^(cobblestone|cobbled_deepslate)$/),
    milestone('craft 1 stone pickaxe', 'stone_pickaxe', 1, /^(stone|iron|diamond|netherite)_pickaxe$/),
    milestone('craft 1 stone sword', 'stone_sword', 1, /^(stone|iron|diamond|netherite)_sword$/),
    milestone('craft 1 furnace', 'furnace', 1, 'furnace'),
    milestone('collect 8 coal', 'coal', 8, /^(coal|charcoal)$/),
    milestone('craft 16 torches', 'torch', 16, 'torch'),
    milestone('collect 6 cooked food', 'cooked_beef', 6, /^(cooked_|bread$|baked_potato$)/),
    milestone('smelt 8 iron ingots', 'iron_ingot', 8, 'iron_ingot'),
    milestone('craft 1 iron pickaxe', 'iron_pickaxe', 1, /^(iron|diamond|netherite)_pickaxe$/),
    milestone('craft 1 iron sword', 'iron_sword', 1, /^(iron|diamond|netherite)_sword$/),
    milestone('craft 1 shield', 'shield', 1, 'shield'),
    milestone('craft 1 iron chestplate', 'iron_chestplate', 1, /^(iron|diamond|netherite)_chestplate$/),
    milestone('craft 1 bucket', 'bucket', 1, /bucket$/),
    milestone('mine 3 diamonds', 'diamond', 3, 'diamond'),
    milestone('craft 1 diamond pickaxe', 'diamond_pickaxe', 1, /^(diamond|netherite)_pickaxe$/),
];

export class Curriculum {
    /**
     * @param {{maxFailures?: number, milestones?: object[], filePath?: string, completed?: string[]}} opts
     */
    constructor(opts = {}) {
        this.maxFailures = opts.maxFailures ?? 3;
        this.milestones = opts.milestones || MILESTONES;
        this.deferred = new Map(); // milestone text -> timestamp when it may be retried
        this.deferMs = opts.deferMs ?? 30 * 60 * 1000;
        // F2: persisted completed-milestone set. Once a milestone is earned it
        // stays done even if the resource is later consumed, so progression
        // cannot move backwards. Purely in-memory when no filePath is given.
        this.filePath = null;
        this.completed = new Set(
            Array.isArray(opts.completed) ? opts.completed.filter(t => typeof t === 'string') : [],
        );
        if (opts.filePath) this.setFilePath(opts.filePath);
    }

    // Attach (or re-attach) the persistence file. Idempotent: loading twice is
    // a no-op. Called lazily from the curriculum tick so Curriculum stays
    // constructible without a bot name in unit tests.
    setFilePath(path) {
        if (!path || path === this.filePath) return;
        this.filePath = path;
        this.load();
    }

    load() {
        try {
            if (this.filePath && existsSync(this.filePath)) {
                const raw = JSON.parse(readFileSync(this.filePath, 'utf8'));
                if (raw && Array.isArray(raw.completed)) {
                    this.completed = new Set(raw.completed.filter(t => typeof t === 'string'));
                }
            }
        } catch { /* corrupt curriculum file keeps the in-memory set */ }
        return this.completed;
    }

    save() {
        if (!this.filePath) return;
        try {
            writeFileSync(this.filePath, JSON.stringify({ completed: [...this.completed] }, null, 2));
        } catch { /* non-fatal */ }
    }

    // Record a milestone as earned. Returns false when it was already stored.
    markComplete(text) {
        const t = String(text || '').trim();
        if (!t || this.completed.has(t)) return false;
        this.completed.add(t);
        this.save();
        return true;
    }

    isComplete(text) {
        return this.completed.has(String(text || '').trim());
    }

    _findMilestone(goal) {
        const text = String(goal?.text || '').trim();
        if (text) {
            const byText = this.milestones.find(m => m.text === text);
            if (byText) return byText;
        }
        const t = goal?.target;
        if (t && t.item) {
            const want = normItem(t.item);
            const byTarget = this.milestones.find(m =>
                normItem(m.target?.item) === want && Number(m.target?.count) === Number(t.count));
            if (byTarget) return byTarget;
        }
        return null;
    }

    // N1+N2: equivalent-aware inventory count for a goal target. Milestone
    // goals count through the same `match` pattern `have()` uses; a goal that
    // names a mineable block instead counts its drop through the shared
    // BLOCK_DROPS map (e.g. a `stone` target counts `cobblestone`); anything
    // else counts exactly.
    countForGoal(goal, inventory) {
        const m = this._findMilestone(goal);
        if (m && m.match) return countMatching(inventory, m.match);
        const t = normItem(goal?.target?.item);
        if (!t) return 0;
        const drops = dropItemsForBlock(t);
        if (drops.length === 0) return 0;
        return drops.reduce((sum, item) => sum + countMatching(inventory, item), 0);
    }

    // N2: a curriculum goal is met exactly when `have()`-equivalent items
    // cover its target count. Text-only goals (no target) never complete here;
    // they still complete via the LLM `goal_done` path.
    isGoalMet(goal, inventory) {
        const need = Number(goal?.target?.count) || 0;
        if (!goal || !goal.target || need <= 0) return false;
        return this.countForGoal(goal, inventory) >= need;
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
            if (m.have(inv)) {
                // F2: uphold what is earned — a satisfied milestone joins the
                // persisted set so consuming the resource cannot regress it.
                this.markComplete(m.text);
                continue;
            }
            if (this.completed.has(m.text)) continue;
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
        const done = this.milestones.filter(m => this.completed.has(m.text) || m.have(inv)).length;
        return { done, total: this.milestones.length };
    }
}

// Re-exported so tick code and tests can prove both sides share one map.
export { BLOCK_DROPS };
