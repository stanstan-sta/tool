import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { io as createClient } from 'socket.io-client';
import { createMindServer, getIO, getServer, numStateListeners, registerAgent, getAccessToken } from '../src/mindcraft/mindserver.js';

function withTimeout(promise, ms, label) {
    let timer = null;
    const guard = new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out waiting for ${label} after ${ms}ms`)), ms);
    });
    return Promise.race([promise, guard]).then(
        (value) => {
            clearTimeout(timer);
            return value;
        },
        (error) => {
            clearTimeout(timer);
            throw error;
        }
    );
}

function serverAckCount() {
    let total = 0;
    const sockets = getIO().sockets.sockets;
    for (const socket of sockets.values()) {
        if (socket.acks instanceof Map) total += socket.acks.size;
    }
    return total;
}

test('agent acknowledgements are bounded and listener polls are isolated across stop and restart', async (t) => {
    const suffix = `${process.pid}-${Date.now()}`;
    const missingName = `missing-${suffix}`;
    const healthyName = `healthy-${suffix}`;
    const lateName = `late-${suffix}`;
    const replacedName = `replaced-${suffix}`;
    for (const name of [missingName, healthyName, lateName, replacedName]) registerAgent({ profile: { name } });

    createMindServer(false, 0);
    const server = getServer();
    await withTimeout(once(server, 'listening'), 5000, 'server listening');
    const baseUrl = `http://localhost:${server.address().port}`;
    const clients = [];
    const connect = async () => {
        const client = createClient(baseUrl, { transports: ['websocket'], reconnection: false, auth: { token: getAccessToken() } });
        clients.push(client);
        await withTimeout(once(client, 'connect'), 5000, 'client connect');
        return client;
    };
    t.after(async () => {
        for (const client of clients) client.disconnect();
        await withTimeout(new Promise((resolve) => getIO().close(resolve)), 5000, 'server close');
    });

    const missingAgent = await connect();
    missingAgent.emit('login-agent', missingName);
    const healthyAgent = await connect();
    healthyAgent.on('get-agent-memory', (ack) => ack('healthy memory'));
    healthyAgent.on('get-full-state', (ack) => ack({ player: { name: 'healthy' } }));
    healthyAgent.emit('login-agent', healthyName);

    const ui = await connect();
    const memoryStarted = Date.now();
    const memoryResult = await withTimeout(
        new Promise((resolve) => ui.emit('get-agent-memory', missingName, resolve)),
        5000,
        'missing-agent memory callback'
    );
    assert.equal(memoryResult.success, false);
    assert.match(memoryResult.error, /timed out/i);
    assert.ok(Date.now() - memoryStarted < 1800, 'memory callback should finish near the acknowledgement timeout');

    // The built-in Socket.IO timeout must release the internal ack entry; a
    // manual setTimeout around plain emit() would reject above but leak the
    // entry in socket.acks forever when the agent never acks.
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.equal(serverAckCount(), 0, 'timed-out acknowledgement must not retain Socket.IO ack callbacks');

    ui.emit('listen-to-agents');
    const firstStates = await withTimeout(
        new Promise((resolve) => ui.once('state-update', resolve)),
        6000,
        'first state-update'
    );
    assert.match(firstStates[missingName].error, /timed out/i);
    assert.deepEqual(firstStates[healthyName], { player: { name: 'healthy' } });

    // Park the first UI listener so the stop/restart below exercises the
    // last-listener abort path (removeListener only aborts when the count
    // reaches zero). Without this, the old listener would never be the last
    // one and no restart isolation would actually be tested.
    ui.disconnect();
    await new Promise((resolve) => setTimeout(resolve, 100));
    assert.equal(numStateListeners(), 0, 'parking the first listener should stop polling');

    // The first state request intentionally remains unacknowledged across listener stop.
    // NOTE: subsequent acks use a short delay (< AGENT_ACK_TIMEOUT_MS) so the
    // restarted poll can succeed with generation 2. The prior draft used 1250ms
    // here, which always exceeds the 1000ms ack timeout and could never yield
    // generation 2, and its client-side concurrency counter could never stay at
    // 1 while the first request is deliberately held open. Server poll overlap
    // is prevented by the activeListenerPoll guard; client-side outstanding
    // acks can legitimately exceed 1 when an orphaned (aborted) request is held.
    const lateAgent = await connect();
    let firstLateAck;
    let lateRequestCount = 0;
    lateAgent.on('get-full-state', (ack) => {
        lateRequestCount++;
        if (lateRequestCount === 1) {
            firstLateAck = (state) => ack(state);
            return;
        }
        const state = { generation: lateRequestCount };
        setTimeout(() => ack(state), 150);
    });
    lateAgent.emit('login-agent', lateName);

    const oldListener = await connect();
    oldListener.emit('listen-to-agents');
    await withTimeout(
        new Promise((resolve) => lateAgent.once('get-full-state', resolve)),
        6000,
        'late agent first state request'
    );
    oldListener.disconnect();
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(numStateListeners(), 0);

    const restartedListener = await connect();
    const receivedStates = [];
    restartedListener.on('state-update', (states) => receivedStates.push(states));
    restartedListener.emit('listen-to-agents');
    const newState = await withTimeout(
        new Promise((resolve) => restartedListener.once('state-update', resolve)),
        8000,
        'restarted listener state-update'
    );
    assert.equal(newState[lateName].generation, 2);
    // Polls must keep completing (no overlap deadlock): a second update arrives.
    await withTimeout(
        new Promise((resolve) => {
            const check = () => {
                if (receivedStates.length >= 2) resolve();
                else setTimeout(check, 50);
            };
            check();
        }),
        8000,
        'second post-restart state-update (liveness)'
    );

    firstLateAck({ generation: 1 });
    const publishedBeforeLate = receivedStates.length;
    await new Promise((resolve) => setTimeout(resolve, 300));
    assert.equal(receivedStates.length, publishedBeforeLate, 'a late acknowledgement from a stopped listener must not publish');
    assert.equal(numStateListeners(), 1);

    // A replaced socket must not let the old socket's late ack mutate new state.
    const replacedOld = await connect();
    let staleAck = null;
    replacedOld.on('get-full-state', (ack) => {
        staleAck = () => ack({ generation: 1, stale: true });
    });
    replacedOld.emit('login-agent', replacedName);
    await withTimeout(
        new Promise((resolve) => replacedOld.once('get-full-state', resolve)),
        8000,
        'replaced agent first state request'
    );
    const replacedNew = await connect();
    replacedNew.on('get-full-state', (ack) => ack({ generation: 2 }));
    replacedNew.emit('login-agent', replacedName);
    assert.ok(typeof staleAck === 'function', 'old socket should have a pending state request to supersede');
    staleAck();
    // The next poll may have started before the replacement (its stale entry
    // is dropped by the socket-identity check), so wait for the first update
    // carrying the new socket's response rather than assuming the very next
    // tick is already post-replacement.
    const afterReplace = await withTimeout(
        new Promise((resolve) => {
            const check = () => {
                for (let i = receivedStates.length - 1; i >= 0; i--) {
                    const candidate = receivedStates[i][replacedName];
                    if (candidate && candidate.generation === 2) {
                        resolve(receivedStates[i]);
                        return;
                    }
                }
                setTimeout(check, 50);
            };
            check();
        }),
        12000,
        'post-replacement state-update'
    );
    assert.equal(afterReplace[replacedName].generation, 2, 'stale acknowledgement from a replaced socket must not mutate new state');
    assert.ok(!afterReplace[replacedName].stale, 'replaced socket response must win over the old socket');
    for (const states of receivedStates) {
        assert.ok(!states[replacedName]?.stale, 'stale socket response must never be published');
    }
});
