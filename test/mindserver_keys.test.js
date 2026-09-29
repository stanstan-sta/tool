import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

async function setup(t) {
    const f = mindserverFixture(t, process.env.KEYS_BASELINE);
    f.write('keys.example.json', { OPENAI_API_KEY: '', ANTHROPIC_API_KEY: '' });
    f.write('keys.json', { OPENAI_API_KEY: 'prefix-synthetic-secret-suffix', LEGACY_CUSTOM: 'keep-custom' });
    const module = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    const server = module.createMindServer(false, 0);
    await once(server, 'listening');
    t.after(() => new Promise(resolve => module.getIO().close(resolve)));
    const headers = { Authorization: `Bearer ${module.getAccessToken()}`, 'content-type': 'application/json' };
    const url = `http://localhost:${server.address().port}/api/keys`;
    return { ...f, file: path.join(f.root, 'keys.json'), get: () => fetch(url, { headers }), post: body => fetch(url, { method: 'POST', headers, body: JSON.stringify(body) }) };
}

test('key metadata exposes only known names and constant masks, with no credential fragments', async t => {
    const f = await setup(t);
    const response = await f.get();
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { OPENAI_API_KEY: { set: true, mask: '***' }, ANTHROPIC_API_KEY: { set: false, mask: null } });
});

test('invalid key updates reject the entire request and preserve the stored file', async t => {
    const f = await setup(t);
    const before = fs.readFileSync(f.file, 'utf8');
    for (const body of [[], null, 'text', { UNKNOWN: 'value' }, { OPENAI_API_KEY: 123 }, { OPENAI_API_KEY: null },
        { OPENAI_API_KEY: 'new-value', ANTHROPIC_API_KEY: {} }, JSON.parse('{"__proto__":"bad"}'), { constructor: 'bad' }]) {
        assert.equal((await f.post(body)).status, 400, JSON.stringify(body));
        assert.equal(fs.readFileSync(f.file, 'utf8'), before);
    }
});

test('corrupt key storage is reported and never silently replaced', async t => {
    const f = await setup(t);
    for (const before of ['{broken', 'null', '[]', '{"OPENAI_API_KEY":123}']) {
        f.write('keys.json', before);
        assert.equal((await f.get()).status, 500);
        assert.equal((await f.post({ ANTHROPIC_API_KEY: 'fixture' })).status, 500);
        assert.equal(fs.readFileSync(f.file, 'utf8'), before);
    }
});

for (const operation of ['writeFileSync', 'renameSync']) {
    test(`failed atomic ${operation} preserves credentials and removes the temporary file`, async t => {
        const f = await setup(t);
        const before = fs.readFileSync(f.file, 'utf8');
        const original = fs[operation];
        const mocked = t.mock.method(fs, operation, (...args) => {
            if (String(args[0]).startsWith(f.file)) {
                if (operation === 'writeFileSync') original(args[0], 'partial-write', args[2]);
                throw Object.assign(new Error('fixture filesystem failure'), { code: 'EACCES' });
            }
            return original(...args);
        });
        syncBuiltinESMExports();
        try {
            assert.equal((await f.post({ OPENAI_API_KEY: 'replacement' })).status, 500);
            assert.equal(fs.readFileSync(f.file, 'utf8'), before);
            assert.deepEqual(fs.readdirSync(f.root).filter(name => name.startsWith('keys.json.') && name.endsWith('.tmp')), []);
        } finally {
            mocked.mock.restore();
            syncBuiltinESMExports();
        }
    });
}

test('valid saves preserve legacy entries, support deletion and create missing storage', async t => {
    const f = await setup(t);
    assert.equal((await f.post({ OPENAI_API_KEY: '', ANTHROPIC_API_KEY: 'new-fixture' })).status, 200);
    assert.deepEqual(JSON.parse(fs.readFileSync(f.file, 'utf8')), { LEGACY_CUSTOM: 'keep-custom', ANTHROPIC_API_KEY: 'new-fixture' });
    if (process.platform !== 'win32') assert.equal(fs.statSync(f.file).mode & 0o777, 0o600);
    fs.unlinkSync(f.file);
    assert.equal((await f.post({ OPENAI_API_KEY: 'created-fixture' })).status, 200);
    assert.deepEqual(JSON.parse(fs.readFileSync(f.file, 'utf8')), { OPENAI_API_KEY: 'created-fixture' });
});
