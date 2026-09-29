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

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('listener subscribe is idempotent and unsubscribe never touches another listener', async (t) => {
    const suffix = `${process.pid}-${Date.now()}-lcl`;
    const healthyName = `healthy-peer-${suffix}`;
    registerAgent({ profile: { name: healthyName } });

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
        for (const client of clients) {
            try { client.disconnect(); } catch { /* ignore */ }
        }
        await withTimeout(new Promise((resolve) => getIO().close(resolve)), 5000, 'server close');
    });

    const agent = await connect();
    agent.on('get-full-state', (ack) => ack({ player: { name: 'healthy' } }));
    agent.on('get-agent-memory', (ack) => ack('peer memory'));
    agent.emit('login-agent', healthyName);
    await sleep(150);

    // Sole listener + duplicate subscribe stays at 1 (idempotent, no double fan-out entry).
    const uiA = await connect();
    uiA.emit('listen-to-agents');
    await withTimeout(new Promise((resolve) => uiA.once('state-update', resolve)), 6000, 'first update for uiA');
    uiA.emit('listen-to-agents');
    await sleep(150);
    assert.equal(numStateListeners(), 1, 'duplicate listen on the same socket must not double-register');

    // Healthy peer joins; duplicate from uiA must not affect the count.
    const uiB = await connect();
    const uiBUpdates = [];
    uiB.on('state-update', (states) => uiBUpdates.push(states));
    uiB.emit('listen-to-agents');
    await withTimeout(new Promise((resolve) => uiB.once('state-update', resolve)), 6000, 'first update for uiB');
    assert.equal(numStateListeners(), 2);
    uiA.emit('listen-to-agents');
    await sleep(150);
    assert.equal(numStateListeners(), 2, 'duplicate listen must not add a third registration');

    // Nonmember disconnect must not splice another listener.
    const plain = await connect();
    await sleep(100);
    assert.equal(numStateListeners(), 2);
    plain.disconnect();
    await sleep(300);
    assert.equal(numStateListeners(), 2, 'disconnect of a non-listener must not remove another listener');

    // First listener leaves; peer keeps receiving (polling continues, no orphan stop).
    uiA.disconnect();
    await sleep(300);
    assert.equal(numStateListeners(), 1, 'one disconnect must leave the remaining peer subscribed');
    uiBUpdates.length = 0;
    await withTimeout(
        new Promise((resolve) => {
            const check = () => {
                if (uiBUpdates.length >= 1) resolve();
                else setTimeout(check, 50);
            };
            check();
        }),
        6000,
        'peer update after companion unsubscribe'
    );
    assert.deepEqual(uiBUpdates[0][healthyName], { player: { name: 'healthy' } });

    // Duplicate then single disconnect removes everything (no orphan timer).
    uiB.emit('listen-to-agents');
    await sleep(150);
    assert.equal(numStateListeners(), 1, 'duplicate listen on the remaining socket must stay idempotent');
    uiB.disconnect();
    await sleep(400);
    assert.equal(numStateListeners(), 0, 'last-listener disconnect must stop polling');
});
