import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { FabricBridge } from '../src/bridge/fabric_bridge.js';

for (const method of ['skipQueue', 'resumeQueue']) {
    test(`${method} sends its generation and surfaces stale rejection`, async t => {
        const requests = [];
        const server = http.createServer(async (req, res) => {
            let body = '';
            for await (const chunk of req) body += chunk;
            requests.push({ url: req.url, contentType: req.headers['content-type'], body });
            res.writeHead(body.includes('"generation":7') ? 409 : 200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ success: !body.includes('"generation":7') }));
        });
        server.listen(0, '127.0.0.1');
        await once(server, 'listening');
        t.after(() => new Promise(resolve => server.close(resolve)));
        const bridge = new FabricBridge(`http://127.0.0.1:${server.address().port}`);
        assert.deepEqual(await bridge[method](7), { success: false, error: 'HTTP 409' });
        assert.deepEqual(await bridge[method](8), { success: true });
        assert.equal((await bridge[method]()).success, true);
        assert.equal(requests[0].url, method === 'skipQueue' ? '/queue/skip' : '/queue/resume');
        assert.equal(requests[0].contentType, 'application/json');
        assert.deepEqual(JSON.parse(requests[0].body), { generation: 7 });
        assert.deepEqual(JSON.parse(requests[1].body), { generation: 8 });
        assert.equal(requests[2].body, '');
    });
}
