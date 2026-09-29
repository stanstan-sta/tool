import test from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { spawnSync } from 'node:child_process';
import { io } from 'socket.io-client';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('sync and async socket-handler errors are contained and other requests still work', { timeout: 10000 }, async t => {
    const f = mindserverFixture(t, process.env.ERROR_BOUNDARY_BASELINE);
    const module = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    module.registerAgent({ profile: { name: 'Offline' } });
    const server = module.createMindServer(false, 0);
    await once(server, 'listening');
    const client = io(`http://localhost:${server.address().port}`, { transports: ['websocket'], reconnection: false, auth: { token: module.getAccessToken?.() || 'baseline' } });
    t.after(() => { client.disconnect(); return new Promise(resolve => module.getIO().close(resolve)); });
    await once(client, 'connect');
    const ack = (event, ...args) => client.timeout(1500).emitWithAck(event, ...args);
    // Existing offline socket dereference is a synchronous handler throw.
    assert.deepEqual(await ack('chat-message', 'Offline', { message: 'fixture' }), { success: false, error: 'Request failed.' });
    // Async create-agent rejects before its first await when given invalid data.
    assert.deepEqual(await ack('create-agent', null), { success: false, error: 'Request failed.' });
    // Missing callback and no-ack failure must also be contained.
    client.emit('get-settings', 'Offline');
    client.emit('chat-message', 'Offline', {});
    assert.equal((await ack('get-settings', 'Offline')).settings.profile.name, 'Offline');
    assert.equal(module.numStateListeners(), 0);
});

for (const kind of ['uncaughtException', 'unhandledRejection']) {
    test(`fatal ${kind} stops child agents and exits the parent with failure`, { timeout: 8000 }, t => {
        const before = process.env.ERROR_BOUNDARY_BASELINE;
        const f = mindserverFixture(t, before);
        const baselineMindcraft = before && path.join(before, 'mindcraft.js');
        copyFileSync(baselineMindcraft && existsSync(baselineMindcraft) ? baselineMindcraft : path.join(repo, 'src/mindcraft/mindcraft.js'), path.join(f.root, 'src/mindcraft/mindcraft.js'));
        mkdirSync(path.join(f.root, 'src/process'), { recursive: true });
        mkdirSync(path.join(f.root, 'src/runtime/fabric'), { recursive: true });
        writeFileSync(path.join(f.root, 'src/process/agent_process.js'), 'export class AgentProcess { constructor(name) { this.name=name; } start() { console.log("CHILD-START:" + this.name); } stop() { console.log("CHILD-STOP:" + this.name); } }');
        const failure = kind === 'uncaughtException' ? 'throw new Error("fixture fatal")' : 'Promise.reject(new Error("fixture fatal"))';
        writeFileSync(path.join(f.root, 'src/runtime/fabric/create_agent_runtime.js'), `export function prepareFabricRuntime(settings) { setTimeout(() => { ${failure}; }, 40); return Promise.resolve({settings}); }`);
        writeFileSync(path.join(f.root, 'settings.js'), 'export default { profiles:["./profiles/good.json"], mindserver_port:0, auto_open_ui:false };');
        const env = { ...process.env };
        for (const key of ['PROFILES', 'SETTINGS_JSON', 'MINDSERVER_PORT']) delete env[key];
        const result = spawnSync(process.execPath, ['main.js'], { cwd: f.root, env, encoding: 'utf8', timeout: 6000 });
        assert.equal(result.status, 1, `parent must exit nonzero, not hang: ${result.stderr}`);
        assert.match(result.stdout, /CHILD-START:Good/);
        assert.match(result.stdout, /CHILD-STOP:Good/);
        assert.match(result.stderr, new RegExp(kind));
    });
}
