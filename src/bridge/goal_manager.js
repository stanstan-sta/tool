import { readFileSync, writeFileSync, existsSync } from 'fs';

const DEFAULT_MAX_ATTEMPTS = 12;

function normItem(name) {
    return String(name || '').replace(/^minecraft:/i, '').toLowerCase();
}

function inventoryCount(inventory, item) {
    const want = normItem(item);
    let total = 0;
    for (const stack of inventory || []) {
        if (stack && normItem(stack.item) === want) total += Number(stack.count) || 0;
    }
    return total;
}

export class GoalManager {
    constructor(filePath) {
        this.filePath = filePath;
        this.goal = null;
    }

    _blank(text, target) {
        return {
            text: String(text || '').trim(),
            target: target || null,         // { item, count } or null
            status: 'active',               // active | done | failed
            attempts: 0,
            maxAttempts: DEFAULT_MAX_ATTEMPTS,
            noProgressStreak: 0,
            maxNoProgress: 4,
            lastTargetCount: null,
            createdAt: Date.now(),
            updatedAt: Date.now(),
        };
    }

    set(text, target = null) {
        this.goal = this._blank(text, target);
        this.save();
        return this.goal;
    }

    clear() {
        this.goal = null;
        this.save();
    }

    isActive() {
        return !!this.goal && this.goal.status === 'active';
    }

    describe() {
        if (!this.goal) return 'none';
        let s = `"${this.goal.text}" [${this.goal.status}, attempt ${this.goal.attempts}/${this.goal.maxAttempts}]`;
        if (this.goal.target) s += ` target=${this.goal.target.count}x ${this.goal.target.item}`;
        return s;
    }

    recordAttempt(state) {
        if (!this.goal) return;
        const maxNoProgress = this.goal.maxNoProgress || 4;
        if (this.goal.attempts >= this.goal.maxAttempts
            || (this.goal.target && this.goal.noProgressStreak >= maxNoProgress)) {
            this.goal.status = 'failed';
            this.save();
            return;
        }
        this.goal.attempts += 1;
        if (this.goal.target) {
            const have = inventoryCount(state?.inventory, this.goal.target.item);
            if (this.goal.lastTargetCount !== null && have <= this.goal.lastTargetCount) {
                this.goal.noProgressStreak += 1;
            } else {
                this.goal.noProgressStreak = 0;
            }
            this.goal.lastTargetCount = have;
        }
        this.goal.updatedAt = Date.now();
        this.save();
    }

    checkCompletion(state) {
        if (!this.goal || this.goal.status !== 'active') return false;
        if (!this.goal.target) return false; // text-only goals complete via LLM goal_done
        const have = inventoryCount(state?.inventory, this.goal.target.item);
        if (have >= this.goal.target.count) {
            this.goal.status = 'done';
            this.goal.updatedAt = Date.now();
            this.save();
            return true;
        }
        return false;
    }

    markDone() {
        if (this.goal) { this.goal.status = 'done'; this.save(); }
    }

    load() {
        try {
            if (existsSync(this.filePath)) {
                const raw = JSON.parse(readFileSync(this.filePath, 'utf8'));
                if (raw && typeof raw === 'object' && raw.text) this.goal = raw;
            }
        } catch { /* ignore corrupt goal file */ }
        return this.goal;
    }

    save() {
        try {
            writeFileSync(this.filePath, JSON.stringify(this.goal, null, 2));
        } catch { /* non-fatal */ }
    }
}

// Returns { kind: 'set'|'clear'|'status', text? } or null if not a goal command.
export function parseGoalCommand(message) {
    const text = String(message || '').trim();
    const m = text.match(/^(?:goal|set goal)\s*[:=]\s*(.+)$/i);
    if (m) return { kind: 'set', text: m[1].trim() };
    if (/^(?:stop goal|clear goal|cancel goal|abandon goal)$/i.test(text)) return { kind: 'clear' };
    if (/^(?:goal status|current goal|what'?s your goal)\??$/i.test(text)) return { kind: 'status' };
    return null;
}

// Conservatively extract { item, count } from goal text using a known-item set.
// Returns null when no confident match (goal stays text-only).
export function inferGoalTarget(text, knownItems) {
    const m = String(text || '').toLowerCase().match(/(\d+)\s+([a-z_ ]+?)s?\b/);
    if (!m) return null;
    const count = Number(m[1]);
    const base = m[2].trim().replace(/\s+/g, '_');
    for (const c of [base, base + 's', base.replace(/s$/, '')]) {
        if (knownItems && knownItems.has(c)) return { item: c, count };
    }
    return null;
}
