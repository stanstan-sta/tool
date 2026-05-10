import { Prompter } from '../models/prompter.js';
import { History } from '../agent/history.js';
import { FabricBridge } from './fabric_bridge.js';
import { buildBridgeSystemPrompt } from './bridge_prompt.js';
import { buildBridgeTopographySystemMessage } from './topography.js';
import { serverProxy, sendOutputToServer, sendLogToUI } from '../agent/mindserver_proxy.js';
import { wiki } from '../utils/MinecraftWiki.js';
import settings from '../agent/settings.js';
import { EventDetector } from './event_detector.js';
import { DriveModel } from './drive_model.js';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'fs';

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

function normalizeItemName(name) {
    return String(name || '')
        .replace(/^minecraft:/i, '')
        .toLowerCase()
        .replace(/[^a-z0-9_ ]+/g, ' ')
        .replace(/\s+/g, '_')
        .replace(/^_+|_+$/g, '');
}

function countInventoryItems(inventory = []) {
    const counts = new Map();
    for (const stack of inventory || []) {
        if (!stack || !stack.item) continue;
        const name = normalizeItemName(stack.item);
        counts.set(name, (counts.get(name) || 0) + (Number(stack.count) || 0));
    }
    return counts;
}

function getAnyPlankCount(counts) {
    let total = 0;
    for (const [item, count] of counts.entries()) {
        if (item.endsWith('_planks')) total += count;
    }
    return total;
}

function getAnyLogCount(counts) {
    let total = 0;
    for (const [item, count] of counts.entries()) {
        if (item.endsWith('_log') || item.endsWith('_wood')) total += count;
    }
    return total;
}

const NETHER_BLOCKS = new Set([
    'ancient_debris', 'netherrack', 'nether_quartz_ore', 'glowstone', 'soul_sand', 'soul_soil',
    'magma_block', 'nether_gold_ore', 'blackstone', 'basalt', 'smooth_basalt',
    'crimson_nylium', 'warped_nylium', 'crimson_stem', 'warped_stem',
    'crimson_hyphae', 'warped_hyphae', 'crimson_planks', 'warped_planks',
    'nether_bricks', 'red_nether_bricks', 'cracked_nether_bricks', 'chiseled_nether_bricks',
    'nether_brick_fence', 'nether_brick_slab', 'nether_brick_stairs', 'nether_brick_wall',
    'red_nether_brick_slab', 'red_nether_brick_stairs', 'red_nether_brick_wall',
    'quartz_block', 'chiseled_quartz_block', 'quartz_pillar', 'quartz_bricks',
    'quartz_slab', 'quartz_stairs', 'smooth_quartz', 'smooth_quartz_slab', 'smooth_quartz_stairs',
    'shroomlight', 'weeping_vines', 'twisting_vines', 'nether_sprouts', 'crimson_roots', 'warped_roots',
    'crimson_fungus', 'warped_fungus', 'soul_fire', 'soul_torch', 'soul_lantern', 'soul_campfire',
    'netherite_block', 'nether_wart_block', 'warped_wart_block',
    'gilded_blackstone', 'polished_blackstone', 'polished_blackstone_bricks',
    'cracked_polished_blackstone_bricks', 'chiseled_polished_blackstone',
    'polished_blackstone_slab', 'polished_blackstone_stairs', 'polished_blackstone_wall',
    'polished_blackstone_brick_slab', 'polished_blackstone_brick_stairs', 'polished_blackstone_brick_wall',
    'polished_blackstone_button', 'polished_blackstone_pressure_plate',
]);

const MINEABLE_BLOCK_MAP = {
    'diamond': 'diamond_ore',
    'emerald': 'emerald_ore',
    'redstone': 'redstone_ore',
    'lapis_lazuli': 'lapis_ore',
    'coal': 'coal_ore',
    'iron_ingot': 'iron_ore',
    'gold_ingot': 'gold_ore',
    'copper_ingot': 'copper_ore',
    'netherite_scrap': 'ancient_debris',
    'raw_iron': 'iron_ore',
    'raw_gold': 'gold_ore',
    'raw_copper': 'copper_ore',
    'coal': 'coal_ore',
    'cobblestone': 'cobblestone',
    'stone': 'stone',
    'netherrack': 'netherrack',
    'glowstone': 'glowstone',
    'soul_sand': 'soul_sand',
    'magma_block': 'magma_block',
    'nether_quartz': 'nether_quartz_ore',
    'quartz': 'nether_quartz_ore',
    'ancient_debris': 'ancient_debris',
    'obsidian': 'obsidian',
    'blackstone': 'blackstone',
    'basalt': 'basalt',
    'crimson_stem': 'crimson_stem',
    'warped_stem': 'warped_stem',
    'nether_gold_ore': 'nether_gold_ore',
};

const END_BLOCKS = new Set([
    'end_stone', 'end_stone_bricks', 'end_stone_brick_slab', 'end_stone_brick_stairs', 'end_stone_brick_wall',
    'purpur_block', 'purpur_pillar', 'purpur_slab', 'purpur_stairs',
    'chorus_plant', 'chorus_flower', 'chorus_fruit', 'popped_chorus_fruit',
    'end_rod', 'dragon_head', 'dragon_egg',
    'shulker_shell', 'elytra',
]);

function getItemDimension(itemName) {
    const name = normalizeItemName(itemName);
    if (NETHER_BLOCKS.has(name)) return 'nether';
    if (END_BLOCKS.has(name)) return 'end';
    return 'overworld';
}

function findRequestedCraftItem(message) {
    const lower = String(message || '').toLowerCase();
    if (!/\b(make|craft|get|create|build)\b/.test(lower)) return null;
    const normalizedMessage = normalizeItemName(lower);
    const recipes = wiki.data?.recipes?.crafting || {};
    const candidates = Object.keys(recipes)
        .filter(item => normalizedMessage.includes(normalizeItemName(item)))
        .sort((a, b) => b.length - a.length);
    if (candidates[0]) return candidates[0];
    // Also check smelting recipes that are crafted (e.g. netherite_ingot)
    const smelting = wiki.data?.recipes?.smelting || {};
    const smeltCandidates = Object.keys(smelting)
        .filter(item => {
            const r = smelting[item];
            return r.method === 'crafting_table' && normalizedMessage.includes(normalizeItemName(item));
        })
        .sort((a, b) => b.length - a.length);
    return smeltCandidates[0] || null;
}

function inferActiveTaskDecision(message) {
    const text = String(message || '').toLowerCase();
    if (/\b(stop|cancel|instead|rather|nevermind|never mind|come here|come back|follow me|change|switch)\b/.test(text)) {
        return 'cancel_replace';
    }
    return 'append_after_current';
}

export function isActiveQueueState(state) {
    const status = String(state?.queue?.status || '').toLowerCase();
    return status === 'executing' || status === 'draining';
}

export function parseActiveTaskDecision(response, message = '') {
    const json = extractJsonObjectCandidate(response);
    if (!json) {
        return { valid: false, decision: 'continue', reply: '', actions: [], commands: [] };
    }

    try {
        const parsed = JSON.parse(json);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            return { valid: false, decision: 'continue', reply: '', actions: [], commands: [] };
        }

        let decision = String(parsed.decision || '').trim();
        const allowed = new Set(['continue', 'cancel_replace', 'append_after_current']);
        const actions = Array.isArray(parsed.actions) ? parsed.actions.map(normalizeAction).filter(Boolean) : [];
        const commands = Array.isArray(parsed.commands)
            ? parsed.commands.map(cmd => String(cmd || '').trim()).filter(Boolean)
            : [];

        if (!allowed.has(decision) && !decision && (actions.length > 0 || commands.length > 0)) {
            decision = inferActiveTaskDecision(message);
        }

        if (!allowed.has(decision)) {
            return { valid: false, decision: 'continue', reply: '', actions: [], commands: [] };
        }

        return {
            valid: true,
            decision,
            reply: typeof parsed.reply === 'string' ? parsed.reply.trim() : '',
            actions,
            commands,
        };
    } catch {
        return { valid: false, decision: 'continue', reply: '', actions: [], commands: [] };
    }
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

        // ── Proactive behavior layer ───────────────────────────────────────────
        this.eventDetector = new EventDetector();
        this.driveModel = new DriveModel();
        this.episodicMemory = {
            lastSpokeAt: 0,
            lastSpokeTopic: '',
            lastSatisfiedDrive: '',
            lastEventReacted: '',
            lastPlayerChatAnsweredAt: 0,
        };
        this._nextAmbientTickAt = 0;
        this._ambientBucketCredits = settings.bridge_ambient_budget_per_hour || 4;
        this._ambientLastRefillMs = Date.now();
        this._ambientLogPath = `./bots/${this.name}/ambient.log`;
        mkdirSync(`./bots/${this.name}`, { recursive: true });

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
            const loadedData = this.history.load();
            if (loadedData && loadedData.episodic) {
                this.episodicMemory = { ...this.episodicMemory, ...loadedData.episodic };
            }
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

        // Log proactive mode
        const proactive = settings.bridge_proactive_enabled !== false;
        const ambient = settings.bridge_ambient_enabled !== false;
        const events = settings.bridge_events_enabled !== false;
        if (proactive) {
            sendLogToUI(`${this.name}: proactive=on, ambient=${ambient ? 'on' : 'off'}, events=${events ? 'on' : 'off'}`);
        } else {
            sendLogToUI(`${this.name}: proactive=OFF (turn-based only)`);
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
     * Includes inventory, position, health, nearby entities, queue status,
     * and a COMPLETE crafting analysis based on wiki recipe validation.
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

        // ── Crafting analysis based on wiki recipe validation ─────────────
        ctx += this._buildCraftingAnalysis(state.inventory);

        return ctx;
    }

    /**
     * Analyze the player's inventory against all wiki crafting recipes
     * and produce a structured summary of what can be crafted now, and
     * what is close to being craftable (missing 1-2 ingredient types).
     * @param {Array<{slot:number,item:string,count:number}>} inventory
     * @returns {string}
     */
    _buildCraftingAnalysis(inventory) {
        if (!inventory || inventory.length === 0) return '\n\nCRAFTING ANALYSIS:\n  (empty inventory — nothing craftable)';

        const craftingRecipes = wiki.data?.recipes?.crafting || {};
        const smeltingRecipes = wiki.data?.recipes?.smelting || {};
        const fuelItems = Object.keys(wiki.data?.categories?.fuel?.items || {});
        const plankItems = [
            'oak_planks', 'spruce_planks', 'birch_planks', 'jungle_planks',
            'acacia_planks', 'dark_oak_planks', 'mangrove_planks', 'cherry_planks',
            'bamboo_planks',
        ];
        const equivalentCount = (counts, ingredient) => {
            const key = String(ingredient || '').toLowerCase();
            if (key === 'oak_planks') {
                return plankItems.reduce((sum, item) => sum + (counts.get(item) || 0), 0);
            }
            return counts.get(key) || 0;
        };
        const missingIngredients = (counts, recipe) => {
            const missing = [];
            for (const [ingredient, qtyNeeded] of Object.entries(recipe.ingredients || {})) {
                const qtyOwned = equivalentCount(counts, ingredient);
                if (qtyOwned < qtyNeeded) {
                    missing.push({ ingredient, qtyNeeded, qtyOwned });
                }
            }
            return missing;
        };
        const hasIngredients = (counts, recipe) => missingIngredients(counts, recipe).length === 0;

        // Build effective item counts (what we own + what we can craft from what we own)
        const itemCounts = new Map();
        for (const stack of inventory) {
            if (!stack || !stack.item) continue;
            const name = stack.item.replace('minecraft:', '').toLowerCase();
            itemCounts.set(name, (itemCounts.get(name) || 0) + stack.count);
        }
        const directCounts = new Map(itemCounts);
        const directCraftable = new Set();
        for (const [outputItem, recipe] of Object.entries(craftingRecipes)) {
            if (!recipe || !recipe.ingredients) continue;
            if (hasIngredients(directCounts, recipe)) {
                directCraftable.add(outputItem);
            }
        }

        // Resolve transitive crafting: repeatedly check which intermediates we can
        // craft from current effective counts, add them, and re-test. Stop when no
        // new items are discovered (max 5 passes to prevent infinite loops).
        const knownCraftable = new Set();
        for (let pass = 0; pass < 5; pass++) {
            let added = false;
            for (const [outputItem, recipe] of Object.entries(craftingRecipes)) {
                if (!recipe || !recipe.ingredients) continue;
                if (knownCraftable.has(outputItem)) continue;

                if (hasIngredients(itemCounts, recipe)) {
                    // Add this output to effective counts (assume we craft at least 1 batch)
                    const outputQty = recipe.output || 1;
                    itemCounts.set(outputItem.toLowerCase(), (itemCounts.get(outputItem.toLowerCase()) || 0) + outputQty);
                    knownCraftable.add(outputItem);
                    added = true;
                }
            }
            if (!added) break;
        }

        // Direct items can be sent as one craft action. Transitive items need
        // prerequisite craft actions queued first.
        const directlyCraftable = [];
        const craftableAfterPrereqs = [];
        const nearlyCraftable = [];

        for (const [outputItem, recipe] of Object.entries(craftingRecipes)) {
            if (!recipe || !recipe.ingredients) continue;

            const directMissing = missingIngredients(directCounts, recipe);
            const transitiveMissing = missingIngredients(itemCounts, recipe);

            if (directMissing.length === 0) {
                directlyCraftable.push(outputItem);
            } else if (transitiveMissing.length === 0) {
                const prereqs = directMissing
                    .flatMap(m => {
                        const ingredient = String(m.ingredient || '').toLowerCase();
                        if (ingredient === 'oak_planks') {
                            return [...directCraftable].filter(item => item.endsWith('_planks'));
                        }
                        return knownCraftable.has(ingredient) ? [ingredient] : [];
                    })
                    .filter((item, idx, arr) => item && arr.indexOf(item) === idx);
                craftableAfterPrereqs.push(prereqs.length > 0
                    ? `${outputItem} [queue first: ${prereqs.join(', ')}]`
                    : `${outputItem} [queue prerequisite crafts first]`);
            } else if (transitiveMissing.length <= 2 && transitiveMissing.length > 0) {
                nearlyCraftable.push(`${outputItem} [missing: ${transitiveMissing.map(m =>
                    `${m.ingredient} (need ${m.qtyNeeded}, have ${m.qtyOwned})`
                ).join(', ')}]`);
            }
        }

        // Also check smelting recipes (non-transitive for simplicity)
        for (const [outputItem, recipe] of Object.entries(smeltingRecipes)) {
            if (!recipe || !recipe.input) continue;
            const inputs = recipe.input.split('+').map(s => s.trim().toLowerCase());
            const hasAllInputs = inputs.every(inp =>
                inp === 'any_fuel' ||
                inp === 'any_log' ||
                equivalentCount(directCounts, inp) > 0
            );
            if (hasAllInputs) {
                const hasFuel = fuelItems.some(f => directCounts.has(f.toLowerCase()));
                if (hasFuel) {
                    directlyCraftable.push(`${outputItem} (furnace)`);
                } else {
                    nearlyCraftable.push(`${outputItem} (furnace) [missing: fuel]`);
                }
            } else {
                const needed = inputs.filter(inp =>
                    inp !== 'any_fuel' &&
                    inp !== 'any_log' &&
                    equivalentCount(directCounts, inp) <= 0
                );
                if (needed.length <= 2 && needed.length > 0) {
                    nearlyCraftable.push(`${outputItem} (furnace) [missing: ${needed.join(', ')}]`);
                }
            }
        }

        let analysis = '\n\nCRAFTING ANALYSIS:';
        analysis += '\n  Bridge auto-expands stick crafts: if logs are available, craft stick directly and the bridge will craft matching planks first.';
        if (directlyCraftable.length > 0) {
            analysis += `\n  Directly craftable now: ${directlyCraftable.join(', ')}`;
        } else {
            analysis += '\n  Directly craftable now: (nothing - gather more materials first)';
        }
        if (craftableAfterPrereqs.length > 0) {
            analysis += `\n  Craftable after prerequisites: ${craftableAfterPrereqs.join('; ')}`;
        }
        if (nearlyCraftable.length > 0) {
            analysis += `\n  Nearly craftable (missing 1-2 items): ${nearlyCraftable.join('; ')}`;
        }

        return analysis;
    }

    _buildCraftFallbackActions(message, state, chatText, options = {}) {
        const complaint = /\b(need|missing|don't have|do not have|can't|cannot|lack|requires|required)\b/i.test(chatText || '');
        if (!complaint && options.force !== true) return [];

        const item = findRequestedCraftItem(message);
        if (!item) return [];

        // Check crafting recipes first, then smelting recipes with method crafting_table
        let recipe = wiki.data?.recipes?.crafting?.[item];
        if (!recipe?.ingredients) {
            const smelt = wiki.data?.recipes?.smelting?.[item];
            if (smelt?.method === 'crafting_table' && smelt.input) {
                // Convert smithing-table-style recipe to ingredients map
                const parts = smelt.input.split('+').map(s => s.trim());
                const ingredients = {};
                // Try to parse quantities from notes (e.g. "4 netherite_scrap + 4 gold_ingot")
                const notes = smelt.notes || '';
                for (const p of parts) {
                    const qtyMatch = notes.match(new RegExp(`(\\d+)\\s*${p.replace(/_/g, '[_ ]')}`));
                    ingredients[p] = qtyMatch ? Number(qtyMatch[1]) : 1;
                }
                recipe = { ingredients };
            }
        }
        if (!recipe?.ingredients) return [];

        const counts = countInventoryItems(state?.inventory || []);
        const currentDim = (state?.dimension || 'minecraft:overworld').replace('minecraft:', '');
        const actions = [];
        const plannedCrafts = new Set();
        const plannedMines = new Set();
        const plannedSmelts = new Set();

        const addCraft = (craftItem, count = 1) => {
            const key = `${craftItem}:${count}`;
            if (plannedCrafts.has(key)) return;
            plannedCrafts.add(key);
            actions.push({ type: 'craft', provider: 'baritone_chat', item: craftItem, count });
        };

        const addMine = (target, count) => {
            const key = `${target}:${count}`;
            if (plannedMines.has(key)) return;
            plannedMines.add(key);
            actions.push({ type: 'mine', provider: 'baritone_chat', target, count });
        };

        const addSmelt = (target) => {
            if (plannedSmelts.has(target)) return;
            plannedSmelts.add(target);
            actions.push({ type: 'raw_command', provider: 'baritone_chat', command: `#task smelt ${target}` });
        };

        const have = (ingredient) => {
            const name = normalizeItemName(ingredient);
            if (name === 'oak_planks') return getAnyPlankCount(counts);
            return counts.get(name) || 0;
        };

        const resolveIngredient = (name, qtyNeeded, depth = 0) => {
            if (depth > 3) return false; // prevent infinite recursion
            let stillNeed = Math.max(0, qtyNeeded - have(name));
            if (stillNeed <= 0) return true;

            // Special: sticks → craft from planks (handled by bridge mod)
            if (name === 'stick') {
                const current = counts.get('stick') || 0;
                if (current < qtyNeeded) {
                    const planksAvailable = getAnyPlankCount(counts);
                    const logsAvailable = getAnyLogCount(counts);
                    if (planksAvailable <= 0 && logsAvailable <= 0) {
                        addMine('wood', 1);
                    }
                }
                return true;
            }

            // Special: any planks → mine wood if none available
            if (name.endsWith('_planks')) {
                if (getAnyPlankCount(counts) < qtyNeeded && getAnyLogCount(counts) <= 0) {
                    addMine('wood', 1);
                }
                addCraft(name, stillNeed);
                return true;
            }

            // Is this item produced by smelting something?
            const smeltRecipe = wiki.data?.recipes?.smelting?.[name];
            if (smeltRecipe?.input && smeltRecipe.method === 'furnace') {
                const smeltInputRaw = normalizeItemName(smeltRecipe.input);
                const smeltInputBlock = MINEABLE_BLOCK_MAP[smeltInputRaw] || smeltInputRaw;

                // Count everything we have that can become this item
                const haveOutput = counts.get(name) || 0;
                const haveRaw = counts.get(smeltInputRaw) || 0;
                const haveOre = counts.get(smeltInputBlock) || 0;
                const haveCombined = haveOutput + haveRaw + haveOre;

                stillNeed = Math.max(0, qtyNeeded - haveCombined);

                // Only smelt if we're short on the finished output
                const outputMissing = Math.max(0, qtyNeeded - haveOutput);
                if (outputMissing > 0) {
                    addSmelt(smeltInputRaw);
                }

                if (stillNeed > 0) {
                    addMine(smeltInputBlock, stillNeed);
                }
                return true;
            }

            // Not a smelting output, mine directly
            const mineTarget = MINEABLE_BLOCK_MAP[name] || name;
            addMine(mineTarget, stillNeed);
            return true;
        };

        // Resolve all ingredients
        for (const [ingredient, rawQty] of Object.entries(recipe.ingredients)) {
            const name = normalizeItemName(ingredient);
            const qtyNeeded = Number(rawQty) || 1;
            if (!resolveIngredient(name, qtyNeeded)) return [];
        }

        // Separate action types so we can order them correctly:
        // portal → mines → portal return → smelts → crafts
        const mines = actions.filter(a => a.type === 'mine');
        const smelts = actions.filter(a => a.type === 'raw_command' && a.command?.startsWith('#task smelt'));
        const crafts = actions.filter(a => a.type === 'craft');

        const ordered = [];
        const nonOverworldMines = mines.filter(a => getItemDimension(a.target) !== 'overworld');

        if (nonOverworldMines.length > 0) {
            if (currentDim === 'overworld') {
                ordered.push({ type: 'raw_command', provider: 'baritone_chat', command: '#goto nether_portal' });
            }
            ordered.push(...mines);
            ordered.push({ type: 'raw_command', provider: 'baritone_chat', command: '#goto nether_portal' });
        } else {
            ordered.push(...mines);
        }
        ordered.push(...smelts);
        ordered.push(...crafts);

        ordered.push({ type: 'craft', provider: 'baritone_chat', item, count: 1 });
        return ordered;
    }

    _buildActiveTaskCraftActions(message, state) {
        const actions = this._buildCraftFallbackActions(message, state, 'missing materials', { force: true });
        if (actions.length > 0) return actions;

        const item = findRequestedCraftItem(message);
        if (!item) return [];
        return [{ type: 'craft', provider: 'baritone_chat', item, count: 1 }];
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

                            // Detect task failure → skip the failed task and auto-recover
                            if (message.includes('Task failed:')) {
                                const reason = message.substring(message.indexOf('Task failed:') + 'Task failed:'.length).trim();
                                console.log(`${this.name} task failed: ${reason}`);
                                sendOutputToServer(this.name, `⚠️ Task failed: ${reason}`);

                                // Clear the failed batch. Later actions often depend on the
                                // failed one, so continuing stale pending work is unsafe.
                                const cancelResult = await this.bridge.cancelQueue();
                                if (cancelResult.success) {
                                    console.log(`${this.name} cleared failed queue, replanning`);
                                    sendOutputToServer(this.name, `Cleared failed queue`);
                                } else {
                                    console.warn(`${this.name} failed to clear queue: ${cancelResult.error}`);
                                }

                                // Reset continuation state — the failure broke our plan
                                this._pendingContinuation = false;
                                this._lastHadActions = false;

                                // Schedule an LLM re-invoke so the bot can adapt
                                // (e.g., craft planks before trying sticks again)
                                setTimeout(() => {
                                    if (!this.stopped) this._handleFailureRecovery(reason, state);
                                }, 800);
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
                    if (isActiveQueueState(state)) {
                        await this._handleActiveTaskMessage(source, message, state);
                    } else {
                        await this._handleMessage(source, message, state);
                    }

                    this._pollIntervalMs = POLL_MIN_MS;
                    continue;
                }

                // ── 4. World event detection ───────────────────────────────────
                if (settings.bridge_proactive_enabled !== false && settings.bridge_events_enabled !== false) {
                    const worldEvents = this.eventDetector.check(state);
                    for (const ev of worldEvents) {
                        await this._handleEvent(ev, state);
                    }
                }

                // ── 5. Ambient tick ────────────────────────────────────────────
                if (settings.bridge_proactive_enabled !== false && settings.bridge_ambient_enabled !== false) {
                    await this._runAmbientTick(state);
                }

                // ── 6. Self-continuation check ─────────────────────────────────
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
     * Called when a queued task fails. Skips the failed task, gets fresh state,
     * and re-invokes the LLM so it can adapt its plan (e.g., craft prerequisites first).
     * @param {string} reason - the failure reason from Baritone
     * @param {object} state - the state snapshot at the time of failure
     */
    _recordQueueDispatch(label, batchResult, fallbackCount) {
        const queued = batchResult?.queued ?? fallbackCount;
        if (queued > 0) {
            this._lastHadActions = true;
            this._pendingContinuation = true;
            sendOutputToServer(this.name, `⚡ Queued ${queued} ${label}(s) for sequential execution`);
            this.history.add('system', `Queued ${queued} ${label}(s). Queue will advance according to each entry completion policy.`);
            return queued;
        }

        this._lastHadActions = false;
        this._pendingContinuation = false;
        const detail = batchResult?.output ? ` (${batchResult.output})` : '';
        sendOutputToServer(this.name, `⚠️ Queued 0 ${label}(s)${detail}`);
        this.history.add('system', `Queued 0 ${label}(s)${detail}. Nothing is running; replan from current state.`);
        return 0;
    }

    async _handleFailureRecovery(reason, state) {
        // Get fresh state after clearing the failed batch
        const freshState = await this.bridge.getState();
        if (!freshState || !freshState.connected) return;

        this._lastState = freshState;

        // Inject updated state + failure context into history
        const stateContext = this._buildStateContext(freshState);
        if (stateContext) {
            this.history.add('system', stateContext);
        }
        this.history.add('system', `Previous action failed: ${reason}. The failed batch has been cleared. You should try an alternative approach or check what went wrong.`);

        // Build prompt and re-invoke LLM
        const history = this.history.getHistory();
        if (settings.use_textual_topography === true && freshState?.surface_map) {
            history.push({
                role: 'system',
                content: buildBridgeTopographySystemMessage(freshState.surface_map, {
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
            console.error('LLM error in failure recovery:', err);
            return;
        }

        if (!response || response.trim().length === 0) return;
        console.log(`${this.name} failure recovery LLM response: ${response}`);

        const { chat, commands, actions } = parseBridgeResponse(response, wantStructured);
        const chatText = /^(no response needed|no reply|none|n\/a)$/i.test(chat.trim()) ? '' : chat;

        this.history.add(this.name, response);

        if (chatText.trim()) {
            sendOutputToServer(this.name, chatText.trim());
            if (settings.chat_ingame === true) {
                this._trackSentChat(chatText.trim());
                await this.bridge.sendCommand(`chat: ${chatText.trim()}`);
            }
        }

        if (actions.length > 0) {
            const batchResult = await this.bridge.sendBatch(actions);
            if (batchResult.success) {
                this._recordQueueDispatch('action', batchResult, actions.length);
            } else {
                this.history.add('system', `Batch dispatch failed: ${batchResult.error || 'unknown error'}`);
                sendOutputToServer(this.name, `⚠️ Batch dispatch failed: ${batchResult.error || 'unknown error'}`);
            }
        }

        if (commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(commands);
            if (batchResult.success) {
                this._recordQueueDispatch('command', batchResult, commands.length);
            } else {
                this.history.add('system', `Batch command dispatch failed: ${batchResult.error || 'unknown error'}`);
                sendOutputToServer(this.name, `⚠️ Batch command dispatch failed: ${batchResult.error || 'unknown error'}`);
            }
        }

        this.history.save();
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
                this._recordQueueDispatch('action', batchResult, actions.length);
            } else {
                const errMsg = `Batch dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        if (commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(commands);
            if (batchResult.success) {
                this._recordQueueDispatch('command', batchResult, commands.length);
            } else {
                const errMsg = `Batch command dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `⚠️ ${errMsg}`);
            }
        }

        this.history.save();
    }

    async _handleMessage(source, message, state) {
        if (!source || !message) return;

        // Add the triggering message to history.
        if (source !== this.name) {
            this.history.add(source, message);
        }

        // Inject current state into history before the LLM call.
        if (state && state.connected) {
            const stateContext = this._buildStateContext(state);
            if (stateContext) {
                this.history.add('system', stateContext);
            }
        }

        // Build prompt and call the LLM.
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

        // Parse the model response into chat and bridge work.
        const { chat: rawChat, commands, actions, structured } = parseBridgeResponse(response, wantStructured);
        const suppressChat = /^(no response needed|no reply|none|n\/a)$/i.test(rawChat.trim());
        let chat = suppressChat ? '' : rawChat;
        let dispatchActions = actions;

        if (dispatchActions.length === 0 && commands.length === 0 && state?.connected) {
            const fallbackActions = this._buildCraftFallbackActions(message, state, chat);
            if (fallbackActions.length > 0) {
                dispatchActions = fallbackActions;
                chat = 'I will gather the missing materials and craft it.';
                this.history.add('system', `Converted missing-materials reply into ${fallbackActions.length} gather/craft action(s).`);
            }
        }

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

        // Dispatch actions via the batch queue.
        if (dispatchActions.length > 0) {
            const batchResult = await this.bridge.sendBatch(dispatchActions);
            if (batchResult.success) {
                this._continuationSource = source;
                this._recordQueueDispatch('action', batchResult, dispatchActions.length);
            } else {
                const errMsg = `Batch dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `WARNING: ${errMsg}`);
            }
        }

        // Remaining raw commands (parsed from COMMAND: lines, not typed actions)
        if (commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(commands);
            if (batchResult.success) {
                this._recordQueueDispatch('command', batchResult, commands.length);
            } else {
                const errMsg = `Batch command dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `WARNING: ${errMsg}`);
            }
        }

        if (wantStructured && !structured && commands.length === 0 && dispatchActions.length === 0) {
            this.history.add('system', 'Invalid structured response: expected JSON object with reply/actions.');
        }

        this.episodicMemory.lastPlayerChatAnsweredAt = Date.now();
        // Extract topic/satisfied_drive from structured response if present
        let topic = '';
        let satisfiedDrive = '';
        try {
            const json = extractJsonObjectCandidate(response);
            if (json) {
                const parsed = JSON.parse(json);
                if (parsed.topic) topic = String(parsed.topic);
                if (parsed.satisfied_drive) satisfiedDrive = String(parsed.satisfied_drive);
            }
        } catch {}
        if (chat.trim() || dispatchActions.length > 0 || commands.length > 0) {
            this._updateEpisodicMemory(response, topic, satisfiedDrive || 'social');
        }

        this.history.save();
    }

    /**
     * Handle chat while the bridge queue is busy. The evaluator may continue,
     * cancel and replace, or append work behind the current queue.
     */
    async _handleActiveTaskMessage(source, message, state) {
        if (!source || !message) return;

        if (source !== this.name) {
            this.history.add(source, message);
        }

        const queue = state?.queue || {};
        const stateContext = this._buildStateContext(state) || 'CURRENT STATE: unavailable';
        const evaluatorPrompt = [
            'You are evaluating player chat while the bridge agent is already executing a queued task.',
            'Do what the user asks, but decide whether that means continue, cancel/replace, or append after current.',
            'Casual chat, encouragement, status questions, or unrelated comments should usually be "continue" with no actions.',
            'If the player asks to stop, cancel, change target, come back, follow them, or do something instead, use "cancel_replace".',
            'If the player asks to do something after the current task, use "append_after_current".',
            'If the player asks to make, craft, get, create, or build an item, include the needed craft/gather actions. Do not answer with chat only.',
            'For craft requests during an active task, prefer "append_after_current" unless the player clearly says instead/change/stop.',
            '',
            'Return exactly one JSON object and no other text:',
            '{"decision":"continue|cancel_replace|append_after_current","reply":"<short optional chat>","actions":[],"commands":[]}',
            '',
            'Action schema examples:',
            '{"type":"move","provider":"baritone_chat","x":1,"y":64,"z":1}',
            '{"type":"follow","provider":"baritone_chat","target":"player_name"}',
            '{"type":"craft","provider":"baritone_chat","item":"stone_pickaxe","count":1}',
            '{"type":"raw_command","provider":"baritone_chat","command":"#sleep"}',
            '',
            `Queue status: ${queue.status || 'unknown'}`,
            `Active command: ${queue.active || 'none'}`,
            `Pending count: ${queue.pending ?? 0}`,
            `Last failure: ${queue.lastFailure || 'none'}`,
            '',
            stateContext,
            '',
            `New message from ${source}: ${message}`,
        ].join('\n');

        let response;
        try {
            response = await this.prompter.promptConvo([
                { role: 'system', content: evaluatorPrompt },
            ]);
        } catch (err) {
            console.error('LLM error in active-task evaluator:', err);
            this.history.add('system', `Active-task evaluator failed: ${err.message}`);
            return;
        }

        const decision = parseActiveTaskDecision(response, message);
        console.log(`${this.name} active-task evaluator response: ${response}`);
        this.history.add('system', `Active-task evaluator response: ${response || '(empty)'}`);

        if (!decision.valid) {
            this.history.add('system', 'Invalid active-task evaluator response. Defaulted to continue with no queue change.');
            this.history.save();
            return;
        }

        if (decision.decision === 'continue'
                && decision.actions.length === 0
                && decision.commands.length === 0
                && findRequestedCraftItem(message)) {
            const craftActions = this._buildActiveTaskCraftActions(message, state);
            if (craftActions.length > 0) {
                decision.decision = inferActiveTaskDecision(message);
                decision.actions = craftActions;
                if (!decision.reply) {
                    decision.reply = decision.decision === 'cancel_replace'
                        ? 'Okay, switching to that.'
                        : 'Okay, I will queue that after this.';
                }
                this.history.add('system', `Converted active craft request into ${craftActions.length} queued action(s).`);
            }
        }

        if (decision.reply.trim()) {
            const reply = decision.reply.trim();
            sendOutputToServer(this.name, reply);
            if (settings.chat_ingame === true) {
                this._trackSentChat(reply);
                await this.bridge.sendCommand(`chat: ${reply}`);
            }
        }

        if (decision.decision === 'continue') {
            this.history.save();
            return;
        }

        if (decision.decision === 'cancel_replace') {
            const cancelResult = await this.bridge.cancelQueue();
            if (!cancelResult.success) {
                const errMsg = `Active-task cancel failed: ${cancelResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `WARNING: ${errMsg}`);
                this.history.save();
                return;
            }

            this._pendingContinuation = false;
            this._lastHadActions = false;
            this.history.add('system', `Interrupted active queue due to player request: ${message}`);
            sendOutputToServer(this.name, 'Interrupted active task');
        }

        if (decision.actions.length > 0) {
            const batchResult = await this.bridge.sendBatch(decision.actions);
            if (batchResult.success) {
                this._continuationSource = source;
                this._recordQueueDispatch('action', batchResult, decision.actions.length);
            } else {
                const errMsg = `Active-task action dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `WARNING: ${errMsg}`);
            }
        }

        if (decision.commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(decision.commands);
            if (batchResult.success) {
                this._recordQueueDispatch('command', batchResult, decision.commands.length);
            } else {
                const errMsg = `Active-task command dispatch failed: ${batchResult.error || 'unknown error'}`;
                this.history.add('system', errMsg);
                sendOutputToServer(this.name, `WARNING: ${errMsg}`);
            }
        }

        if (decision.decision !== 'continue'
                && decision.actions.length === 0
                && decision.commands.length === 0) {
            this.history.add('system', `Active-task decision ${decision.decision} returned no actions or commands.`);
        }

        this.episodicMemory.lastPlayerChatAnsweredAt = Date.now();
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

    // ── Proactive behavior methods ───────────────────────────────────────────

    _formatEventPrompt(event, state) {
        const dim = (state.dimension || 'overworld').replace('minecraft:', '').toUpperCase();
        const pos = `x=${state.x}, y=${state.y}, z=${state.z}`;
        const tag = `[${dim}] `;
        switch (event.type) {
            case 'night_start':
                return `${tag}It just turned to night in Minecraft. You're at ${pos}. Hostile mobs can spawn now. React naturally: sleep if you can, comment on the darkness, mine deeper, or say nothing.`;
            case 'sunrise':
                return `${tag}The sun is rising. Morning has come at ${pos}.`;
            case 'weather_start_rain':
                return `${tag}It started raining.`;
            case 'weather_end_rain':
                return `${tag}The rain stopped.`;
            case 'weather_start_thunder':
                return `${tag}A thunderstorm started.`;
            case 'hostile_entered_range':
                return `${tag}Hostile mobs are nearby (${event.detail} seen). You're at ${pos}. The bot is fleeing automatically if unarmed or low HP. Narrate what you do or stay silent.`;
            case 'low_hp':
                return `${tag}Your health is low (${event.detail}). Consider eating, retreating, or asking for help.`;
            case 'low_food':
                return `${tag}Your hunger is low (${event.detail}). You should eat soon.`;
            case 'queue_complete':
                return `${tag}Your queued task just finished: ${event.detail}.`;
            case 'queue_failed':
                return `${tag}A queued task failed: ${event.detail}.`;
            case 'new_player_nearby':
                return `${tag}A player named "${event.detail}" is now nearby.`;
            case 'player_left_nearby':
                return `${tag}Player "${event.detail}" left the area.`;
            case 'player_idle':
                return `${tag}The player has been quiet for a while.`;
            case 'entered_nether':
                return `${tag}You just entered the Nether. Piglins are hostile without gold armor. Beds explode. No water.`;
            case 'entered_overworld':
                return `${tag}You returned to the Overworld.`;
            case 'entered_end':
                return `${tag}You entered the End.`;
            default:
                return `${tag}World event: ${event.type}${event.detail ? ' — ' + event.detail : ''}.`;
        }
    }

    async _handleEvent(event, state) {
        if (!state || !state.connected) return;
        this.episodicMemory.lastEventReacted = event.type;
        // Bump relevant drive
        if (event.type === 'night_start' || event.type === 'hostile_entered_range') {
            this.driveModel.bump('safety', 0.3);
            this.driveModel.bump('rest', 0.2);
        } else if (event.type === 'new_player_nearby') {
            this.driveModel.bump('social', 0.3);
        } else if (event.type === 'queue_complete') {
            this.driveModel.bump('social', 0.15);
        }

        // Auto-flee for hostile events when unarmed or low HP
        if (event.type === 'hostile_entered_range') {
            const armed = this._hasWeapon(state.inventory);
            const safe = (state.health || 0) >= 14;
            if (!armed || !safe) {
                await this.bridge.sendAction({ type: 'flee', distance: 24, provider: 'baritone_chat' });
            }
        }

        const prompt = this._formatEventPrompt(event, state);
        this.history.add('system', prompt);
        await this._dispatchProactiveTurn(state, `event:${event.type}`);
    }

    _hasWeapon(inventory = []) {
        for (const stack of inventory) {
            const name = String(stack.item || '').toLowerCase();
            if (name.includes('sword') || name.includes('axe')) return true;
        }
        return false;
    }

    async _runAmbientTick(state) {
        const now = Date.now();
        if (now < this._nextAmbientTickAt) return;

        // Baseline re-eval: if suppressed, re-check in 10s. Overridden below on actual fire.
        this._nextAmbientTickAt = now + 10_000;

        this._maybeRefillAmbientBucket();
        if (this._ambientBucketCredits <= 0) {
            this._ambientLog({ t: now, dt_ms: 0, drives: this.driveModel.getSummary(), suppressed_by: 'budget', decision: 'silent' });
            return;
        }

        // Suppressors
        const queue = state.queue || {};
        if (queue.status === 'executing' || queue.status === 'draining' || queue.paused) {
            this._ambientLog({ t: now, dt_ms: 0, drives: this.driveModel.getSummary(), suppressed_by: 'queue_busy', decision: 'silent' });
            return;
        }
        if ((state.player_idle_ms || 0) > 300_000) {
            this._ambientLog({ t: now, dt_ms: 0, drives: this.driveModel.getSummary(), suppressed_by: 'player_afk', decision: 'silent' });
            return;
        }
        if (now - this.episodicMemory.lastSpokeAt < 90_000) {
            this._ambientLog({ t: now, dt_ms: 0, drives: this.driveModel.getSummary(), suppressed_by: 'recent_speech', decision: 'silent' });
            return;
        }
        if (now - this.episodicMemory.lastPlayerChatAnsweredAt < 60_000) {
            this._ambientLog({ t: now, dt_ms: 0, drives: this.driveModel.getSummary(), suppressed_by: 'conversation_turn', decision: 'silent' });
            return;
        }

        // Tick drives and decide
        const dtMs = now - this.driveModel.lastTickMs;
        this.driveModel.tick(dtMs);

        const flavors = ['observation', 'recall', 'intent'];
        const flavor = flavors[Math.floor(Math.random() * flavors.length)];
        const prompt = this._formatAmbientPrompt(state, flavor);
        this.history.add('system', prompt);

        const response = await this._dispatchProactiveTurn(state, 'ambient');

        // Determine decision from parsed response
        let decision = 'silent';
        let suppressedBy = null;
        if (!response || !response.trim()) {
            decision = 'silent';
            suppressedBy = 'empty_response';
        } else {
            const wantStructured = settings.bridge_structured_output === true;
            const parsed = parseBridgeResponse(response, wantStructured);
            const hasChat = parsed.chat && parsed.chat.trim().length > 0
                && !/^(no response needed|no reply|none|n\/a)$/i.test(parsed.chat.trim());
            if (hasChat) {
                decision = 'speak';
            } else if (parsed.actions.length > 0 || parsed.commands.length > 0) {
                decision = 'silent';
                suppressedBy = 'action_only';
            } else {
                decision = 'silent';
                suppressedBy = 'empty_response';
            }
        }

        this._ambientLog({
            t: now, dt_ms,
            drives: this.driveModel.getSummary(),
            suppressed_by: suppressedBy,
            decision,
            satisfied_drive: this.episodicMemory.lastSatisfiedDrive,
            topic: this.episodicMemory.lastSpokeTopic,
            event_reacted: this.episodicMemory.lastEventReacted,
            recent_events: (state.recent_events || []).map(e => e.type),
        });

        if (decision === 'speak') {
            this._ambientBucketCredits -= 1;
            // Schedule real jitter window only when tick actually fired
            const minGap = settings.bridge_ambient_min_gap_ms || 45_000;
            const maxGap = settings.bridge_ambient_max_gap_ms || 180_000;
            this._nextAmbientTickAt = now + minGap + Math.random() * (maxGap - minGap);
        }
    }

    _formatAmbientPrompt(state, flavor) {
        const hints = this.driveModel.getActiveHints();
        const mem = this.episodicMemory;
        const lastSpokeAgo = mem.lastSpokeAt ? Math.round((Date.now() - mem.lastSpokeAt) / 1000) + 's' : 'never';
        const recentEvents = (state.recent_events || []).slice(-3).map(e => e.type).join(', ') || 'none';

        let base = `AMBIENT TICK — ${flavor.toUpperCase()}\n`;
        base += `Current state: ${state.day_phase || 'unknown'}, ${state.hostile_count_nearby || 0} hostiles nearby, queue: ${state.queue?.status || 'idle'}.\n`;
        if (hints.length > 0) {
            base += `Drives: ${hints.join(' ')}\n`;
        }
        base += `Episodic memory: last spoke ${lastSpokeAgo} ago about "${mem.lastSpokeTopic || 'nothing'}".\n`;
        base += `Recent events: ${recentEvents}.\n`;
        base += `You may reply briefly in character, act via a single craft/mine/move action, or stay completely silent.`;
        return base;
    }

    async _dispatchProactiveTurn(state, sourceLabel) {
        const stateContext = this._buildStateContext(state);
        if (stateContext) {
            this.history.add('system', stateContext);
        }

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
            console.error(`LLM error in ${sourceLabel}:`, err);
            return '';
        }
        if (!response || response.trim().length === 0) return '';
        console.log(`${this.name} ${sourceLabel} LLM response: ${response}`);

        const { chat, commands, actions } = parseBridgeResponse(response, wantStructured);
        const suppressChat = /^(no response needed|no reply|none|n\/a)$/i.test(chat.trim());
        const chatText = suppressChat ? '' : chat;

        this.history.add(this.name, response);

        // Extract optional topic from structured response
        let topic = '';
        let satisfiedDrive = '';
        try {
            const json = extractJsonObjectCandidate(response);
            if (json) {
                const parsed = JSON.parse(json);
                if (parsed.topic) topic = String(parsed.topic);
                if (parsed.satisfied_drive) satisfiedDrive = String(parsed.satisfied_drive);
            }
        } catch {}

        if (chatText.trim()) {
            const trimmedChat = chatText.trim();
            sendOutputToServer(this.name, trimmedChat);
            if (settings.chat_ingame === true) {
                this._trackSentChat(trimmedChat);
                await this.bridge.sendCommand(`chat: ${trimmedChat}`);
            }
            this._updateEpisodicMemory(response, topic, satisfiedDrive || 'social');
        } else if (actions.length > 0 || commands.length > 0) {
            this._updateEpisodicMemory(response, topic, satisfiedDrive || 'curiosity');
        }

        if (actions.length > 0) {
            const batchResult = await this.bridge.sendBatch(actions);
            if (batchResult.success) {
                this._recordQueueDispatch('action', batchResult, actions.length);
            } else {
                this.history.add('system', `Batch dispatch failed: ${batchResult.error || 'unknown error'}`);
            }
        }
        if (commands.length > 0) {
            const batchResult = await this.bridge.sendBatchCommands(commands);
            if (batchResult.success) {
                this._recordQueueDispatch('command', batchResult, commands.length);
            } else {
                this.history.add('system', `Batch command dispatch failed: ${batchResult.error || 'unknown error'}`);
            }
        }

        this.history.save();
        return response;
    }

    _updateEpisodicMemory(response, topic, satisfiedDrive) {
        this.episodicMemory.lastSpokeAt = Date.now();
        if (topic) this.episodicMemory.lastSpokeTopic = topic;
        if (satisfiedDrive) {
            this.episodicMemory.lastSatisfiedDrive = satisfiedDrive;
            this.driveModel.satisfy(satisfiedDrive);
        }
        // Persist episodic memory into the shared memory file
        try {
            let data = {};
            try {
                if (existsSync(this.history.memory_fp)) {
                    data = JSON.parse(readFileSync(this.history.memory_fp, 'utf8'));
                }
            } catch {}
            data.episodic = this.episodicMemory;
            writeFileSync(this.history.memory_fp, JSON.stringify(data, null, 2));
        } catch (err) {
            console.error('Failed to persist episodic memory:', err);
        }
    }

    _maybeRefillAmbientBucket() {
        const budget = settings.bridge_ambient_budget_per_hour || 4;
        const now = Date.now();
        const refillInterval = 3_600_000 / Math.max(1, budget);
        const elapsed = now - this._ambientLastRefillMs;
        if (elapsed >= refillInterval) {
            const credits = Math.floor(elapsed / refillInterval);
            this._ambientBucketCredits = Math.min(budget, this._ambientBucketCredits + credits);
            this._ambientLastRefillMs = now;
        }
    }

    _ambientLog(entry) {
        try {
            const line = JSON.stringify(entry) + '\n';
            appendFileSync(this._ambientLogPath, line, 'utf8');
        } catch (err) {
            console.error('Failed to write ambient log:', err);
        }
    }
}
