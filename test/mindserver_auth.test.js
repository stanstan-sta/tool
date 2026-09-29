import test from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { io } from 'socket.io-client';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
async function setup(t) {
    const fixture = mindserverFixture(t, process.env.AUTH_BASELINE);
    const serverModule = await import(pathToFileURL(path.join(fixture.root, 'src/mindcraft/mindserver.js')));
    const server = serverModule.createMindServer(false, 0);
    await once(server, 'listening');
    t.after(() => new Promise(resolve => serverModule.getIO().close(resolve)));
    return { ...fixture, serverModule, base: `http://localhost:${server.address().port}`, token: serverModule.getAccessToken?.() || 'baseline' };
}

test('HTTP control and data routes require the current bearer credential before any mutation', async t => {
    const f = await setup(t);
    const before = readFileSync(path.join(f.root, 'keys.json'), 'utf8');
    for (const authorization of [undefined, 'Bearer wrong', f.token]) {
        for (const [route, method, body] of [
            ['/api/keys', 'GET'], ['/api/profiles', 'GET'], ['/api/startup-profiles', 'GET'],
            ['/api/keys', 'POST', { OPENAI_API_KEY: 'unauthorized' }],
            ['/api/startup-profiles', 'PUT', []],
            ['/api/profiles/good', 'DELETE'],
        ]) {
            const headers = { 'content-type': 'application/json' };
            if (authorization) headers.Authorization = authorization;
            const response = await fetch(f.base + route, { method, headers, body: body && JSON.stringify(body) });
            assert.equal(response.status, 401, `${method} ${route}`);
            assert.deepEqual(await response.json(), { error: 'Unauthorized' });
        }
    }
    assert.equal(readFileSync(path.join(f.root, 'keys.json'), 'utf8'), before);
    const response = await fetch(f.base + '/api/startup-profiles', { headers: { Authorization: `Bearer ${f.token}` } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), []);
});

test('unauthenticated Socket.IO clients cannot connect; the owner can read settings', async t => {
    const f = await setup(t);
    f.serverModule.registerAgent({ profile: { name: 'Alpha', model: 'fixture' } });
    for (const token of [undefined, 'wrong']) {
        const client = io(f.base, { reconnection: false, transports: ['websocket'], auth: { token, agentName: 'Alpha' } });
        t.after(() => client.disconnect());
        const outcome = await new Promise(resolve => {
            client.once('connect', () => resolve('connected'));
            client.once('connect_error', error => resolve(error.message));
        });
        assert.equal(outcome, 'Unauthorized');
        assert.equal(client.connected, false);
    }
    const owner = io(f.base, { reconnection: false, transports: ['websocket'], auth: { token: f.token } });
    t.after(() => owner.disconnect());
    await once(owner, 'connect');
    assert.equal((await owner.timeout(1500).emitWithAck('get-settings', 'Alpha')).settings.profile.name, 'Alpha');
});

test('a new parent instance rejects the previous instance credential', async t => {
    const first = await setup(t);
    const second = await setup(t);
    assert.match(second.token, /^[0-9a-f]{64}$/);
    assert.notEqual(first.token, second.token);
    assert.equal((await fetch(second.base + '/api/startup-profiles', { headers: { Authorization: `Bearer ${first.token}` } })).status, 401);
});

test('real parent, child launcher and proxy carry authentication through agent startup', { timeout: 12000 }, async t => {
    const f = mindserverFixture(t);
    for (const relative of ['src/process/agent_process.js', 'src/agent/mindserver_proxy.js', 'src/mindcraft/mindcraft.js', 'src/agent/history.js', 'src/agent/npc/data.js']) {
        mkdirSync(path.dirname(path.join(f.root, relative)), { recursive: true });
        copyFileSync(path.join(repo, relative), path.join(f.root, relative));
    }
    mkdirSync(path.join(f.root, 'src/agent/library'), { recursive: true });
    mkdirSync(path.join(f.root, 'src/runtime/fabric'), { recursive: true });
    f.write('src/agent/conversation.js', 'export default { receiveFromBot() {}, updateAgents() {} };');
    f.write('src/agent/settings.js', 'export function setSettings(settings) { if (settings.profile.name !== "Alpha") throw Error("Wrong settings"); } export default {};');
    f.write('src/agent/library/full_state.js', 'export function getFullState() { return {}; }');
    f.write('src/runtime/fabric/create_agent_runtime.js', 'export async function prepareFabricRuntime(settings) { return { settings }; }');
    f.write('src/process/init_bridge_agent.js', `
        import { serverProxy } from '../agent/mindserver_proxy.js';
        await serverProxy.connect('Alpha', Number(process.argv[process.argv.indexOf('-p') + 1]));
        const response = await fetch('http://localhost:' + process.argv[process.argv.indexOf('-p') + 1] + '/api/keys', {
            headers: { Authorization: 'Bearer ' + process.env.MINDSERVER_TOKEN }
        });
        if (response.status !== 401) throw Error('Child received owner privileges');
        console.log('AUTHENTICATED_CHILD');
        serverProxy.socket.disconnect();
        process.exit(0);
    `);
    f.write('parent.js', `
        import * as mindcraft from './src/mindcraft/mindcraft.js';
        import { getIO } from './src/mindcraft/mindserver.js';
        import { once } from 'node:events';
        import net from 'node:net';
        const reservation = net.createServer();
        reservation.listen(0, 'localhost');
        await once(reservation, 'listening');
        const port = reservation.address().port;
        await new Promise(resolve => reservation.close(resolve));
        await mindcraft.init(false, port, false);
        const server = getIO().httpServer;
        if (!server.listening) await once(server, 'listening');
        await mindcraft.createAgent({ profile: { name: 'Alpha' } });
        const child = mindcraft.getAgentProcess('Alpha');
        const [code] = await once(child.process, 'exit');
        getIO().close(() => process.exit(code || 0));
    `);
    const child = spawn(process.execPath, ['parent.js'], {
        cwd: f.root, env: { ...process.env, PATH: `${path.dirname(process.execPath)}${path.delimiter}${process.env.PATH}` }, stdio: ['ignore', 'pipe', 'pipe'],
    });
    t.after(() => { if (child.exitCode === null) child.kill(); });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    assert.equal((await once(child, 'exit'))[0], 0, output);
    assert.match(output, /Dashboard access link: http:\/\/localhost:\d+\/#token=[0-9a-f]{64}/);
    assert.match(output, /AUTHENTICATED_CHILD/);
});
