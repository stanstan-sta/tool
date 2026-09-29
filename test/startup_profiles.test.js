import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { mindserverFixture } from './helpers/mindserver_fixture.js';
import { once } from 'node:events';
import { spawnSync } from 'node:child_process';




test('startup API validates the whole list before persisting and preserves legacy profiles', { timeout: 15000 }, async t => {
    const f = mindserverFixture(t, process.env.STARTUP_BASELINE);
    const { createMindServer, getIO, getAccessToken } = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    const server = createMindServer(false, 0);
    await once(server, 'listening');
    t.after(() => new Promise(resolve => getIO().close(resolve)));
    const endpoint = `http://localhost:${server.address().port}/api/startup-profiles`;
    const headers = { Authorization: `Bearer ${getAccessToken?.() || 'baseline'}` };
    const put = profiles => fetch(endpoint, { method: 'PUT', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify(profiles) });
    const response = await put(['./profiles/good.json', './miku.json']);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).profiles, ['./profiles/good.json', './miku.json']);
    const persisted = readFileSync(path.join(f.root, 'settings_local.json'), 'utf8');
    const cases = [
        ['outside relative', '../outside.json'],
        ['outside absolute', path.join(f.temporary, 'outside.json')],
        ['missing', './profiles/missing.json'],
        ['malformed', './profiles/bad.json'],
        ['invalid profile', './profiles/invalid.json'],
        ['reserved root file', './keys.json'],
        ['empty', ''],
        ['not a string', 4],
    ];
    mkdirSync(path.join(f.temporary, 'external'), { recursive: true });
    writeFileSync(path.join(f.temporary, 'external/escape.json'), '{"name":"Escape","model":"fixture"}');
    symlinkSync(path.join(f.temporary, 'external'), path.join(f.root, 'profiles/linked'), 'junction');
    cases.push(['junction escape', './profiles/linked/escape.json']);
    for (const [name, invalid] of cases) {
        await t.test(name, async () => {
            assert.equal((await put(['./profiles/good.json', invalid])).status, 400);
            assert.equal(readFileSync(path.join(f.root, 'settings_local.json'), 'utf8'), persisted);
            assert.deepEqual(await (await fetch(endpoint, { headers })).json(), ['./profiles/good.json', './miku.json']);
        });
    }
    assert.equal((await put({ profiles: [] })).status, 400);
    const normalized = await put(['profiles/../profiles/good.json', './miku.json', './profiles/good.json']);
    assert.deepEqual((await normalized.json()).profiles, ['./profiles/good.json', './miku.json']);
    assert.equal((await put([])).status, 200);
    assert.deepEqual(await (await fetch(endpoint, { headers })).json(), []);
});

test('startup skips corrupt/missing saved profiles and never loads an external saved path', t => {
    const f = mindserverFixture(t, process.env.STARTUP_BASELINE);
    f.write('settings_local.json', { profiles: ['../outside.json', './profiles/bad.json', './profiles/missing.json', './profiles/invalid.json', './profiles/good.json', './miku.json'] });
    const env = { ...process.env };
    delete env.PROFILES;
    delete env.SETTINGS_JSON;
    const result = spawnSync(process.execPath, ['main.js'], { cwd: f.root, env, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /START:Good/);
    assert.match(result.stdout, /START:Legacy/);
    assert.doesNotMatch(result.stdout, /START:Outside/);
    assert.match(result.stderr, /invalid saved startup profile/);
});

test('explicit CLI profiles retain external-file support and skip a broken file', t => {
    const f = mindserverFixture(t, process.env.STARTUP_BASELINE);
    const env = { ...process.env };
    delete env.PROFILES;
    delete env.SETTINGS_JSON;
    const result = spawnSync(process.execPath, ['main.js', '--profiles', './profiles/bad.json', path.join(f.temporary, 'outside.json'), './profiles/good.json'], { cwd: f.root, env, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /START:Outside/);
    assert.match(result.stdout, /START:Good/);
    assert.match(result.stderr, /Skipping invalid startup profile/);
});
