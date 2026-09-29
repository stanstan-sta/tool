import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { FabricBridge } from '../src/bridge/fabric_bridge.js';

// A10/A12 client side: bearer transport on every non-liveness endpoint,
// loud 401 surfacing, and explicit drain only for the observation poll.
function ephemeral(handler) {
    const seen = [];
    const server = http.createServer((req, res) => {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', () => {
            seen.push({ url: req.url, method: req.method, authorization: req.headers.authorization || null });
            handler(req, res, body);
        });
    });
    return { server, seen };
}

async function listen(server) {
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return server.address().port;
}

test('client sends the bearer token on every non-liveness endpoint', async t => {
    const { server, seen } = ephemeral((req, res) => {
        if (req.url.startsWith('/screenshot')) {
            res.writeHead(200, { 'content-type': 'image/jpeg' });
            res.end(Buffer.from([1, 2, 3]));
            return;
        }
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{"ok":true,"success":true}');
    });
    const port = await listen(server);
    t.after(() => server.close());
    const bridge = new FabricBridge(`http://127.0.0.1:${port}`, { token: 'secret-token' });
    await bridge.sendCommand('#sleep');
    await bridge.sendAction({ type: 'ping' });
    await bridge.getCapabilities();
    await bridge.getQueueState();
    await bridge.getCommands();
    await bridge.getState(1);
    await bridge.readBlocks({ x: 0, y: 0, z: 0, w: 1, h: 1, l: 1 });
    await bridge.getScreenshot();
    await bridge.cancelQueue();
    assert.ok(seen.length >= 9);
    for (const call of seen) {
        assert.equal(call.authorization, 'Bearer secret-token', `missing auth on ${call.method} ${call.url}`);
    }
});

test('liveness ping sends no credentials', async t => {
    const { server, seen } = ephemeral((req, res) => {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{"ok":true}');
    });
    const port = await listen(server);
    t.after(() => server.close());
    const bridge = new FabricBridge(`http://127.0.0.1:${port}`, { token: 'secret-token' });
    assert.equal(await bridge.isReachable(), true);
    assert.equal(seen[0].authorization, null);
});

test('observation poll drains explicitly while peek reads do not', async t => {
    const { server, seen } = ephemeral((req, res) => {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{"connected":false}');
    });
    const port = await listen(server);
    t.after(() => server.close());
    const bridge = new FabricBridge(`http://127.0.0.1:${port}`, { token: 't' });
    await bridge.getState(7);
    assert.match(seen.at(-1).url, /drain=true/);
    await bridge.getState(null, { drainChat: false });
    assert.match(seen.at(-1).url, /peek=true/);
    assert.doesNotMatch(seen.at(-1).url, /drain=true/);
});

test('401 surfaces an actionable error and flags the bridge', async t => {
    const { server } = ephemeral((req, res) => {
        res.writeHead(401, { 'content-type': 'application/json' });
        res.end('{"success":false,"error":"unauthorized_bridge_token"}');
    });
    const port = await listen(server);
    t.after(() => server.close());
    const bridge = new FabricBridge(`http://127.0.0.1:${port}`, { token: 'wrong' });
    const result = await bridge.sendCommand('#sleep');
    assert.equal(result.success, false);
    assert.match(result.error, /FABRIC_BRIDGE_TOKEN/);
    assert.equal(bridge.authFailed, true);
    assert.equal(await bridge.getQueueState(), null);
});
