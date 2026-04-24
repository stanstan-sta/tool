/**
 * HTTP client for the Mindcraft Fabric Bridge Mod.
 *
 * The Fabric mod runs an HTTP server (default localhost:8765) that exposes:
 *   GET  /ping     — liveness check
 *   GET  /state    — current player state (position, health, inventory, queued chat)
 *   POST /command  — execute a command on the Minecraft client
 *
 * Supported command types:
 *   #goto x y z            — Baritone: navigate to coordinates
 *   #mine <block> <count>  — Baritone: mine a block type
 *   #follow player <name>  — Baritone: follow a player
 *   #cancel                — Baritone: cancel current task
 *   #explore               — Baritone: explore the world
 *   /say <text>            — Minecraft slash command
 *   chat: <text>           — Send public chat message
 *   whisper: <player> <msg>— Whisper to a player
 */
export class FabricBridge {
    constructor(url = 'http://localhost:8765') {
        this.url = url.replace(/\/$/, '');
    }

    /**
     * Check whether the Fabric mod's HTTP server is reachable.
     * @returns {Promise<boolean>}
     */
    async isReachable() {
        try {
            const res = await fetch(`${this.url}/ping`, {
                signal: AbortSignal.timeout(2000),
            });
            return res.ok;
        } catch {
            return false;
        }
    }

    /**
     * Send a single command to the Fabric client.
     * @param {string} command
     * @returns {Promise<{success: boolean, output?: string, error?: string}>}
     */
    async sendCommand(command) {
        try {
            const res = await fetch(`${this.url}/command`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ command }),
                signal: AbortSignal.timeout(8000),
            });
            if (!res.ok) return { success: false, error: `HTTP ${res.status}` };
            return await res.json();
        } catch (err) {
            return { success: false, error: err.message };
        }
    }

    /**
     * Send a typed action to the Fabric client.
     * @param {object} action
     * @returns {Promise<{success: boolean, output?: string, error?: string}>}
     */
    async sendAction(action) {
        try {
            const res = await fetch(`${this.url}/action`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action }),
                signal: AbortSignal.timeout(8000),
            });
            if (!res.ok) return { success: false, error: `HTTP ${res.status}` };
            return await res.json();
        } catch (err) {
            return { success: false, error: err.message };
        }
    }

    /**
     * Retrieve bridge/runtime capabilities.
     * @returns {Promise<object|null>}
     */
    async getCapabilities() {
        try {
            const res = await fetch(`${this.url}/capabilities`, {
                signal: AbortSignal.timeout(3000),
            });
            if (!res.ok) return null;
            return await res.json();
        } catch {
            return null;
        }
    }

    /**
     * Retrieve the current command registry from the Fabric client.
     * @returns {Promise<Array<string>>}
     */
    async getCommands() {
        try {
            const res = await fetch(`${this.url}/commands`, {
                signal: AbortSignal.timeout(3000),
            });
            if (!res.ok) return [];
            const data = await res.json();
            return Array.isArray(data) ? data : [];
        } catch {
            return [];
        }
    }

    /**
     * Get the current player state snapshot from the Fabric client.
     * The `chat` array is automatically cleared by the mod after each /state call
     * so callers always receive only new messages.
     *
     * @returns {Promise<FabricState|null>}
     *
     * @typedef {Object} FabricState
     * @property {boolean} connected
     * @property {number}  x
     * @property {number}  y
     * @property {number}  z
     * @property {number}  health
     * @property {number}  hunger
     * @property {number}  saturation
     * @property {string}  dimension   e.g. "minecraft:overworld"
     * @property {string}  gameMode    e.g. "SURVIVAL"
     * @property {Array<{slot:number,item:string,count:number}>} inventory
     * @property {string[]} nearby_players
     * @property {string[]} chat        messages received since last poll
     */
    async getState(sinceSeq = null, options = {}) {
        try {
            const query = [];
            if (sinceSeq != null) query.push(`since=${encodeURIComponent(String(sinceSeq))}`);
            if (options.includeSurfaceMap === true) query.push('surface=true');
            if (Number.isFinite(options.surfaceRadius)) query.push(`surface_radius=${encodeURIComponent(String(options.surfaceRadius))}`);
            const suffix = query.length ? `?${query.join('&')}` : '';
            const res = await fetch(`${this.url}/state${suffix}`, {
                signal: AbortSignal.timeout(3000),
            });
            if (!res.ok) return null;
            return await res.json();
        } catch {
            return null;
        }
    }

    /**
     * Format a FabricState snapshot as a compact, human-readable summary
     * suitable for injection into the LLM's conversation history.
     * @param {FabricState} state
     * @returns {string}
     */
    static formatState(state) {
        if (!state || !state.connected) return 'Fabric client not connected.';

        const inv = (state.inventory || [])
            .map(i => `${i.count}x ${i.item.replace('minecraft:', '')}`)
            .join(', ') || 'empty';
        const nearby = (state.nearby_players || []).join(', ') || 'none';
        const nearbyEntities = (state.nearby_entities || [])
            .slice(0, 5)
            .map(e => `${e.type}@(${e.x},${e.y},${e.z})`)
            .join(', ') || 'none';
        const dim = (state.dimension || 'overworld').replace('minecraft:', '');

        return [
            `Position: x=${state.x}, y=${state.y}, z=${state.z}  Dimension: ${dim}`,
            `Health: ${state.health}/20  Hunger: ${state.hunger}/20  Mode: ${state.gameMode || '?'}`,
            `Inventory: ${inv}`,
            `Nearby players: ${nearby}`,
            `Nearby entities: ${nearbyEntities}`,
        ].join('\n');
    }
}
