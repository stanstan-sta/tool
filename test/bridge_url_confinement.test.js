import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { io } from 'socket.io-client';
import { FabricBridge, normalizeBridgeUrl, resolveBridgeUrl } from '../src/bridge/fabric_bridge.js';
import { mindserverFixture } from './helpers/mindserver_fixture.js';

// A15: a persisted bridge_url must not turn Node into a request primitive
// against arbitrary remote/private hosts. Loopback + port selection keep
// working; redirects are never followed.
test('normalizeBridgeUrl keeps loopback with port selection', () => {
    assert.equal(normalizeBridgeUrl('http://localhost:8765'), 'http://localhost:8765');
    assert.equal(normalizeBridgeUrl('http://localhost:8765/'), 'http://localhost:8765');
    assert.equal(normalizeBridgeUrl('http://127.0.0.1:9999'), 'http://127.0.0.1:9999');
    assert.equal(normalizeBridgeUrl('http://[::1]:8765'), 'http://[::1]:8765');
    assert.equal(normalizeBridgeUrl('http://localhost'), 'http://localhost');
});

test('normalizeBridgeUrl rejects remote, private, credentialed, and non-http URLs', () => {
    for (const bad of [
        'http://example.com:8765',
        'http://192.168.1.10:8765',
        'http://10.0.0.5/',
        'http://169.254.169.254/',
        'https://localhost:8765',
        'http://user:pass@localhost:8765',
        'http://localhost.example.com/',
        '',
        'not a url',
        'file:///etc/passwd',
    ]) {
        assert.throws(() => normalizeBridgeUrl(bad), /Bridge URL must/, `accepted: ${bad}`);
    }
});

test('FabricBridge constructor confines its fetch base', () => {
    assert.equal(new FabricBridge('http://127.0.0.1:1234').url, 'http://127.0.0.1:1234');
    assert.throws(() => new FabricBridge('http://192.168.0.1:8765'), /Bridge URL must/);
});

test('normalizeBridgeUrl rejects query strings instead of folding them into fetch paths', () => {
    for (const bad of [
        'http://localhost:8765?evil=1',
        'http://localhost:8765?token=secret',
        'http://127.0.0.1:8765/?since=1&drain=true',
        'http://localhost:8765/api?x=1',
    ]) {
        assert.throws(() => normalizeBridgeUrl(bad), /query/, `accepted: ${bad}`);
    }
    // A clean base composes with endpoint paths without query confusion.
    assert.equal(`${normalizeBridgeUrl('http://localhost:8765')}/ping`, 'http://localhost:8765/ping');
    assert.equal(`${normalizeBridgeUrl('http://localhost:8765/api/')}/ping`, 'http://localhost:8765/api/ping');
    assert.throws(() => new FabricBridge('http://localhost:8765?x=1'), /query/);
});

test('resolveBridgeUrl fails loudly on configured-invalid URLs, defaults only when absent', () => {
    assert.equal(resolveBridgeUrl(undefined), 'http://localhost:8765');
    assert.equal(resolveBridgeUrl(null), 'http://localhost:8765');
    assert.equal(resolveBridgeUrl(''), 'http://localhost:8765');
    assert.equal(resolveBridgeUrl('   '), 'http://localhost:8765');
    assert.equal(resolveBridgeUrl('http://127.0.0.1:9999'), 'http://127.0.0.1:9999');
    // An invalid persisted/configured URL must throw, never silently return
    // the local default (a different endpoint).
    for (const bad of [
        'http://192.168.1.10:8765',
        'http://example.com:8765',
        'https://localhost:8765',
        'http://user:pass@localhost:8765',
        'http://localhost:8765?evil=1',
        'not a url',
        'file:///etc/passwd',
    ]) {
        assert.throws(() => resolveBridgeUrl(bad), /Bridge URL must/, `fell back to default: ${bad}`);
    }
});

test('bridge fetches never follow redirects', async () => {
    const redirector = http.createServer((req, res) => {
        res.writeHead(302, { Location: 'http://127.0.0.1:9/collect' });
        res.end();
    });
    redirector.listen(0, '127.0.0.1');
    await once(redirector, 'listening');
    const plain = http.createServer((req, res) => {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{"ok":true}');
    });
    plain.listen(0, '127.0.0.1');
    await once(plain, 'listening');
    try {
        const followed = new FabricBridge(`http://127.0.0.1:${redirector.address().port}`);
        // redirect:'error' rejects instead of following the Location.
        assert.equal(await followed.isReachable(), false);
        const direct = new FabricBridge(`http://127.0.0.1:${plain.address().port}`);
        assert.equal(await direct.isReachable(), true);
    } finally {
        redirector.close();
        plain.close();
    }
});

async function agentSetup(t) {
    const f = mindserverFixture(t, process.env.PROFILE_BASELINE);
    f.write('profiles/Alpha.json', { name: 'Alpha', model: 'old' });
    const module = await import(pathToFileURL(path.join(f.root, 'src/mindcraft/mindserver.js')));
    module.registerAgent({ profile: { name: 'Alpha', model: 'old' }, profile_path: './profiles/Alpha.json' });
    const server = module.createMindServer(false, 0);
    await once(server, 'listening');
    const base = `http://localhost:${server.address().port}`;
    const token = module.getAccessToken?.() || 'baseline';
    const client = io(base, { transports: ['websocket'], reconnection: false, auth: { token } });
    t.after(() => { client.disconnect(); return new Promise(resolve => module.getIO().close(resolve)); });
    await once(client, 'connect');
    const ack = (event, ...args) => client.timeout(1500).emitWithAck(event, ...args);
    return { ack };
}

test('set-agent-settings rejects a non-loopback bridge_url and keeps the old value', async t => {
    const f = await agentSetup(t);
    const bad = await f.ack('set-agent-settings', 'Alpha', { bridge_url: 'http://192.168.1.10:8765' });
    assert.equal(bad.success, false);
    assert.match(bad.error, /localhost|loopback|\[::1\]/);
    const settings = await f.ack('get-settings', 'Alpha');
    assert.equal(settings.settings.bridge_url, undefined);
    const good = await f.ack('set-agent-settings', 'Alpha', { bridge_url: 'http://127.0.0.1:9999' });
    assert.equal(good.success, true);
    assert.equal((await f.ack('get-settings', 'Alpha')).settings.bridge_url, 'http://127.0.0.1:9999');
});
