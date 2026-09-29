import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { io } from 'socket.io-client';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

async function setup(t, profilePath = './profiles/Alpha.json') {
    const f = mindserverFixture(t, process.env.PROFILE_BASELINE);
    f.write('profiles/Alpha.json', { name: 'Alpha', model: 'old' });
    const module = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    module.registerAgent({ profile: { name: 'Alpha', model: 'old' }, profile_path: profilePath });
    const server = module.createMindServer(false, 0);
    await once(server, 'listening');
    const base = `http://localhost:${server.address().port}`;
    const token = module.getAccessToken?.() || 'baseline';
    const headers = { Authorization: `Bearer ${token}` };
    const client = io(base, { transports: ['websocket'], reconnection: false, auth: { token } });
    t.after(() => { client.disconnect(); return new Promise(resolve => module.getIO().close(resolve)); });
    await once(client, 'connect');
    const ack = (event, ...args) => client.timeout(1500).emitWithAck(event, ...args);
    const put = (name, profile) => fetch(`${base}/api/profiles/${encodeURIComponent(name)}`, {
        method: 'PUT', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify(profile),
    });
    return { ...f, base, put, ack, module, headers };
}

test('profile PUT rejects changing the name without changing disk or live settings', async t => {
    const f = await setup(t);
    const before = readFileSync(path.join(f.root, 'profiles/Alpha.json'), 'utf8');
    assert.equal((await f.put('Alpha', { name: 'Beta', model: 'new' })).status, 400);
    assert.equal(readFileSync(path.join(f.root, 'profiles/Alpha.json'), 'utf8'), before);
    assert.equal((await f.ack('get-settings', 'Alpha')).settings.profile.model, 'old');
});

test('profile PUT rejects an existing inconsistent file identity', async t => {
    const f = await setup(t);
    f.write('profiles/Alpha.json', { name: 'Wrong', model: 'old' });
    assert.equal((await f.put('Alpha', { name: 'Alpha', model: 'new' })).status, 409);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/Alpha.json'), 'utf8')).name, 'Wrong');
});

test('profile PUT updates the matching relative-path agent and not another file', async t => {
    const f = await setup(t);
    assert.equal((await f.put('Alpha', { name: 'Alpha', model: 'new' })).status, 200);
    assert.equal((await f.ack('get-settings', 'Alpha')).settings.profile.model, 'new');
    f.module.registerAgent({ profile: { name: 'Alpha', model: 'separate' }, profile_path: './another/Alpha.json' });
    assert.equal((await f.put('Alpha', { name: 'Alpha', model: 'newer' })).status, 200);
    assert.equal((await f.ack('get-settings', 'Alpha')).settings.profile.model, 'separate');
});

test('profile DELETE cannot bypass in-use protection with a relative path', async t => {
    const f = await setup(t);
    assert.equal((await fetch(`${f.base}/api/profiles/Alpha`, { method: 'DELETE', headers: f.headers })).status, 409);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/Alpha.json'), 'utf8')).name, 'Alpha');
});

test('settings update cannot rename an existing agent', async t => {
    const f = await setup(t);
    const response = await f.ack('set-agent-settings', 'Alpha', { profile: { name: 'Beta', model: 'new' } });
    assert.equal(response.success, false);
    assert.match(response.error, /rename/);
    assert.deepEqual((await f.ack('get-settings', 'Alpha')).settings.profile, { name: 'Alpha', model: 'old' });
});

test('failed profile save leaves live settings unchanged; successful save applies them', async t => {
    const f = await setup(t);
    // Existing directory as destination forces a real filesystem write error.
    f.module.registerAgent({ profile: { name: 'Alpha', model: 'old' }, profile_path: path.join(f.root, 'profiles') });
    assert.equal((await f.ack('set-agent-settings', 'Alpha', { profile: { name: 'Alpha', model: 'new' } })).success, false);
    assert.equal((await f.ack('get-settings', 'Alpha')).settings.profile.model, 'old');
    f.module.registerAgent({ profile: { name: 'Alpha', model: 'old' }, profile_path: path.join(f.root, 'profiles/Alpha.json') });
    assert.equal((await f.ack('set-agent-settings', 'Alpha', { profile: { name: 'Alpha', model: 'new' } })).success, true);
    assert.equal((await f.ack('get-settings', 'Alpha')).settings.profile.model, 'new');
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/Alpha.json'), 'utf8')).model, 'new');
});
