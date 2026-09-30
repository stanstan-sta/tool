import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { History, renderMemorySchema } from '../src/agent/history.js';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';
import { Agent } from '../src/agent/agent.js';
import { serverProxy } from '../src/agent/mindserver_proxy.js';
import { createMindServer, getIO, registerAgent, issueAgentToken } from '../src/mindcraft/mindserver.js';

before(async () => {
    const server = createMindServer(false, 0);
    await once(server, 'listening');
    registerAgent({ profile: { name: 'MemoryAudit', model: 'fixture' } });
    const previousToken = process.env.MINDSERVER_TOKEN;
    process.env.MINDSERVER_TOKEN = issueAgentToken('MemoryAudit');
    try {
        await serverProxy.connect('MemoryAudit', server.address().port);
    } finally {
        if (previousToken === undefined) delete process.env.MINDSERVER_TOKEN;
        else process.env.MINDSERVER_TOKEN = previousToken;
    }
});

after(() => {
    serverProxy.setAgent(null);
    serverProxy.getSocket()?.disconnect();
    return new Promise(resolve => getIO().close(resolve));
});

function fixture(t) {
    const root = mkdtempSync(path.join(tmpdir(), 'mindcraft-memory-controls-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const agent = { name: 'MemoryAudit' };
    const schema = { version: 1, facts: [{ kind: 'preference', text: 'The player prefers oak houses.' }] };
    const history = Object.create(History.prototype);
    Object.assign(history, {
        agent, name: agent.name, memory_fp: path.join(root, 'memory.json'),
        turns: [{ role: 'user', content: 'a transient conversation' }],
        memory_schema: schema, memory: renderMemorySchema(schema), max_messages: 1000,
    });
    agent.history = history;
    const reload = () => {
        const restored = Object.create(History.prototype);
        restored.memory_fp = history.memory_fp;
        restored.load();
        return restored;
    };
    return { agent, history, schema, reload };
}

async function setImportantMemory(t, agent, value) {
    const previous = serverProxy.agent;
    serverProxy.setAgent(agent);
    t.after(() => serverProxy.setAgent(previous));
    const listener = serverProxy.getSocket().listeners('set-important-memory')[0];
    assert.equal(typeof listener, 'function');
    await listener(value);
}

test('bridge clear with preserveImportant retains typed facts after reload', async t => {
    const f = fixture(t);
    await BridgeAgent.prototype.clearAllMemory.call(f.agent, true);
    const restored = f.reload();
    assert.deepEqual(restored.memory_schema, f.schema);
    assert.equal(restored.memory, 'The player prefers oak houses.');
    assert.deepEqual(restored.turns, []);
});

test('legacy clear with preserveImportant retains typed facts after reload', async t => {
    const f = fixture(t);
    f.agent.memory_bank = { getJson: () => ({}), loadJson: () => {} };
    await Agent.prototype.clearAllMemory.call(f.agent, true);
    assert.deepEqual(f.reload().memory_schema, f.schema);
});

test('clear without preserveImportant removes typed facts after reload', async t => {
    const f = fixture(t);
    await BridgeAgent.prototype.clearAllMemory.call(f.agent, false);
    const restored = f.reload();
    assert.deepEqual(restored.memory_schema.facts, []);
    assert.equal(restored.memory, '');
});

test('dashboard important-memory update persists its new facts after reload', async t => {
    const f = fixture(t);
    await setImportantMemory(t, f.agent, 'The player prefers stone houses.');
    const restored = f.reload();
    assert.equal(restored.memory, 'The player prefers stone houses.');
    assert.equal(restored.memory_schema.facts[0].text, restored.memory);
});

test('dashboard important-memory deletion does not revive the old schema', async t => {
    const f = fixture(t);
    await setImportantMemory(t, f.agent, '');
    const restored = f.reload();
    assert.equal(restored.memory, '');
    assert.deepEqual(restored.memory_schema.facts, []);
});
