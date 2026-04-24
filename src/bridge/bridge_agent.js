import { Prompter } from '../models/prompter.js';
import { History } from '../agent/history.js';
import { FabricBridge } from './fabric_bridge.js';
import { buildBridgeSystemPrompt } from './bridge_prompt.js';
import { buildBridgeTopographySystemMessage } from './topography.js';
import { serverProxy, sendOutputToServer, sendLogToUI } from '../agent/mindserver_proxy.js';
import settings from '../agent/settings.js';

const POLL_MIN_MS = 800;
const POLL_DEFAULT_MS = 2000;
const POLL_MAX_MS = 5000;

function clamp(n, min, max) {
    return Math.min(max, Math.max(min, n));
}

function extractJsonObjectCandidate(text) {
    const trimmed = String(text || '').trim();
    if (!trimmed) return null;
    const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
    if (fenced?.[1]) return fenced[1].trim();
    if (trimmed.startsWith('{') && trimmed.endsWith('}')) return trimmed;
    const first = trimmed.indexOf('{');
    const last = trimmed.lastIndexOf('}');
    if (first >= 0 && last > first) return trimmed.slice(first, last + 1);
    return null;
}

function stripChatFormatting(text) {
    return String(text || '').replace(/§[0-9A-FK-OR]/gi, '');
}

function normalizeChatText(text) {
    return String(text || '')
        .replace(/\s+/g, ' ')
        .replace(/§[0-9A-FK-OR]/gi, '')
        .trim()
        .toLowerCase();
}

function parsePlayerChatMessage(message) {
    let clean = stripChatFormatting(message).trim();
    clean = clean.replace(/^\[\d{1,2}:\d{2}:\d{2}\]\s*/i, '').trim();
    clean = clean.replace(/^\[CHAT\]\s*/i, '').trim();
    if (clean.toLowerCase().startsWith("[baritone]")) return null;
    const patterns = [
        /^<([^>]+)>\s*(.+)$/,
        /^\[.*?\]\s*<([^>]+)>\s*(.+)$/,
        /^([^:]+):\s*(.+)$/,
        /^\[.*?\]\s*([^:]+):\s*(.+)$/
    ];
    for (const pattern of patterns) {
        const match = clean.match(pattern);
        if (match) {
            return { from: match[1].trim(), text: match[2].trim() };
        }
    }
    return null;
}

function isChatAllowed(playerName) {
    const name = String(playerName || '').trim().toLowerCase();
    const whitelist = Array.isArray(settings.bridge_chat_whitelist)
        ? settings.bridge_chat_whitelist.map(name => String(name || '').trim().toLowerCase()).filter(Boolean)
        : [];
    const blacklist = Array.isArray(settings.bridge_chat_blacklist)
        ? settings.bridge_chat_blacklist.map(name => String(name || '').trim().toLowerCase()).filter(Boolean)
        : [];
    if (blacklist.includes(name)) return false;
    if (whitelist.length === 0) return true;
    return whitelist.includes(name);
}

function normalizeAction(action) {
    if (!action) return null;
    if (typeof action === 'string') {
        return { type: 'raw_command', command: action };
    }
    if (typeof action !== 'object') return null;
    if (!action.type && action.command) {
        return { type: 'raw_command', command: action.command };
    }
    if (!action.type) return null;
    return action;
}

function commandToTypedAction(cmd) {
    const raw = String(cmd || '').trim();
    const rawLower = raw.toLowerCase();
    if (!raw) return null;
    const goto = raw.match(/^#goto\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)/i);
    if (goto) {
        return { type: 'move', provider: 'baritone_chat', x: Number(goto[1]), y: Number(goto[2]), z: Number(goto[3]) };
    }
    const mine = raw.match(/^#mine\s+([a-z0-9_:-]+)(?:\s+(\d+))?/i);
    if (mine) {
        return { type: 'mine', provider: 'baritone_chat', target: mine[1], count: Number(mine[2] || 1) };
    }
    const follow = raw.match(/^#follow\s+player\s+([^\s]+)$/i);
    if (follow) {
        return { type: 'follow', provider: 'baritone_chat', target: follow[1] };
    }
    if (rawLower === '#cancel') {
        return { type: 'cancel', provider: 'baritone_chat' };
    }
    return { type: 'raw_command', provider: 'baritone_chat', command: raw };
}

/**
 * Parse the LLM's response into a chat text portion and a list of COMMAND: lines.
 *
 * Expected format (any ordering, COMMAND lines can appear multiple times):
 *   THOUGHT: <reasoning>
 *   PLAN: <goal>
 *   COMMAND: #goto 100 64 -200
 *   COMMAND: #mine iron_ore 16
 *
 * Lines that are not THOUGHT/PLAN/COMMAND are treated as chat text.
 * @param {string} response
 * @returns {{chat: string, commands: string[]}}
 */
export function parseBridgeResponse(response, expectStructured = false) {
    const commands = [];
    const actions = [];
    const chatLines = [];

    const jsonCandidate = extractJsonObjectCandidate(response);
    if (jsonCandidate) {
        try {
            const parsed = JSON.parse(jsonCandidate);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                const reply = typeof parsed.reply === 'string'
                    ? parsed.reply.trim()
                    // Backward-compat: allow "chat" from older structured bridge prompts.
                    : (typeof parsed.chat === 'string' ? parsed.chat.trim() : '');
                const structuredActions = Array.isArray(parsed.actions)
                    ? parsed.actions.map(normalizeAction).filter(Boolean)
                    : [];
                return {
                    chat: reply,
                    commands,
                    actions: structuredActions,
                    structured: true,
                };
            }
        } catch {
            // fall back to COMMAND parsing below
        }
    }

    for (const raw of response.split('\n')) {
        const line = raw.trim();
        if (line.startsWith('COMMAND:')) {
            const cmd = line.slice('COMMAND:'.length).trim();
            if (cmd) commands.push(cmd);
        } else if (line.startsWith('ACTION:')) {
            const rawAction = line.slice('ACTION:'.length).trim();
            if (rawAction) {
                let actionObj = rawAction;
                if (rawAction.startsWith('{') && rawAction.endsWith('}')) {
                    try {
                        actionObj = JSON.parse(rawAction);
                    } catch {
                        actionObj = rawAction;
                    }
                }
                const normalized = normalizeAction(actionObj);
                if (normalized) actions.push(normalized);
            }
        } else if (line.startsWith('THOUGHT:') || line.startsWith('PLAN:')) {
            // These are internal reasoning lines — don't show in chat but keep for context
        } else if (line) {
            chatLines.push(line);
        }
    }

    return {
        chat: chatLines.join('\n'),
        commands,
        actions,
        structured: false,
        expectedStructured: expectStructured,
    };
}

/**
 * Minimal stub that satisfies the small subset of the SelfPrompter interface
 * used by Prompter.replaceStrings when $SELF_PROMPT is in the template.
 */
class BridgeSelfPrompter {
    constructor() {
        this.prompt = '';
        this.state = 'stopped';
    }
    isStopped() { return true; }
    isActive() { return false; }
    shouldInterrupt() { return false; }
    handleUserPromptedCmd() {}
}

/**
 * A lightweight agent that controls a Fabric + Baritone Minecraft client via
 * the Mindcraft Bridge Mod's HTTP API. No Mineflayer connection is used.
 *
 * The agent:
 *  1. Polls the Fabric mod for world state and new chat messages.
 *  2. Injects state snapshots into the conversation history.
 *  3. Calls the Ollama/other LLM to decide what to do.
 *  4. Parses COMMAND: lines from the LLM response and forwards them to the mod.
 *  5. Records command results back into history for context.
 */
export class BridgeAgent {
    async start(load_mem = false, init_message = null) {
        this.stopped = false;
        this._inboundQueue = [];
        this._bridgeCommands = [];
        this._bridgeReachable = false;

        // ── Prompter / LLM ────────────────────────────────────────────────────
        this.prompter = new Prompter(this, settings.profile);
        this.name = (this.prompter.getName() || '').trim();
        console.log(`Initializing bridge agent: ${this.name}`);
        // Bridge mode should not use the normal bot prompt template with internal
        // queries like $STATS / $INVENTORY. Use a bridge-specific prompt instead.
        this.prompter.profile.conversing = buildBridgeSystemPrompt(settings, '');
        // ── History ────────────────────────────────────────────────────────────
        this.history = new History(this);

        // ── Stubs for Prompter compatibility ──────────────────────────────────
        this.self_prompter = new BridgeSelfPrompter();
        this.blocked_actions = settings.blocked_actions || [];
        this.actions = { currentActionLabel: 'Idle' };
        this.npc = { constructions: null };
        this.last_sender = null;
        this.shut_up = false;

        // ── Fabric bridge HTTP client ──────────────────────────────────────────
        this.bridge = new FabricBridge(settings.bridge_url || 'http://localhost:8765');
        this._lastStateStr = '';
        this._lastStateSeq = null;
        this._pollIntervalMs = POLL_DEFAULT_MS;
        this._capabilities = null;
        this._recentSentChats = [];

        // ── MindServer registration ────────────────────────────────────────────
        // respondFunc is called by serverProxy when the WebUI sends a message.
        this.respondFunc = (from, msg) => {
            try {
                if (msg) this._inboundQueue.push({ source: from, message: msg });
            } catch (e) {
                console.error('BridgeAgent respondFunc error:', e);
            }
        };
        serverProxy.setAgent(this);
        serverProxy.login();

        await this.prompter.initExamples();

        if (load_mem) {
            this.history.load();
        }

        // Verify mod reachability
        const reachable = await this.bridge.isReachable();
        if (reachable) {
            sendLogToUI(`${this.name}: Fabric bridge mod connected at ${this.bridge.url}`);
            this._capabilities = await this.bridge.getCapabilities();
            if (this._capabilities) {
                sendLogToUI(`${this.name}: Bridge capabilities: provider=${this._capabilities.default_provider || 'unknown'}, typed_actions=${this._capabilities.supports_typed_actions === true}`);
            }
            this._bridgeReachable = true;
            this._bridgeCommands = await this.fetchBridgeCommands();
            if (Array.isArray(this._bridgeCommands) && this._bridgeCommands.length) {
                sendLogToUI(`${this.name}: Loaded ${this._bridgeCommands.length} bridge commands.`);
            }
        } else {
            this._bridgeReachable = false;
            sendLogToUI(`${this.name}: ⚠️  Fabric bridge mod not reachable at ${this.bridge.url} — waiting...`);
        }

        if (init_message) {
            this._inboundQueue.push({ source: 'system', message: init_message });
        } else {
            sendOutputToServer(this.name, `Bridge agent ${this.name} is online. Waiting for commands.`);
        }

        // Start the main loop
        this._runLoop();
    }

    _trackSentChat(message) {
        const normalized = normalizeChatText(message);
        if (!normalized) return;
        this._recentSentChats.unshift(normalized);
        if (this._recentSentChats.length > 32) {
            this._recentSentChats.pop();
        }
    }

    _isSelfSentChat(message) {
        const normalized = normalizeChatText(message);
        if (!normalized) return false;
        return this._recentSentChats.includes(normalized);
    }

    /**
     * Main agent loop. Polls state, processes pending messages, calls LLM.
     */
    async _runLoop() {
        while (!this.stopped) {
            try {
                // ── 1. Poll Fabric mod state ───────────────────────────────────
                const state = await this.bridge.getState(this._lastStateSeq, {
                    includeSurfaceMap: settings.use_textual_topography === true,
                    surfaceRadius: settings.textual_topography_radius || 8
                });
                const nowReachable = Boolean(state && state.connected);
                if (nowReachable && !this._bridgeReachable) {
                    this._bridgeReachable = true;
                    this._bridgeCommands = await this.fetchBridgeCommands();
                } else if (!nowReachable) {
                    this._bridgeReachable = false;
                }

                if (typeof state?.seq === 'number') {
                    this._lastStateSeq = state.seq;
                }

                if (state && state.connected && !state.unchanged) {
                    const stateStr = FabricBridge.formatState(state);
                    this._lastStateStr = stateStr;

                    // ── 2. Bubble up any chat messages received in-game ────────
                    const events = Array.isArray(state.chat_events)
                        ? state.chat_events
                        : (Array.isArray(state.chat) ? state.chat.map(msg => ({ type: 'player', message: msg })) : []);

                    const selfName = String(state?.player_name || this.name || '').trim().toLowerCase();
                    for (const event of events) {
                        const message = String(event?.message || '');
                        if (!message) continue;

                        // If the Fabric mod provided an authoritative sender, skip messages
                        // that were sent by this client to avoid self-looping.
                        const senderField = (event && event.sender) ? String(event.sender).trim() : '';
                        if (senderField) {
                            if (senderField.toLowerCase() === selfName) {
                                // Drop self-generated chat — never enqueue.
                                continue;
                            }
                        }
                        if (this._isSelfSentChat(message)) {
                            continue;
                        }

                        const parsed = parsePlayerChatMessage(message);
                        if (parsed && isChatAllowed(parsed.from)) {
                            const sender = String(parsed.from || '').trim();
                            if (sender.toLowerCase() !== selfName) {
                                this._inboundQueue.push({ source: sender, message: parsed.text });
                            }
                        } else if (!parsed) {
                            const stripped = stripChatFormatting(message);
                            const lower = stripped.toLowerCase();
                            const appearsFromSelf = lower.startsWith(`${selfName}:`) || lower.startsWith(`<${selfName}>`);
                            const mentionsBot = lower.includes(selfName);
                            if (!appearsFromSelf && mentionsBot && isChatAllowed('player')) {
                                this._inboundQueue.push({ source: 'player', message: stripped });
                            } else {
                                // System, self, or non-user chat; preserve for logs only.
                                sendLogToUI(`${this.name}: system message: ${stripped}`);
                                console.log(`${this.name} system chat event: ${message}`);
                                this.history.add('system', stripped);
                            }
                        }
                    }
                }

                // ── 3. Process any inbound queued message ─────────────────────
                if (this._inboundQueue.length > 0) {
                    const { source, message } = this._inboundQueue.shift();
                    console.log(`${this.name} handling message from ${source}: ${message}`);
                    await this._handleMessage(source, message, state);

                    // Tight loop while there's a response to work on
                    this._pollIntervalMs = POLL_MIN_MS;
                    continue;
                }

                if (state?.unchanged) {
                    this._pollIntervalMs = clamp(this._pollIntervalMs + 200, POLL_MIN_MS, POLL_MAX_MS);
                } else {
                    this._pollIntervalMs = clamp(this._pollIntervalMs - 200, POLL_MIN_MS, POLL_DEFAULT_MS);
                }

            } catch (err) {
                console.error('BridgeAgent loop error:', err);
            }

            await new Promise(r => setTimeout(r, this._pollIntervalMs));
        }
    }

    /**
     * Handle a single incoming message — call LLM, execute commands, record results.
     * @param {string} source
     * @param {string} message
     * @param {object|null} state  Most-recent Fabric state snapshot
     */
    async _handleMessage(source, message, state) {
        if (!source || !message) return;

        // Add the triggering message to history.
        if (source !== this.name) {
            this.history.add(source, message);
        }

        // ── 4. Build prompt and call LLM ──────────────────────────────────────
        const history = this.history.getHistory();
        if (settings.use_textual_topography === true && state?.surface_map) {
            history.push({
                role: 'system',
                content: buildBridgeTopographySystemMessage(state.surface_map, {
                    format: settings.textual_topography_format || 'coordinate_list'
                })
            });
        }
        const wantStructured = settings.bridge_structured_output === true;
        if (wantStructured) {
            history.push({
                role: 'system',
                content: buildBridgeSystemPrompt(settings, this.history.memory),
            });
        }
        let response;
        try {
            response = await this.prompter.promptConvo(history);
        } catch (err) {
            console.error('LLM error:', err);
            sendOutputToServer(this.name, `LLM error: ${err.message}`);
            return;
        }

        if (!response || response.trim().length === 0) {
            console.warn(`${this.name}: empty LLM response`);
            return;
        }

        console.log(`${this.name} LLM response: ${response}`);

        // ── 5. Parse COMMAND: lines ───────────────────────────────────────────
        const { chat: rawChat, commands, actions, structured } = parseBridgeResponse(response, wantStructured);
        const suppressChat = /^(no response needed|no reply|none|n\/a)$/i.test(rawChat.trim());
        const chat = suppressChat ? '' : rawChat;

        // Record the full LLM response in history
        this.history.add(this.name, response);

        // Send the chat portion to the WebUI output panel and in-game chat if enabled.
        if (chat.trim()) {
            const trimmedChat = chat.trim();
            sendOutputToServer(this.name, trimmedChat);
            if (settings.chat_ingame === true) {
                this._trackSentChat(trimmedChat);
                await this.bridge.sendCommand(`chat: ${trimmedChat}`);
            }
        }

        // ── 6. Execute each COMMAND: line via Fabric bridge ───────────────────
        for (const action of actions) {
            sendOutputToServer(this.name, `⚡ action:${action.type}`);
            const result = action.type === 'raw_command'
                ? await this.bridge.sendCommand(action.command || '')
                : await this.bridge.sendAction(action);
            if (result.output) {
                this.history.add('system', `action:${action.type} → ${result.output}`);
                sendOutputToServer(this.name, result.output);
            } else if (!result.success) {
                const errMsg = `Action failed (${action.type}): ${result.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        for (const cmd of commands) {
            sendOutputToServer(this.name, `⚡ ${cmd}`);
            console.log(`${this.name} → Fabric: ${cmd}`);

            const compatAction = commandToTypedAction(cmd);
            const result = compatAction
                ? await this.bridge.sendAction(compatAction)
                : await this.bridge.sendCommand(cmd);

            if (result.output) {
                const resultMsg = `${cmd} → ${result.output}`;
                this.history.add('system', resultMsg);
                sendOutputToServer(this.name, result.output);
            } else if (!result.success) {
                const errMsg = `Command failed: ${result.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        if (wantStructured && !structured && commands.length === 0 && actions.length === 0) {
            this.history.add('system', 'Invalid structured response: expected JSON object with reply/actions.');
        }

        this.history.save();
    }

    async fetchBridgeCommands() {
        try {
            const commands = await this.bridge.getCommands();
            return Array.isArray(commands) ? commands : [];
        } catch (err) {
            console.warn(`${this.name} failed to fetch bridge commands:`, err);
            return [];
        }
    }

    async clearAllMemory(preserveImportant = false) {
        const preservedMemory = preserveImportant ? this.history.memory : '';
        this.history.clear();
        this.history.memory = preservedMemory;
        await this.history.save();
        sendOutputToServer(this.name, `Memory cleared${preserveImportant ? ' (important facts preserved)' : ''}.`);
    }

    async compactMemoryNow(reason = 'manual') {
        await this.history.save();
        this.history.turns = [];
        this.history.memory = this.history.memory || '';
        await this.history.save();
        sendOutputToServer(this.name, `Memory compacted (${reason}).`);
    }

    /** Called by serverProxy on disconnect from MindServer. */
    cleanKill(msg, code = 0) {
        console.log(`${this.name} clean kill: ${msg || 'shutting down'}`);
        this.stopped = true;
        setTimeout(() => process.exit(code), 500);
    }

    /** Stub for full-state polling — returns bridge state in a compatible format. */
    async getFullState() {
        const state = await this.bridge.getState();
        if (!state || !state.connected) return null;
        const now = Date.now();
        return {
            gameplay: {
                health: state.health,
                healthMax: 20,
                hunger: state.hunger,
                hungerMax: 20,
                position: { x: state.x, y: state.y, z: state.z },
                biome: state.dimension,
                gamemode: state.gameMode,
            },
            world_model: {
                freshness_ts: now,
                confidence: 1.0,
                nearbyPlayers: state.nearby_players || [],
                nearbyEntities: state.nearby_entities || [],
            },
            inventory: {
                stacksUsed: (state.inventory || []).length,
                totalSlots: 36,
                counts: Object.fromEntries(
                    (state.inventory || []).map(i => [i.item.replace('minecraft:', ''), i.count])
                ),
                equipment: {},
            },
            action: { current: 'Baritone' },
        };
    }
}
