import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { strictFormat } from '../utils/text.js';

// Talks to a ChatGPT-subscription model through a persistent `codex app-server`
// (JSON-RPC over stdio). One process is shared by every Codex model instance;
// each request runs in its own ephemeral thread so no history leaks between turns.
// Profile usage: "model": "codex/gpt-5.6-luna", optional params:
//   { "effort": "none" | "low" | ..., "output_schema": {...}, "timeout_ms": 60000 }

const DEFAULT_TIMEOUT_MS = 60000;
const MAX_LATE_RESPONSE_MS = 5000;
const MAX_INTERRUPT_TIMEOUT_MS = 1000;
let server = null;

// The bot never uses the user's Codex MCP servers or plugins, and starting them on every
// thread adds seconds to each turn, so switch off every one named in config.toml.
function disabledToolArgs() {
    const file = path.join(process.env.CODEX_HOME || path.join(os.homedir(), '.codex'), 'config.toml');
    let toml = '';
    try { toml = fs.readFileSync(file, 'utf8'); } catch { return ' -c features.apps=false'; }
    const names = [...toml.matchAll(/^\[(mcp_servers\.[A-Za-z0-9_-]+|plugins\."[^"]+")\]\s*$/gm)].map(m => m[1]);
    return ' -c features.apps=false' + names.map(n => ` -c "${n.replaceAll('"', '\\"')}.enabled=false"`).join('');
}

function startServer() {
    const proc = spawn('codex app-server' + disabledToolArgs(), { shell: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const state = { proc, nextId: 1, pending: new Map(), lateResponses: new Map(), listeners: new Set(), ready: null, dead: false };

    const fail = (err) => {
        if (state.dead) return;
        state.dead = true;
        if (server === state) server = null;
        for (const { reject } of state.pending.values()) reject(err);
        state.pending.clear();
        for (const { timer } of state.lateResponses.values()) clearTimeout(timer);
        state.lateResponses.clear();
        for (const listener of state.listeners) listener({ method: '__exit__', error: err });
    };
    proc.on('error', fail);
    proc.on('exit', code => fail(new Error(`codex app-server exited (code ${code})`)));
    proc.stderr.on('data', d => console.warn(`[codex] ${String(d).trim()}`));

    readline.createInterface({ input: proc.stdout }).on('line', line => {
        let msg;
        try { msg = JSON.parse(line); } catch { return; }
        if (msg.id !== undefined && state.pending.has(msg.id)) {
            const { resolve, reject } = state.pending.get(msg.id);
            state.pending.delete(msg.id);
            if (msg.error) reject(new Error(`codex ${msg.error.message || JSON.stringify(msg.error)}`));
            else resolve(msg.result);
            return;
        }
        if (msg.id !== undefined && state.lateResponses.has(msg.id)) {
            const { onResult, timer } = state.lateResponses.get(msg.id);
            state.lateResponses.delete(msg.id);
            clearTimeout(timer);
            if (!msg.error) onResult(msg.result);
            return;
        }
        for (const listener of state.listeners) listener(msg);
    });

    state.call = (method, params, options = {}) => new Promise((resolve, reject) => {
        if (state.dead) return reject(new Error('codex app-server is not running'));
        const { signal, onLateResult, lateResponseMs = MAX_LATE_RESPONSE_MS } = options;
        if (signal?.aborted) return reject(signal.reason);
        const id = state.nextId++;
        const cleanup = () => signal?.removeEventListener('abort', abort);
        const finish = callback => value => {
            cleanup();
            callback(value);
        };
        const abort = () => {
            if (!state.pending.delete(id)) return;
            cleanup();
            if (onLateResult) {
                const timer = setTimeout(() => state.lateResponses.delete(id), lateResponseMs);
                state.lateResponses.set(id, { onResult: onLateResult, timer });
            }
            reject(signal.reason);
        };
        state.pending.set(id, { resolve: finish(resolve), reject: finish(reject) });
        signal?.addEventListener('abort', abort, { once: true });
        proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
    const initialization = new AbortController();
    const initializationTimer = setTimeout(
        () => initialization.abort(new Error(`codex app-server initialization timed out after ${DEFAULT_TIMEOUT_MS}ms`)),
        DEFAULT_TIMEOUT_MS,
    );
    state.ready = state.call(
        'initialize',
        { clientInfo: { name: 'mindcraft-bridge', version: '1.0.0' } },
        { signal: initialization.signal },
    ).then(() => proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'initialized' }) + '\n'))
        .finally(() => clearTimeout(initializationTimer));
    state.ready.catch(err => {
        fail(err);
        if (!proc.killed) proc.kill();
    });
    return state;
}

function waitFor(promise, signal) {
    if (!signal) return promise;
    if (signal.aborted) throw signal.reason;
    return new Promise((resolve, reject) => {
        const abort = () => {
            signal.removeEventListener('abort', abort);
            reject(signal.reason);
        };
        signal.addEventListener('abort', abort, { once: true });
        promise.then(
            value => { signal.removeEventListener('abort', abort); resolve(value); },
            err => { signal.removeEventListener('abort', abort); reject(err); },
        );
    });
}

async function getServer(signal) {
    if (!server) server = startServer();
    const current = server;
    await waitFor(current.ready, signal);
    return current;
}

function turnIdFrom(result) {
    return result?.turn?.id ?? result?.turnId;
}

function turnsToText(turns) {
    return strictFormat(turns)
        .map(t => `[${t.role}]\n${typeof t.content === 'string' ? t.content : JSON.stringify(t.content)}`)
        .join('\n\n');
}

export class Codex {
    static prefix = 'codex';

    constructor(model_name, url, params, testHooks = {}) {
        this.model_name = model_name || 'gpt-5.6-luna';
        this.params = params || {};
        this._getServer = testHooks.getServer || getServer;
    }

    async sendRequest(turns, systemMessage) {
        try {
            console.log('Awaiting Codex response from model', this.model_name);
            const res = await this._runTurn(systemMessage, turnsToText(turns));
            console.log('Received.');
            return res;
        } catch (err) {
            console.log(err);
            return 'My brain disconnected, try again.';
        }
    }

    async _runTurn(instructions, text) {
        const timeoutMs = this.params.timeout_ms ?? DEFAULT_TIMEOUT_MS;
        const timeout = new AbortController();
        const timer = setTimeout(() => timeout.abort(new Error(`codex turn timed out after ${timeoutMs}ms`)), timeoutMs);
        const signal = timeout.signal;
        let srv;
        let threadId;
        let turnId;
        let reply = '';
        let listener;
        let removeDoneAbort;
        let interruptedTurnId;

        const interrupt = async id => {
            if (!srv || !threadId || !id || interruptedTurnId === id) return;
            interruptedTurnId = id;
            const cleanup = new AbortController();
            const cleanupMs = Math.min(MAX_INTERRUPT_TIMEOUT_MS, Math.max(50, Math.floor(timeoutMs / 4)));
            const cleanupTimer = setTimeout(() => cleanup.abort(new Error('codex turn/interrupt timed out')), cleanupMs);
            try {
                await srv.call('turn/interrupt', { threadId, turnId: id }, { signal: cleanup.signal });
            } catch {
                // The original deadline is authoritative; cleanup is best effort and bounded.
            } finally {
                clearTimeout(cleanupTimer);
            }
        };

        try {
            srv = await this._getServer(signal);
            const thread = await srv.call('thread/start', {
                model: this.model_name,
                ephemeral: true,
                sandbox: 'read-only',
                approvalPolicy: 'never',
                baseInstructions: instructions,
            }, { signal });
            threadId = thread?.thread?.id ?? thread?.threadId;
            if (!threadId) throw new Error('codex thread/start returned no thread id');

            const done = new Promise((resolve, reject) => {
                const abort = () => reject(signal.reason);
                removeDoneAbort = () => signal.removeEventListener('abort', abort);
                signal.addEventListener('abort', abort, { once: true });
                listener = msg => {
                    const p = msg.params || {};
                    if (msg.method === '__exit__') return reject(msg.error);
                    if (p.threadId && p.threadId !== threadId) return;
                    if (msg.method === 'turn/started') turnId = p.turn?.id ?? turnId;
                    if (msg.method === 'item/completed' && p.item?.type === 'agentMessage') reply = p.item.text ?? reply;
                    if (msg.method === 'turn/completed') {
                        const status = p.turn?.status;
                        if (status && status !== 'completed') reject(new Error(`codex turn ${status}: ${JSON.stringify(p.turn?.error ?? '')}`));
                        else resolve();
                    }
                };
                srv.listeners.add(listener);
            });
            done.catch(() => {}); // turn/start can fail before completion is awaited

            const started = await srv.call('turn/start', {
                threadId,
                effort: this.params.effort ?? 'none',
                ...(this.params.output_schema ? { outputSchema: this.params.output_schema } : {}),
                input: [{ type: 'text', text }],
            }, {
                signal,
                onLateResult: result => { void interrupt(turnIdFrom(result)); },
            });
            turnId = turnIdFrom(started) ?? turnId;
            await done;
            return reply;
        } catch (err) {
            if (signal.aborted) await interrupt(turnId);
            throw err;
        } finally {
            clearTimeout(timer);
            removeDoneAbort?.();
            if (listener) srv?.listeners.delete(listener);
        }
    }
}
