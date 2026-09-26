const FLEE_COOLDOWN_MS = 8000;
const norm = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();

export class SurvivalReflex {
    constructor(opts = {}) {
        this.fleeHp = opts.fleeHp ?? 6;
        this._lastFleeAt = 0;
    }

    hostilePresent(state) {
        const ents = state?.nearby_entities || state?.entities || [];
        return ents.some(e => e && (e.hostile === true || e.is_hostile === true ||
            /zombie|skeleton|creeper|spider|witch|piglin|hoglin|ghast|blaze|enderman|drowned|husk|pillager|vindicator|warden/.test(norm(e.type || e.name))));
    }

    evaluate(state, ctx = {}) {
        if (!state || !state.connected) return null;
        const hp = Number(state.health);
        const now = Date.now();
        if (Number.isFinite(hp) && hp <= this.fleeHp && this.hostilePresent(state)
            && (now - this._lastFleeAt) >= FLEE_COOLDOWN_MS && ctx.safePos) {
            this._lastFleeAt = now;
            const p = ctx.safePos;
            return { reason: `flee hp=${hp}`, commands: ['#cancel', `#goto ${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)}`] };
        }
        return null;
    }
}
