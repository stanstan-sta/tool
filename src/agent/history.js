import { writeFileSync, readFileSync, mkdirSync, existsSync } from 'fs';
import { NPCData } from './npc/data.js';
import settings from './settings.js';


const MEMORY_FILTER_INSTRUCTION =
    'Memory-filtered transcript. Save only stable user preferences, explicit reminders, corrected facts, or confirmed successful outcomes. Ignore action proposals, queued/failed bridge actions, transient state, inventory, coordinates, docs, and anything the bot merely said it would do.';

// A9: persisted memory is replayed into prompts, so it must never carry
// instruction-like content. validateMemoryText returns the text when it is a
// plain fact-style string, or null when it looks like an instruction,
// command proposal, or markup injection. Callers keep the previous memory
// (store path) or fall back to '' (load path) on rejection.
const MEMORY_MAX_STORED_CHARS = 2000;
const MEMORY_INSTRUCTION_PATTERNS = [
    /ignore\s+(all\s+)?(previous|prior|above)\s+instructions/im,
    /^\s*system\s*:/im,
    // Same breadth as the assistant action-proposal detector: summaries must
    // never carry executable proposals, wherever they appear in the text.
    /"(actions|commands)"\s*:|\b(ACTION|COMMAND)\s*:/im,
    /![a-zA-Z_][\w-]*\s*\(/m,
    /<\s*script\b/im,
];

export function validateMemoryText(text) {
    if (typeof text !== 'string') return null;
    const trimmed = text.trim();
    if (!trimmed) return null;
    if (trimmed.length > MEMORY_MAX_STORED_CHARS) return null;
    if (MEMORY_INSTRUCTION_PATTERNS.some(pattern => pattern.test(trimmed))) return null;
    return trimmed;
}

function parseJsonObject(text) {
    const trimmed = String(text || '').trim();
    if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return null;
    try {
        const parsed = JSON.parse(trimmed);
        return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
    } catch {
        return null;
    }
}

function looksLikeActionProposal(text) {
    const value = String(text || '');
    return /"actions"\s*:|"commands"\s*:|\b(ACTION|COMMAND)\s*:/im.test(value)
        || /![a-zA-Z_][\w-]*\s*\(/.test(value);
}

function sanitizeAssistantTurn(content) {
    const parsed = parseJsonObject(content);
    if (parsed) {
        const hasActions = Array.isArray(parsed.actions) && parsed.actions.length > 0;
        const hasCommands = Array.isArray(parsed.commands) && parsed.commands.length > 0;
        const loneAction = parsed.type && !parsed.reply && !parsed.chat;
        if (hasActions || hasCommands || loneAction) return null;
        const reply = typeof parsed.reply === 'string'
            ? parsed.reply.trim()
            : (typeof parsed.chat === 'string' ? parsed.chat.trim() : '');
        return reply ? { role: 'assistant', content: reply } : null;
    }
    if (looksLikeActionProposal(content)) return null;
    const trimmed = String(content || '').trim();
    return trimmed ? { role: 'assistant', content: trimmed } : null;
}

export function sanitizeTurnsForMemory(turns) {
    if (!Array.isArray(turns)) return [];
    const sanitized = [];
    for (const turn of turns) {
        if (!turn || typeof turn.content !== 'string') continue;
        if (turn.role === 'system') continue;

        if (turn.role === 'assistant') {
            const clean = sanitizeAssistantTurn(turn.content);
            if (clean) sanitized.push(clean);
            continue;
        }

        const trimmed = turn.content.trim();
        if (trimmed) sanitized.push({ role: 'user', content: trimmed });
    }
    return sanitized;
}

// W1: turns restored from disk are untrusted prompt input. Only user/assistant
// turns with string content survive; system-role turns from the file are
// dropped (fresh state is rebuilt every prompt) so a crafted memory.json can
// never inject system-role instructions. Returns the sanitized array.
export function sanitizeLoadedTurns(turns) {
    if (turns === undefined) return [];
    if (!Array.isArray(turns)) {
        console.warn('Discarded malformed stored turns (not an array); starting with empty turns.');
        return [];
    }
    const clean = [];
    let dropped = 0;
    for (const turn of turns) {
        if (!turn || typeof turn !== 'object' || Array.isArray(turn)) { dropped++; continue; }
        if ((turn.role !== 'user' && turn.role !== 'assistant') || typeof turn.content !== 'string') { dropped++; continue; }
        clean.push({ role: turn.role, content: turn.content });
    }
    if (dropped > 0) {
        console.warn(`Discarded ${dropped} malformed or system-role stored turn(s); kept ${clean.length}.`);
    }
    return clean;
}

export class History {
    constructor(agent) {
        this.agent = agent;
        this.name = agent.name;
        this.memory_fp = `./bots/${this.name}/memory.json`;
        this.full_history_fp = undefined;

        mkdirSync(`./bots/${this.name}/histories`, { recursive: true });

        this.turns = [];

        // Natural language memory as a summary of recent messages + previous memory
        this.memory = '';

        // Maximum number of messages to keep in context before saving chunk to memory
        this.max_messages = settings.max_messages;

        // Number of messages to remove from current history and save into memory
        this.summary_chunk_size = 15; 
        // chunking reduces expensive calls to promptMemSaving and appendFullHistory
        // and improves the quality of the memory summary
    }

    getHistory() { // expects an Examples object
        return JSON.parse(JSON.stringify(this.turns));
    }

    async summarizeMemories(turns) {
        console.log("Storing memories...");
        const sanitized = sanitizeTurnsForMemory(turns);
        if (sanitized.length === 0) {
            console.log("No memory-worthy turns in compacted chunk.");
            return;
        }
        const summary = await this.agent.prompter.promptMemSaving([
            { role: 'system', content: MEMORY_FILTER_INSTRUCTION },
            ...sanitized,
        ]);

        const validated = validateMemoryText(summary);
        if (validated === null) {
            // Never persist instruction-like summaries; keep prior memory.
            console.warn("Rejected instruction-like memory summary; keeping previous memory.");
            return;
        }
        this.memory = validated;

        if (this.memory.length > 500) {
            this.memory = this.memory.slice(0, 500);
            this.memory += '...(Memory truncated to 500 chars. Compress it more next time)';
        }

        console.log("Memory updated to: ", this.memory);
    }

    async appendFullHistory(to_store) {
        if (this.full_history_fp === undefined) {
            const string_timestamp = new Date().toLocaleString().replace(/[/:]/g, '-').replace(/ /g, '').replace(/,/g, '_');
            this.full_history_fp = `./bots/${this.name}/histories/${string_timestamp}.json`;
            writeFileSync(this.full_history_fp, '[]', 'utf8');
        }
        try {
            const data = readFileSync(this.full_history_fp, 'utf8');
            let full_history = JSON.parse(data);
            full_history.push(...to_store);
            writeFileSync(this.full_history_fp, JSON.stringify(full_history, null, 4), 'utf8');
        } catch (err) {
            console.error(`Error reading ${this.name}'s full history file: ${err.message}`);
        }
    }

    async add(name, content) {
        let role = 'assistant';
        if (name === 'system') {
            role = 'system';
        }
        else if (name !== this.name) {
            role = 'user';
            content = `${name}: ${content}`;
        }
        this.turns.push({role, content});

        if (settings.auto_compact_memory !== false && this.turns.length >= this.max_messages) {
            let chunk = this.turns.splice(0, this.summary_chunk_size);
            while (this.turns.length > 0 && this.turns[0].role === 'assistant')
                chunk.push(this.turns.shift()); // remove until turns starts with system/user message

            await this.summarizeMemories(chunk);
            await this.appendFullHistory(chunk);
        }
    }

    async save() {
        try {
            // Preserve extra keys (e.g. episodic memory) that other subsystems write
            let existing = {};
            try {
                if (existsSync(this.memory_fp)) {
                    existing = JSON.parse(readFileSync(this.memory_fp, 'utf8'));
                }
            } catch {}

            const selfPrompter = this.agent?.self_prompter;
            const data = {
                ...existing,
                memory: this.memory,
                turns: this.turns,
                self_prompting_state: selfPrompter?.state ?? null,
                self_prompt: selfPrompter
                    ? ((typeof selfPrompter.isStopped === 'function' && selfPrompter.isStopped()) ? null : (selfPrompter.prompt ?? null))
                    : null,
                taskStart: this.agent?.task?.taskStartTime ?? null,
                last_sender: this.agent?.last_sender ?? null
            };
            writeFileSync(this.memory_fp, JSON.stringify(data, null, 2));
            console.log('Saved memory to:', this.memory_fp);
        } catch (error) {
            console.error('Failed to save history:', error);
            throw error;
        }
    }

    load() {
        try {
            if (!existsSync(this.memory_fp)) {
                console.log('No memory file found.');
                return null;
            }
            let data;
            try {
                data = JSON.parse(readFileSync(this.memory_fp, 'utf8'));
            } catch (err) {
                // W1: a corrupt file must not crash the agent (both bridge and
                // legacy init call load() without a try/catch). Skip + warn.
                console.warn(`Discarding corrupt memory file ${this.memory_fp}; starting with empty memory.`);
                this.memory = '';
                this.turns = [];
                return null;
            }
            if (!data || typeof data !== 'object' || Array.isArray(data)) {
                console.warn(`Discarding unexpected-shape memory file ${this.memory_fp}; starting with empty memory.`);
                this.memory = '';
                this.turns = [];
                return null;
            }
            // A9: stored memory is untrusted input. Non-string, overlong, or
            // instruction-like content is discarded, never replayed.
            this.memory = validateMemoryText(data.memory) ?? '';
            if (data.memory !== undefined && data.memory !== null && data.memory !== '' && !this.memory) {
                console.warn('Discarded invalid stored memory; starting with empty memory.');
            }
            // W1: stored turns are untrusted prompt input. System-role and
            // malformed entries are dropped so they can never replay as
            // system-role instructions; user/assistant flows are preserved.
            this.turns = sanitizeLoadedTurns(data.turns);
            console.log('Loaded memory:', this.memory);
            return { ...data, memory: this.memory, turns: this.turns };
        } catch (error) {
            // W1: never let a malformed file crash the agent; fall back empty.
            console.warn(`Discarding unreadable memory file ${this.memory_fp}; starting with empty memory.`);
            this.memory = '';
            this.turns = [];
            return null;
        }
    }

    clear() {
        this.turns = [];
        this.memory = '';
    }
}
