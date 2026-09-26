import assert from 'node:assert/strict';
import test from 'node:test';
import { Codex } from '../src/models/codex.js';

function codexWith(getServer, timeout_ms = 25) {
    return new Codex('gpt-test', null, { timeout_ms }, { getServer });
}

function abortableNever(signal) {
    return new Promise((resolve, reject) => {
        if (signal.aborted) return reject(signal.reason);
        signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    });
}

test('deadline includes waiting for the shared server to initialize', async () => {
    const model = codexWith(signal => abortableNever(signal));
    await assert.rejects(model._runTurn('system', 'hello'), /timed out after 25ms/);
});

test('deadline includes thread/start and passes its abort signal to the RPC', async () => {
    let threadSignal;
    const srv = {
        listeners: new Set(),
        call(method, params, options) {
            assert.equal(method, 'thread/start');
            threadSignal = options.signal;
            return abortableNever(options.signal);
        },
    };
    const model = codexWith(() => Promise.resolve(srv));

    await assert.rejects(model._runTurn('system', 'hello'), /timed out after 25ms/);
    assert.equal(threadSignal.aborted, true);
    assert.equal(srv.listeners.size, 0);
});

test('timed-out accepted turns are interrupted and listeners are removed', async () => {
    const calls = [];
    const srv = {
        listeners: new Set(),
        call(method, params) {
            calls.push({ method, params });
            if (method === 'thread/start') return Promise.resolve({ thread: { id: 'thread-1' } });
            if (method === 'turn/start') return Promise.resolve({ turn: { id: 'turn-1' } });
            if (method === 'turn/interrupt') return Promise.resolve({});
            throw new Error(`unexpected method ${method}`);
        },
    };
    const model = codexWith(() => Promise.resolve(srv));

    await assert.rejects(model._runTurn('system', 'hello'), /timed out after 25ms/);
    assert.deepEqual(calls.at(-1), {
        method: 'turn/interrupt',
        params: { threadId: 'thread-1', turnId: 'turn-1' },
    });
    assert.equal(srv.listeners.size, 0);
});

test('a late turn/start reply is interrupted after the request deadline', async () => {
    const interrupts = [];
    const srv = {
        listeners: new Set(),
        call(method, params, options = {}) {
            if (method === 'thread/start') return Promise.resolve({ thread: { id: 'thread-late' } });
            if (method === 'turn/start') {
                return new Promise((resolve, reject) => {
                    options.signal.addEventListener('abort', () => {
                        reject(options.signal.reason);
                        queueMicrotask(() => options.onLateResult({ turn: { id: 'turn-late' } }));
                    }, { once: true });
                });
            }
            if (method === 'turn/interrupt') {
                interrupts.push(params);
                return Promise.resolve({});
            }
            throw new Error(`unexpected method ${method}`);
        },
    };
    const model = codexWith(() => Promise.resolve(srv));

    await assert.rejects(model._runTurn('system', 'hello'), /timed out after 25ms/);
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.deepEqual(interrupts, [{ threadId: 'thread-late', turnId: 'turn-late' }]);
    assert.equal(srv.listeners.size, 0);
});

test('successful turns return the final agent message and clean up the listener', async () => {
    const srv = {
        listeners: new Set(),
        call(method) {
            if (method === 'thread/start') return Promise.resolve({ thread: { id: 'thread-ok' } });
            if (method === 'turn/start') {
                queueMicrotask(() => {
                    for (const listener of srv.listeners) {
                        listener({ method: 'item/completed', params: { threadId: 'thread-ok', item: { type: 'agentMessage', text: 'answer' } } });
                        listener({ method: 'turn/completed', params: { threadId: 'thread-ok', turn: { id: 'turn-ok', status: 'completed' } } });
                    }
                });
                return Promise.resolve({ turn: { id: 'turn-ok' } });
            }
            throw new Error(`unexpected method ${method}`);
        },
    };
    const model = codexWith(() => Promise.resolve(srv), 100);

    assert.equal(await model._runTurn('system', 'hello'), 'answer');
    assert.equal(srv.listeners.size, 0);
});
