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
 *   #mine <count> <block> [secondary]  — Baritone: mine a block type
 *   #find <block>          — Baritone: locate special blocks like containers or beds; not common blocks like wood or stone
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
     * Send a batch of typed actions to the Fabric client.
     * Routes through the /batch endpoint which queues actions sequentially
     * in the Fabric mod's TaskQueue, advancing on Baritone completion signals.
     * @param {object[]} actions
     * @returns {Promise<{success: boolean, queued?: number, error?: string}>}
     */
    async sendBatch(actions) {
        try {
            const res = await fetch(`${this.url}/batch`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ actions }),
                signal: AbortSignal.timeout(25000),
            });
            if (!res.ok) {
                // Surface the planner's actual error message instead of just the status code.
                let detail = `HTTP ${res.status}`;
                try {
                    const body = await res.text();
                    if (body) {
                        detail += `: ${body}`;
                        try {
                            const json = JSON.parse(body);
                            return { ...json, success: false, error: detail };
                        } catch {
                            // Body was not JSON.
                        }
                    }
                } catch {
                    // Ignore unreadable error body.
                }
                return { success: false, error: detail };
            }
            const json = await res.json();
            if (json && json.success === false && !json.error) {
                json.error = summarizeBatchError(json) || 'unknown error';
            }
            return json;
        } catch (err) {
            return { success: false, error: err.message };
        }
    }

    /**
     * Send a batch of raw command strings.
     * @param {string[]} commands
     * @returns {Promise<{success: boolean, queued?: number, error?: string}>}
     */
    async sendBatchCommands(commands) {
        try {
            const res = await fetch(`${this.url}/batch`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ commands }),
                signal: AbortSignal.timeout(8000),
            });
            if (!res.ok) {
                let detail = `HTTP ${res.status}`;
                try {
                    const body = await res.text();
                    if (body) {
                        detail += `: ${body}`;
                        try {
                            const json = JSON.parse(body);
                            return { ...json, success: false, error: detail };
                        } catch {
                            // Body was not JSON.
                        }
                    }
                } catch {
                    // Ignore unreadable error body.
                }
                return { success: false, error: detail };
            }
            const json = await res.json();
            if (json && json.success === false && !json.error) {
                json.error = summarizeBatchError(json) || 'unknown error';
            }
            return json;
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
     * Skip the currently-failed task and advance the queue.
     * @returns {Promise<{success: boolean, error?: string}>}
     */
    async skipQueue() {
        try {
            const res = await fetch(`${this.url}/queue/skip`, {
                method: 'POST',
                signal: AbortSignal.timeout(3000),
            });
            if (!res.ok) return { success: false, error: `HTTP ${res.status}` };
            return await res.json();
        } catch (err) {
            return { success: false, error: err.message };
        }
    }

    /**
     * Resume a paused queue (retries the failed task).
     * @returns {Promise<{success: boolean, error?: string}>}
     */
    async resumeQueue() {
        try {
            const res = await fetch(`${this.url}/queue/resume`, {
                method: 'POST',
                signal: AbortSignal.timeout(3000),
            });
            if (!res.ok) return { success: false, error: `HTTP ${res.status}` };
            return await res.json();
        } catch (err) {
            return { success: false, error: err.message };
        }
    }

    /**
     * Cancel all queued and active tasks.
     * @returns {Promise<{success: boolean, error?: string}>}
     */
    async cancelQueue() {
        try {
            const res = await fetch(`${this.url}/queue/cancel`, {
                method: 'POST',
                signal: AbortSignal.timeout(3000),
            });
            if (!res.ok) return { success: false, error: `HTTP ${res.status}` };
            return await res.json();
        } catch (err) {
            return { success: false, error: err.message };
        }
    }

    /**
     * Get queue state (status, active task, pending count, paused, last failure).
     * @returns {Promise<{status: string, active: string|null, pending: number, paused: boolean, lastFailure?: string}|null>}
     */
    async getQueueState() {
        try {
            const res = await fetch(`${this.url}/queue/state`, {
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
     * Read block identifier strings from an axis-aligned box on the client.
     * Returns { origin:[x,y,z], size:[w,h,l], blocks:[id,...] } or null.
     * Mod-side clamps w/h/l to 24 each to avoid oversized responses.
     * @returns {Promise<{origin:number[],size:number[],blocks:string[]}|null>}
     */
    async readBlocks({ x, y, z, w, h, l }) {
        try {
            const query = `x=${x}&y=${y}&z=${z}&w=${w}&h=${h}&l=${l}`;
            const res = await fetch(`${this.url}/read_blocks?${query}`, {
                signal: AbortSignal.timeout(5000),
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
            .filter(i => i && i.item)
            .map(i => `${i.count || 1}x ${i.item.replace('minecraft:', '')}`)
            .join(', ') || 'empty';
        const nearby = (state.nearby_players || []).join(', ') || 'none';
        const nearbyEntities = (state.nearby_entities || [])
            .slice(0, 5)
            .map(e => `${e.type}@(${e.x},${e.y},${e.z})`)
            .join(', ') || 'none';
        const dim = (state.dimension || 'overworld').replace('minecraft:', '');

        // Phase 1: held items + equipment (defensive)
        const held = state.held_items?.main_hand?.item
            ? state.held_items.main_hand.item.replace('minecraft:', '')
            : 'empty';
        const armor = state.equipment
            ? ['head', 'chest', 'legs', 'feet']
                .map(slot => state.equipment[slot]?.item?.replace('minecraft:', '') || 'empty')
                .join('/')
            : 'unknown';

        const lines = [
            `Position: x=${state.x}, y=${state.y}, z=${state.z}  Dimension: ${dim}`,
            `Health: ${state.health}/20  Hunger: ${state.hunger}/20  Mode: ${state.gameMode || '?'}`,
            `Held: ${held}  Armor: ${armor}`,
            `Inventory: ${inv}`,
            `Nearby players: ${nearby}`,
            `Nearby entities: ${nearbyEntities}`,
        ];

        // Phase 1: status effects (defensive)
        if (state.effects && state.effects.length > 0) {
            lines.push(`Effects: ${state.effects.map(e => `${(e.id || '').replace('minecraft:', '')}(${e.amplifier + 1})`).join(', ')}`);
        }

        // Phase 1: open screen (defensive)
        if (state.open_screen?.open) {
            lines.push(`Open screen: ${state.open_screen.handler_class || '?'}`);
        }

        // Include queue status if non-idle
        if (state.queue && state.queue.status !== 'idle' && state.queue.status !== 'disabled') {
            const q = state.queue;
            let queueLine = `Queue: ${q.status} | Pending: ${q.pending}`;
            if (q.active) queueLine += ` | Active: ${q.active}`;
            if (q.paused) queueLine += ` | PAUSED`;
            if (q.lastFailure) queueLine += ` | Last failure: ${q.lastFailure}`;
            lines.push(queueLine);
        }

        return lines.join('\n');
    }
}

function summarizeBatchError(json) {
    if (!json || !Array.isArray(json.results)) return null;
    const failed = json.results.find(r => r && r.status === 'rejected') || json.results.find(r => r && r.failure_code);
    if (!failed) return null;
    const code = failed.failure_code ? String(failed.failure_code) : 'rejected';
    const message = failed.message ? String(failed.message) : '';
    return message ? `${code}: ${message}` : code;
}
