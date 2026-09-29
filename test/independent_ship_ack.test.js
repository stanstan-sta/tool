import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { io as createClient } from 'socket.io-client';
import { createMindServer, getIO, getServer, numStateListeners, registerAgent, getAccessToken } from '../src/mindcraft/mindserver.js';

function withTimeout(promise, ms, label) {
    let timer = null;
    const guard = new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out waiting for ${label} after ${ms}ms`)), ms);
    });
    return Promise.race([promise, guard]).then(
        (v) => { clearTimeout(timer); return v; },
        (e) => { clearTimeout(timer); throw e; }
    );
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function serverAckCount() {
    let total = 0;
    const sockets = getIO()?.sockets?.sockets;
    if (!sockets) return 0;
    for (const s of sockets.values()) {
        if (s.acks instanceof Map) total += s.acks.size;
    }
    return total;
}

test('independent adversarial: duplicate listeners, stale-disconnect clobber, late ack single-delivery', async (t) => {
    const realSetInterval = global.setInterval;
    const capturedIntervals = new Set();
    // @ts-ignore
    global.setInterval = (...args) => {
        const id = realSetInterval(...args);
        capturedIntervals.add(id);
        return id;
    };
    const failures = [];
    const note = (cond, msg) => { console.log((cond ? 'PASS: ' : 'FAIL: ') + msg); if (!cond) failures.push(msg); };

    const suffix = `${process.pid}-${Date.now()}-ind`;
    const dupAgent = `dupagent-${suffix}`;
    const replAgent = `replagent-${suffix}`;
    const slowAgent = `slowagent-${suffix}`;
    for (const n of [dupAgent, replAgent, slowAgent]) registerAgent({ profile: { name: n } });

    createMindServer(false, 0);
    const server = getServer();
    await withTimeout(once(server, 'listening'), 5000, 'server listening');
    const baseUrl = `http://localhost:${server.address().port}`;
    const clients = [];
    const connect = async () => {
        const c = createClient(baseUrl, { transports: ['websocket'], reconnection: false, auth: { token: getAccessToken() } });
        clients.push(c);
        await withTimeout(once(c, 'connect'), 5000, 'client connect');
        return c;
    };
    t.after(async () => {
        for (const c of clients) { try { c.disconnect(); } catch { /* ignore */ } }
        await sleep(300);
        for (const id of capturedIntervals) { try { clearInterval(id); } catch { /* ignore */ } }
        global.setInterval = realSetInterval;
        try {
            await withTimeout(new Promise((resolve) => getIO().close(resolve)), 4000, 'server close');
        } catch (e) { console.log('server close issue (non-fatal): ' + e.message); }
    });

    // --- Phase A: stale disconnect must not clobber replacement (no listeners active) ---
    try {
        const oldSock = await connect();
        oldSock.on('get-full-state', (ack) => { try { ack({ gen: 1 }); } catch { /* ignore */ } });
        oldSock.on('get-agent-memory', (ack) => { try { ack('old'); } catch { /* ignore */ } });
        oldSock.emit('login-agent', replAgent);
        await sleep(150);
        const newSock = await connect();
        newSock.on('get-full-state', (ack) => { try { ack({ gen: 2 }); } catch { /* ignore */ } });
        newSock.on('get-agent-memory', (ack) => { try { ack('new'); } catch { /* ignore */ } });
        newSock.emit('login-agent', replAgent);
        await sleep(150);
        oldSock.disconnect(); // stale socket goes away AFTER replacement
        await sleep(300);
        const probe = await connect();
        const memRes = await withTimeout(
            new Promise((resolve) => probe.emit('get-agent-memory', replAgent, resolve)),
            4000, 'post-replacement memory'
        );
        console.log(`REPLACEMENT-PROBE memRes=${JSON.stringify(memRes)}`);
        note(memRes.success === true && memRes.memory === 'new',
            `replacement socket must survive stale disconnect (got ${JSON.stringify(memRes)})`);
    } catch (e) { note(false, 'replacement probe completed: ' + e.message); }

    // --- Phase B: late ack after timeout delivers exactly once, no ack leak (no listeners active) ---
    try {
        const slow = await connect();
        slow.on('get-agent-memory', (ack) => {
            setTimeout(() => { try { ack('late-mem'); } catch { /* ignore */ } }, 1600);
        });
        slow.emit('login-agent', slowAgent);
        await sleep(150);
        assert.equal(numStateListeners(), 0, 'precondition: no listeners during ack-leak probe');
        const ui2 = await connect();
        let callbacks = 0;
        let firstRes = null;
        await withTimeout(new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('no callback for slow memory')), 4000);
            ui2.emit('get-agent-memory', slowAgent, (r) => {
                callbacks++;
                if (callbacks === 1) { firstRes = r; clearTimeout(timer); resolve(); }
            });
        }), 5000, 'slow memory first callback');
        note(firstRes && firstRes.success === false && /timed out/i.test(firstRes.error || ''),
            `slow agent must time out (got ${JSON.stringify(firstRes)})`);
        await sleep(1400); // allow late ack to arrive + internal cleanup
        note(callbacks === 1, `late ack must not invoke UI callback twice (got ${callbacks})`);
        await sleep(400);
        const leaked = serverAckCount();
        console.log(`ACK-LEAK-PROBE acks=${leaked}`);
        note(leaked === 0, `late ack must not retain Socket.IO ack entries (got ${leaked})`);
    } catch (e) { note(false, 'late-ack probe completed: ' + e.message); }

    // --- Phase C (LAST): duplicate subscriptions on one socket ---
    // Runs last because the leak it demonstrates would otherwise pollute
    // earlier ack measurements with background poll traffic.
    try {
        const agent = await connect();
        agent.on('get-full-state', (ack) => { try { ack({ ok: 1 }); } catch { /* ignore */ } });
        agent.emit('login-agent', dupAgent);
        await sleep(150);
        const ui = await connect();
        let updates = 0;
        ui.on('state-update', () => { updates++; });
        ui.emit('listen-to-agents');
        await sleep(150);
        ui.emit('listen-to-agents'); // duplicate subscribe, same socket
        await sleep(150);
        const afterDup = numStateListeners();
        updates = 0;
        try {
            await withTimeout((async () => {
                const deadline = Date.now() + 4000;
                while (updates < 1 && Date.now() < deadline) await sleep(100);
                if (updates < 1) throw new Error('no state-update arrived');
            })(), 5000, 'state-update after dup subscribe');
        } catch (e) { note(false, 'state-update arrived after dup subscribe: ' + e.message); }
        const deliveredAfterDup = updates;
        console.log(`DUPLICATE-PROBE afterDup=${afterDup} delivered=${deliveredAfterDup}`);
        note(afterDup === 1, `duplicate listen-to-agents on same socket must not double-register (got ${afterDup})`);
        note(deliveredAfterDup <= 1, `one poll tick must deliver at most one state-update per socket (got ${deliveredAfterDup} in window)`);
        ui.disconnect();
        await sleep(400);
        const afterOneDisconnect = numStateListeners();
        console.log(`DUPLICATE-PROBE afterOneDisconnect=${afterOneDisconnect}`);
        note(afterOneDisconnect === 0, `disconnect must remove all registrations for socket (got ${afterOneDisconnect})`);
    } catch (e) { note(false, 'duplicate probe completed: ' + e.message); }

    assert.deepEqual(failures, [], `adversarial findings:\n- ${failures.join('\n- ')}`);
});
