import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { spawnSync } from 'node:child_process';
import { io } from 'socket.io-client';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

async function setup(t) {
    const f = mindserverFixture(t, process.env.AGENT_AUTH_BASELINE);
    const module = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    for (const name of ['Alpha', 'Beta']) module.registerAgent({ profile: { name, model: 'fixture' } });
    const server = module.createMindServer(false, 0);
    await once(server, 'listening');
    const base = `http://localhost:${server.address().port}`;
    t.after(() => new Promise(resolve => module.getIO().close(resolve)));
    const issue = name => module.issueAgentToken?.(name) || module.getAccessToken();
    const connect = async (agentName, token = agentName ? issue(agentName) : module.getAccessToken(), rejected = false) => {
        const client = io(base, { reconnection: false, transports: ['websocket'], auth: { token, agentName } });
        t.after(() => client.disconnect());
        const result = await new Promise(resolve => {
            client.once('connect', () => resolve('connected'));
            client.once('connect_error', error => resolve(error.message));
        });
        assert.equal(result, rejected ? 'Unauthorized' : 'connected');
        return client;
    };
    return { module, base, issue, connect };
}
const ack = (client, event, ...args) => client.timeout(1500).emitWithAck(event, ...args);

test('child identity is fixed at authentication and administrative events are forbidden', async t => {
    const f = await setup(t);
    const alpha = await f.connect('Alpha');
    const beta = await f.connect('Beta');
    const owner = await f.connect();
    alpha.emit('login-agent', 'Alpha');
    beta.emit('login-agent', 'Beta');
    beta.on('get-agent-memory', callback => callback('beta memory'));
    assert.equal((await ack(alpha, 'get-settings', 'Alpha')).settings.profile.name, 'Alpha');
    for (const [event, ...args] of [
        ['get-settings', 'Beta'], ['connect-agent-process', 'Beta'], ['login-agent', 'Beta'], ['bot-output', 'Beta', 'spoof'],
        ['create-agent', {}], ['set-agent-settings', 'Alpha', {}], ['restart-agent', 'Beta'], ['stop-agent', 'Beta'],
        ['start-agent', 'Beta'], ['destroy-agent', 'Beta'], ['stop-all-agents'], ['shutdown'], ['listen-to-agents'],
        ['get-agent-memory', 'Beta'], ['clear-agent-memory', 'Beta'], ['compact-agent-memory', 'Beta'],
        ['set-important-memory', 'Beta', 'tampered'], ['send-message', 'Beta', { message: 'spoof' }],
    ]) assert.deepEqual(await ack(alpha, event, ...args), { success: false, error: 'Forbidden' }, event);
    assert.equal(f.module.numStateListeners(), 0);
    assert.equal((await ack(owner, 'get-agent-memory', 'Beta')).memory, 'beta memory');
    const chat = once(beta, 'chat-message');
    alpha.emit('chat-message', 'Beta', { message: 'real peer message' });
    assert.deepEqual(await chat, ['Alpha', { message: 'real peer message' }]);
    const output = once(owner, 'bot-output');
    alpha.emit('bot-output', 'Alpha', 'own log');
    assert.deepEqual(await output, ['Alpha', 'own log']);
});

test('child credentials cannot access HTTP APIs, change identity or be reused', async t => {
    const f = await setup(t);
    const token = f.issue('Alpha');
    await f.connect('Beta', token, true);
    await f.connect('Alpha', token);
    await f.connect('Alpha', token, true);
    assert.equal((await fetch(f.base + '/api/keys', { headers: { Authorization: `Bearer ${token}` } })).status, 401);
    f.module.registerAgent({ profile: { name: '__proto__', model: 'fixture' } });
    const special = await f.connect('__proto__');
    assert.equal((await ack(special, 'get-settings', '__proto__')).settings.profile.name, '__proto__');
});

test('new launch credentials disconnect the old child and invalidate earlier unused credentials', async t => {
    const f = await setup(t);
    const unused = f.issue('Alpha');
    const first = await f.connect('Alpha');
    const disconnected = once(first, 'disconnect');
    const nextToken = f.issue('Alpha');
    await disconnected;
    await f.connect('Alpha', unused, true);
    const replacement = await f.connect('Alpha', nextToken);
    assert.equal((await ack(replacement, 'get-settings', 'Alpha')).settings.profile.name, 'Alpha');
    const owner = await f.connect();
    replacement.on('get-agent-memory', callback => callback('replacement'));
    assert.equal((await ack(owner, 'get-agent-memory', 'Alpha')).memory, 'replacement');
});

test('disconnect during initialization clears the authenticated process association', async t => {
    const f = await setup(t);
    const child = await f.connect('Alpha');
    const owner = await f.connect();
    const status = new Promise(resolve => owner.on('agents-status', agents => {
        if (agents.find(agent => agent.name === 'Alpha')?.socket_connected === false) resolve();
    }));
    child.disconnect();
    await status;
    assert.match((await ack(owner, 'get-agent-memory', 'Alpha')).error, /not connected/);
});

test('an explicitly assigned benchmark retains its run-completion shutdown capability', { timeout: 9000 }, t => {
    const f = mindserverFixture(t);
    f.write('src/mindcraft/mindcraft.js', 'export function stopAgent(name) { console.log("STOPPED:" + name); }');
    f.write('benchmark.js', `
        import { createMindServer, registerAgent, issueAgentToken } from './src/mindcraft/mindserver.js';
        import { once } from 'node:events';
        import { io } from 'socket.io-client';
        registerAgent({ profile: { name: 'Benchmark' }, task: { task_id: 'fixture-task' } });
        const server = createMindServer(false, 0);
        await once(server, 'listening');
        const client = io('http://localhost:' + server.address().port, {
            auth: { token: issueAgentToken('Benchmark'), agentName: 'Benchmark' }, reconnection: false
        });
        await once(client, 'connect');
        client.emit('shutdown');
    `);
    const result = spawnSync(process.execPath, ['benchmark.js'], { cwd: f.root, encoding: 'utf8', timeout: 6000 });
    assert.equal(result.error, undefined, result.stderr);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /STOPPED:Benchmark/);
    assert.match(result.stdout, /Exiting MindServer/);
});
