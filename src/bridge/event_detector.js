const COOLDOWNS = {
    night_start: 0,
    sunrise: 0,
    weather_start_rain: 120_000,
    weather_end_rain: 120_000,
    weather_start_thunder: 120_000,
    weather_end_thunder: 120_000,
    hostile_entered_range: 45_000,
    queue_complete: 10_000,
    queue_failed: 10_000,
    low_hp: 30_000,
    low_food: 30_000,
    new_player_nearby: 30_000,
    player_left_nearby: 30_000,
    player_idle: 300_000,
};

/**
 * Diff-based event detector for the Fabric bridge state stream.
 * Owns a lastState reference and emits discrete events with per-type cooldowns.
 */
export class EventDetector {
    constructor() {
        this.lastState = null;
        this.cooldowns = new Map(); // eventType -> lastFiredMs
    }

    check(state) {
        if (!state || !state.connected) return [];
        const events = [];
        const now = Date.now();

        // Drain mod-provided world events directly
        if (Array.isArray(state.recent_events)) {
            for (const ev of state.recent_events) {
                const type = String(ev.type || '');
                if (this._canFire(type, now)) {
                    events.push({ type, detail: String(ev.detail || ''), source: 'mod' });
                    this._recordFire(type, now);
                }
            }
        }

        // Node-diffed transitions (only if we have a previous state)
        if (this.lastState) {
            // low_hp transition
            if (state.low_hp_flag && !this.lastState.low_hp_flag) {
                if (this._canFire('low_hp', now)) {
                    events.push({ type: 'low_hp', detail: String(state.health || 0), source: 'diff' });
                    this._recordFire('low_hp', now);
                }
            }
            // low_food transition
            if (state.low_food_flag && !this.lastState.low_food_flag) {
                if (this._canFire('low_food', now)) {
                    events.push({ type: 'low_food', detail: String(state.hunger || 0), source: 'diff' });
                    this._recordFire('low_food', now);
                }
            }
            // new_player_nearby
            const prevPlayers = new Set(this.lastState.nearby_players || []);
            const currPlayers = new Set(state.nearby_players || []);
            for (const name of currPlayers) {
                if (!prevPlayers.has(name)) {
                    if (this._canFire('new_player_nearby', now)) {
                        events.push({ type: 'new_player_nearby', detail: name, source: 'diff' });
                        this._recordFire('new_player_nearby', now);
                    }
                }
            }
            // player_left_nearby
            for (const name of prevPlayers) {
                if (!currPlayers.has(name)) {
                    if (this._canFire('player_left_nearby', now)) {
                        events.push({ type: 'player_left_nearby', detail: name, source: 'diff' });
                        this._recordFire('player_left_nearby', now);
                    }
                }
            }
            // player_idle transition (AFK)
            const wasIdle = (this.lastState.player_idle_ms || 0) > 300_000;
            const isIdle = (state.player_idle_ms || 0) > 300_000;
            if (isIdle && !wasIdle) {
                if (this._canFire('player_idle', now)) {
                    events.push({ type: 'player_idle', detail: String(state.player_idle_ms || 0), source: 'diff' });
                    this._recordFire('player_idle', now);
                }
            }
        }

        this.lastState = {
            low_hp_flag: state.low_hp_flag !== undefined ? state.low_hp_flag : this.lastState?.low_hp_flag,
            low_food_flag: state.low_food_flag !== undefined ? state.low_food_flag : this.lastState?.low_food_flag,
            nearby_players: state.nearby_players !== undefined ? state.nearby_players : this.lastState?.nearby_players,
            player_idle_ms: state.player_idle_ms !== undefined ? state.player_idle_ms : this.lastState?.player_idle_ms,
            lastMs: Date.now(),
        };
        return events;
    }

    _canFire(type, now) {
        const cooldown = COOLDOWNS[type] || 0;
        const last = this.cooldowns.get(type) || 0;
        return (now - last) >= cooldown;
    }

    _recordFire(type, now) {
        this.cooldowns.set(type, now);
    }
}
