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
    if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
        try { JSON.parse(trimmed); return trimmed; } catch {}
    }
    const first = trimmed.indexOf('{');
    const last = trimmed.lastIndexOf('}');
    if (first >= 0 && last > first) {
        const candidate = trimmed.slice(first, last + 1);
        try { JSON.parse(candidate); return candidate; } catch {}
    }
    let endIdx = trimmed.length;
    while (endIdx > 0) {
        const close = trimmed.lastIndexOf('}', endIdx - 1);
        if (close < 0) break;
        const open = trimmed.lastIndexOf('{', close);
        if (open < 0) break;
        const candidate = trimmed.slice(open, close + 1);
        try {
            JSON.parse(candidate);
            return candidate;
        } catch {}
        endIdx = open;
    }
    return null;
}

/**
 * Extract all valid JSON objects from a text response.
 * Handles models that emit multiple separate {…} objects.
 * @param {string} text
 * @returns {Array<object>}
 */
function extractAllJsonObjects(text) {
    const results = [];
    const trimmed = String(text || '').trim();
    if (!trimmed) return results;

    // First, try extracting from a code fence
    const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
    const source = fenced?.[1] ? fenced[1].trim() : trimmed;

    // Scan character by character for balanced {…} pairs
    let depth = 0;
    let start = -1;
    for (let i = 0; i < source.length; i++) {
        const ch = source[i];
        if (ch === '{' && depth === 0) {
            start = i;
            depth = 1;
        } else if (ch === '{' && depth > 0) {
            depth++;
        } else if (ch === '}' && depth > 0) {
            depth--;
            if (depth === 0 && start >= 0) {
                const candidate = source.slice(start, i + 1);
                try {
                    const parsed = JSON.parse(candidate);
                    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                        results.push(parsed);
                    }
                } catch {
                    // skip unbalanced/broken objects
                }
                start = -1;
            }
        }
    }

    return results;
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

/**
 * Parse the LLM's response into a chat text portion and a list of actions.
 *
 * Expected format (any ordering, COMMAND lines can appear multiple times):
 *   THOUGHT: <reasoning>
 *   PLAN: <goal>
 *   COMMAND: #goto 100 64 -200
 *   COMMAND: #mine 16 iron_ore
 *
 * Lines that are not THOUGHT/PLAN/COMMAND are treated as chat text.
 * @param {string} response
 * @returns {{chat: string, commands: string[]}}
 */
export function parseBridgeResponse(response, expectStructured = false) {
    const commands = [];
    const actions = [];
    const chatLines = [];

    // ── Try extracting ALL JSON objects from the response ──────────────────
    // Small models often emit separate {"reply":"..."} and {"type":"craft",...}
    // objects.  Scan for every {…} pair and try to merge them.
    const jsonObjects = extractAllJsonObjects(response);
    if (jsonObjects.length > 0) {
        // If we found 2+ objects, try to assemble reply + actions from them.
        if (jsonObjects.length >= 2) {
            let mergedReply = '';
            const mergedActions = [];
            for (const obj of jsonObjects) {
                if (typeof obj.reply === 'string') mergedReply = obj.reply.trim();
                if (typeof obj.chat === 'string' && !mergedReply) mergedReply = obj.chat.trim();
                if (Array.isArray(obj.actions)) {
                    for (const a of obj.actions) {
                        const na = normalizeAction(a);
                        if (na) mergedActions.push(na);
                    }
                }
                // Lone action object (has type, no reply/actions)
                if (obj.type && !obj.reply && !obj.actions) {
                    const na = normalizeAction(obj);
                    if (na) mergedActions.push(na);
                }
            }
            if (mergedActions.length > 0 || mergedReply) {
                return {
                    chat: mergedReply,
                    commands,
                    actions: mergedActions,
                    structured: true,
                };
            }
        }

        // Single object — process normally
        const parsed = jsonObjects[0];
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
            // Lone action object (has type, no reply/actions array)
            if (parsed.type && !parsed.reply && !parsed.actions) {
                const na = normalizeAction(parsed);
                return {
                    chat: '',
                    commands,
                    actions: na ? [na] : [],
                    structured: true,
                };
            }
            // Normal structured response
            const reply = typeof parsed.reply === 'string'
                ? parsed.reply.trim()
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
 *  3. Calls the LLM to decide what to do.
 *  4. Parses actions from the LLM response and forwards them to the mod.
 *  5. After queue completion, re-invokes the LLM to continue multi-step plans.
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
        this.prompter.profile.conversing = buildBridgeSystemPrompt(settings, '');
        // ── History ────────────────────────────────────────────────────────────
        this.history = new History(this);

        // ── Stubs for Prompter compatibility ──────────────────────────────────
        this.self_prompter = new BridgeSelfPrompter();
        this.isBridgeAgent = true;
        this.blocked_actions = settings.blocked_actions || [];
        this.actions = { currentActionLabel: 'Idle' };
        this.npc = { constructions: null };
        this.last_sender = null;
        this.shut_up = false;

        // ── Self-continuation state ───────────────────────────────────────────
        this._lastHadActions = false;  // did the previous LLM response have actions?
        this._pendingContinuation = false; // waiting for queue to drain before continuing
        this._continuationSource = null; // original source (for continuation history)
        this._lastState = null;  // most recent state snapshot

        // ── Fabric bridge HTTP client ──────────────────────────────────────────
        this.bridge = new FabricBridge(settings.bridge_url || 'http://localhost:8765');
        this._lastStateStr = '';
        this._lastStateSeq = null;
        this._pollIntervalMs = POLL_DEFAULT_MS;
        this._capabilities = null;
        this._recentSentChats = [];

        // ── MindServer registration ────────────────────────────────────────────
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
        if (!normalized || !this._recentSentChats.length) return false;
        return this._recentSentChats.some(sent => normalized.includes(sent));
    }

    /**
     * Build a state summary string for injection into the LLM's context.
     * Includes inventory, position, health, nearby entities, and queue status.
     */
    _buildStateContext(state) {
        if (!state || !state.connected) return null;

        const inv = (state.inventory || [])
            .map(i => `${i.count}x ${i.item.replace('minecraft:', '')}`)
            .join(', ') || 'empty';
        const dim = (state.dimension || 'overworld').replace('minecraft:', '');
        const nearby = (state.nearby_players || []).join(', ') || 'none';
        const entities = (state.nearby_entities || [])
            .slice(0, 5)
            .map(e => `${e.type}@(${e.x},${e.y},${e.z})`)
            .join(', ') || 'none';

        let ctx = `CURRENT STATE:\n`;
        ctx += `Position: x=${state.x}, y=${state.y}, z=${state.z}  Dimension: ${dim}\n`;
        ctx += `Health: ${state.health}/20  Hunger: ${state.hunger}/20  Mode: ${state.gameMode || '?'}\n`;
        ctx += `Inventory: ${inv}\n`;
        ctx += `Nearby players: ${nearby}\n`;
        ctx += `Nearby entities: ${entities}`;

        // Include craftable items if available
        if (state.craftable && Array.isArray(state.craftable) && state.craftable.length > 0) {
            ctx += `\nCraftable: ${state.craftable.join(', ')}`;
        }

        return ctx;
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

                if (state && state.connected) {
                    const stateStr = FabricBridge.formatState(state);
                    this._lastStateStr = stateStr;
                    this._lastState = state;

                    // ── 2. Process chat events ────────────────────────────────
                    const events = Array.isArray(state.chat_events)
                        ? state.chat_events
                        : (Array.isArray(state.chat) ? state.chat.map(msg => ({ type: 'player', message: msg })) : []);

                    const selfName = String(state?.player_name || this.name || '').trim().toLowerCase();
                    for (const event of events) {
                        const message = String(event?.message || '');
                        if (!message) continue;

                        const eventType = (event && event.type) ? String(event.type) : 'player';

                        // Handle Baritone task queue status events
                        if (eventType === 'baritone_queue') {
                            console.log(`${this.name} queue event: ${message}`);
                            this.history.add('system', `[Baritone] ${message}`);
                            sendOutputToServer(this.name, `🔄 ${message}`);

                            // Detect queue draining → idle transition for self-continuation
                            if (this._pendingContinuation && message.includes('All queued tasks complete')) {
                                this._pendingContinuation = false;
                                // Schedule continuation after a short delay to let state settle
                                setTimeout(() => {
                                    if (!this.stopped) this._continuePlan();
                                }, 500);
                            }
                            continue;
                        }

                        // Skip non-player messages
                        if (eventType !== 'player') continue;
                        const hasSender = !!(event && event.sender);
                        if (!hasSender) {
                            sendLogToUI(`${this.name}: ignoring non-player message: ${stripChatFormatting(message).trim()}`);
                            continue;
                        }

                        // Skip self-sent messages
                        const senderField = (event && event.sender) ? String(event.sender).trim() : '';
                        if (senderField) {
                            if (senderField.toLowerCase() === selfName) {
                                continue;
                            }
                        }
                        if (this._isSelfSentChat(message)) {
                            continue;
                        }
                        const strippedLower = stripChatFormatting(message).trim().toLowerCase();
                        if (selfName && strippedLower.startsWith('<' + selfName + '>')) {
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

                    this._pollIntervalMs = POLL_MIN_MS;
                    continue;
                }

                // ── 4. Self-continuation check ─────────────────────────────────
                // If there are no pending incoming messages but a continuation was
                // triggered by queue completion, handle it.
                if (this._pendingContinuation && !this._inboundQueue.length) {
                    // Already handled in baritone_queue event above via setTimeout
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
     * Called after Baritone completes a batch of actions.
     * If the previous LLM response had actions, re-invoke the LLM with
     * updated state so it can continue the plan.
     */
    async _continuePlan() {
        if (!this._lastHadActions || !this._lastState) return;

        this._lastHadActions = false;

        // Give Baritone a moment to settle
        await new Promise(r => setTimeout(r, 800));

        // Get fresh state
        const state = await this.bridge.getState();
        if (!state || !state.connected) return;

        this._lastState = state;

        // Check if queue is truly idle now
        if (state.queue && state.queue.status !== 'idle' && state.queue.status !== 'disabled') {
            // Queue still busy — wait for next baritone_queue event
            this._pendingContinuation = true;
            return;
        }

        // Inject updated state into history
        const stateContext = this._buildStateContext(state);
        if (stateContext) {
            this.history.add('system', stateContext);
        }

        // Build prompt and call LLM (use same logic as _handleMessage but
        // with a continuation source instead of a player message)
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
            console.error('LLM error in continuation:', err);
            return;
        }

        if (!response || response.trim().length === 0) return;
        console.log(`${this.name} continuation LLM response: ${response}`);

        // Parse and dispatch
        const { chat, commands, actions } = parseBridgeResponse(response, wantStructured);
        const suppressChat = /^(no response needed|no reply|none|n\/a)$/i.test(chat.trim());
        const chatText = suppressChat ? '' : chat;

        this.history.add(this.name, response);

        if (chatText.trim()) {
            const trimmedChat = chatText.trim();
            sendOutputToServer(this.name, trimmedChat);
            if (settings.chat_ingame === true) {
                this._trackSentChat(trimmedChat);
                await this.bridge.sendCommand(`chat: ${trimmedChat}`);
            }
        }

        if (actions.length > 0) {
            const batchResult = await this.bridge.sendBatch(actions);
            if (batchResult.success) {
                this._lastHadActions = true;
                this._pendingContinuation = true;
                const queued = batchResult.queued || actions.length;
                sendOutputToServer(this.name, `⚡ Queued ${queued} action(s) for sequential execution`);
                this.history.add('system', `Queued ${queued} action(s). Queue will advance on Baritone completion signals.`);
            } else {
                const errMsg = `Batch dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        if (commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(commands);
            if (batchResult.success) {
                this._lastHadActions = true;
                this._pendingContinuation = true;
                const queued = batchResult.queued || commands.length;
                sendOutputToServer(this.name, `⚡ Queued ${queued} command(s) for sequential execution`);
                this.history.add('system', `Queued ${queued} command(s). Queue will advance on Baritone completion signals.`);
            } else {
                const errMsg = `Batch command dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        this.history.save();
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

        // ── Inject current state into history before LLM call ───────────────
        if (state && state.connected) {
            const stateContext = this._buildStateContext(state);
            if (stateContext) {
                this.history.add('system', stateContext);
            }
        }

        // ── Build prompt and call LLM ──────────────────────────────────────
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

        // ── Parse response ─────────────────────────────────────────────────
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

        // ── Dispatch actions via batch queue ───────────────────────────────
        if (actions.length > 0) {
            const batchResult = await this.bridge.sendBatch(actions);
            if (batchResult.success) {
                this._lastHadActions = true;
                this._pendingContinuation = true;
                this._continuationSource = source;
                const queued = batchResult.queued || actions.length;
                sendOutputToServer(this.name, `⚡ Queued ${queued} action(s) for sequential execution`);
                this.history.add('system', `Queued ${queued} action(s). Queue will advance on Baritone completion signals.`);
            } else {
                const errMsg = `Batch dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        // Remaining raw commands (parsed from COMMAND: lines, not typed actions)
        if (commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(commands);
            if (batchResult.success) {
                this._lastHadActions = true;
                this._pendingContinuation = true;
                const queued = batchResult.queued || commands.length;
                sendOutputToServer(this.name, `⚡ Queued ${queued} command(s) for sequential execution`);
                this.history.add('system', `Queued ${queued} command(s). Queue will advance on Baritone completion signals.`);
            } else {
                const errMsg = `Batch command dispatch failed: ${batchResult.error || 'unknown error'}`;
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