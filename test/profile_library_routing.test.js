import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync, mkdirSync, renameSync, symlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { io } from 'socket.io-client';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

// Profile library routing: GET /api/profiles lists profiles/*.json plus legacy
// root files (miku.json) and files whose stored name differs from the filename.
// Read/update/delete must resolve that same actual confined file, and duplicate
// stored names must fail explicitly rather than editing the wrong file.
async function setup(t) {
    const f = mindserverFixture(t, process.env.PROFILE_BASELINE);
    const module = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    const server = module.createMindServer(false, 0);
    await once(server, 'listening');
    const base = `http://localhost:${server.address().port}`;
    const token = module.getAccessToken?.() || 'baseline';
    const headers = { Authorization: `Bearer ${token}` };
    const client = io(base, { transports: ['websocket'], reconnection: false, auth: { token } });
    t.after(() => { client.disconnect(); return new Promise(resolve => module.getIO().close(resolve)); });
    await once(client, 'connect');
    const get = name => fetch(`${base}/api/profiles/${encodeURIComponent(name)}`, { headers });
    const put = (name, profile) => fetch(`${base}/api/profiles/${encodeURIComponent(name)}`, {
        method: 'PUT', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify(profile),
    });
    const del = name => fetch(`${base}/api/profiles/${encodeURIComponent(name)}`, { method: 'DELETE', headers });
    const post = profile => fetch(`${base}/api/profiles`, {
        method: 'POST', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify(profile),
    });
    const ack = (event, ...args) => client.timeout(2000).emitWithAck(event, ...args);
    return { ...f, base, get, put, del, post, ack, module, headers };
}

function redirectProfiles(f, target) {
    const source = path.resolve(f.root, 'profiles');
    const backup = path.resolve(f.root, 'original-profiles');
    assert.equal(path.dirname(source), path.resolve(f.root));
    assert.equal(path.dirname(backup), path.resolve(f.root));
    renameSync(source, backup);
    symlinkSync(target, source, 'junction');
}

test('profile creation cannot overwrite a file through an outside directory junction', async t => {
    const f = await setup(t);
    const outside = path.join(f.temporary, 'outside-profiles');
    mkdirSync(outside);
    const file = path.join(outside, 'Outside.json');
    const before = JSON.stringify({ name: 'Outside', model: 'preserved' });
    writeFileSync(file, before);
    redirectProfiles(f, outside);
    assert.ok([400, 409].includes((await f.post({ name: 'Outside', model: 'overwritten' })).status));
    assert.equal(readFileSync(file, 'utf8'), before);
});

test('profile routing and listing exclude reserved files behind a directory alias', async t => {
    const f = await setup(t);
    redirectProfiles(f, f.root);
    assert.ok([400, 404].includes((await f.get('NotAProfile')).status));
    const listed = await (await fetch(`${f.base}/api/profiles`, { headers: f.headers })).json();
    assert.ok(listed.every(profile => profile.name !== 'NotAProfile'));
});

test('launch cannot create a new profile through an outside directory junction', async t => {
    const f = await setup(t);
    const outside = path.join(f.temporary, 'outside-profiles');
    mkdirSync(outside);
    redirectProfiles(f, outside);
    const result = await f.ack('create-agent', { profile: { name: 'Fresh', model: 'fixture' } });
    assert.equal(result.success, false);
    assert.equal(existsSync(path.join(outside, 'Fresh.json')), false);
});

test('GET resolves a legacy root profile by stored name', async t => {
    const f = await setup(t);
    // Fixture root miku.json stores name 'Legacy'.
    const res = await f.get('Legacy');
    assert.equal(res.status, 200);
    assert.equal((await res.json()).name, 'Legacy');
});

test('GET resolves a profiles file whose stored name differs from its filename', async t => {
    const f = await setup(t);
    // Fixture profiles/good.json stores name 'Good'.
    const res = await f.get('Good');
    assert.equal(res.status, 200);
    assert.equal((await res.json()).name, 'Good');
});

test('duplicate stored names fail explicitly without touching either file', async t => {
    const f = await setup(t);
    f.write('profiles/dup_a.json', { name: 'Dup', model: 'a' });
    f.write('profiles/dup_b.json', { name: 'Dup', model: 'b' });
    assert.equal((await f.get('Dup')).status, 409);
    assert.equal((await f.put('Dup', { name: 'Dup', model: 'new' })).status, 409);
    assert.equal((await f.del('Dup')).status, 409);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/dup_a.json'), 'utf8')).model, 'a');
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/dup_b.json'), 'utf8')).model, 'b');
});

test('PUT updates the legacy root file and its live agent', async t => {
    const f = await setup(t);
    f.module.registerAgent({ profile: { name: 'Legacy', model: 'old' }, profile_path: './miku.json' });
    const res = await f.put('Legacy', { name: 'Legacy', model: 'new' });
    assert.equal(res.status, 200);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'miku.json'), 'utf8')).model, 'new');
    assert.equal(existsSync(path.join(f.root, 'profiles/Legacy.json')), false);
});

test('DELETE removes the legacy root file and its startup entry', async t => {
    const f = await setup(t);
    const settingsMod = await import(pathToFileURL(path.join(f.root, 'settings.js')));
    settingsMod.default.profiles = ['./miku.json', './profiles/good.json'];
    const res = await f.del('Legacy');
    assert.equal(res.status, 200);
    assert.equal(existsSync(path.join(f.root, 'miku.json')), false);
    assert.deepEqual(JSON.parse(readFileSync(path.join(f.root, 'settings_local.json'), 'utf8')).profiles, ['./profiles/good.json']);
});

test('POST refuses a name already used by a root or mismatched file', async t => {
    const f = await setup(t);
    assert.equal((await f.post({ name: 'Legacy', model: 'x' })).status, 409);
    assert.equal((await f.post({ name: 'Good', model: 'x' })).status, 409);
    assert.equal(existsSync(path.join(f.root, 'profiles/Legacy.json')), false);
});

test('reserved root files and traversal input never resolve to a profile', async t => {
    const f = await setup(t);
    // Fixture keys.json looks like a profile but is a reserved credential file.
    assert.equal((await f.get('NotAProfile')).status, 404);
    // '../keys' sanitizes to 'keys', which is reserved and never resolves.
    assert.equal((await f.get('../keys')).status, 404);
    assert.equal((await f.get('no-such-profile')).status, 404);
});

test('separator, nested-dot and case-variant escapes never resolve to a profile', async t => {
    const f = await setup(t);
    // Note: '.' is omitted: URL path normalization maps /api/profiles/.
    // to the list endpoint (200 with the profile array), which resolves no
    // file. Every entry below must resolve to no file.
    for (const hostile of ['..\\keys', '....', '...', '..', 'keys', 'KEYS', 'Keys', 'keys.json', 'profiles/good', 'good.json']) {
        const res = await f.get(hostile);
        assert.ok(res.status === 404 || res.status === 400, `${hostile} resolved with ${res.status}`);
    }
    // Single-encoded traversal sent raw (not double-encoded): still confined.
    const raw = await fetch(`${f.base}/api/profiles/..%2Fkeys`, { headers: f.headers });
    assert.ok(raw.status === 404 || raw.status === 400, `..%2Fkeys resolved with ${raw.status}`);
    // Reserved and library files are untouched; legitimate names still work.
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'keys.json'), 'utf8')).name, 'NotAProfile');
    assert.equal((await f.get('Good')).status, 200);
    assert.equal((await f.get('Legacy')).status, 200);
    assert.equal((await f.get('miku')).status, 200);
});

test('launching a legacy root profile reuses miku.json without duplicating it', async t => {
    const f = await setup(t);
    const prof = await (await f.get('Legacy')).json();
    const ack = await f.ack('create-agent', { profile: prof, launch_mode: 'fabric_ui' });
    assert.equal(ack.success, true);
    assert.equal(existsSync(path.join(f.root, 'profiles/Legacy.json')), false);
    // Read/edit still resolve the single root file instead of hitting a
    // duplicate-name conflict.
    assert.equal((await f.get('Legacy')).status, 200);
    const put = await f.put('Legacy', { name: 'Legacy', model: 'launched' });
    assert.equal(put.status, 200);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'miku.json'), 'utf8')).model, 'launched');
});

test('launching a stored-name-mismatched profile creates no duplicate file', async t => {
    const f = await setup(t);
    const { readdirSync } = await import('node:fs');
    const before = readdirSync(path.join(f.root, 'profiles'));
    const prof = await (await f.get('Good')).json();
    const ack = await f.ack('create-agent', { profile: prof, launch_mode: 'fabric_ui' });
    assert.equal(ack.success, true);
    // No case-variant duplicate (e.g. profiles/Good.json next to good.json
    // on case-sensitive filesystems); the launch reuses the listed file.
    const after = readdirSync(path.join(f.root, 'profiles'));
    assert.deepEqual(after, before);
    assert.equal((await f.get('Good')).status, 200);
});

test('launching an ambiguous duplicate name fails instead of using the wrong file', async t => {
    const f = await setup(t);
    f.write('profiles/dup_a.json', { name: 'Dup', model: 'a' });
    f.write('profiles/dup_b.json', { name: 'Dup', model: 'b' });
    const ack = await f.ack('create-agent', { profile: { name: 'Dup', model: 'x' }, launch_mode: 'fabric_ui' });
    assert.equal(ack.success, false);
    assert.match(ack.error, /Multiple profiles|Rename/);
    assert.equal(existsSync(path.join(f.root, 'profiles/Dup.json')), false);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/dup_a.json'), 'utf8')).model, 'a');
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/dup_b.json'), 'utf8')).model, 'b');
});

test('launching a genuinely new profile still creates its library file', async t => {
    const f = await setup(t);
    const ack = await f.ack('create-agent', { profile: { name: 'Fresh', model: 'x' }, launch_mode: 'fabric_ui' });
    assert.equal(ack.success, true);
    assert.equal(JSON.parse(readFileSync(path.join(f.root, 'profiles/Fresh.json'), 'utf8')).name, 'Fresh');
});
