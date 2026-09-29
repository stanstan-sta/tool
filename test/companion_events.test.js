import assert from 'node:assert/strict';
import test from 'node:test';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';

const COMPANION_EVENT = {
    v: 1, type: 'event', kind: 'player_death', player: 'Alex', cause: 'fall',
};

function companionAgent(polls, historyAdds) {
    const agent = Object.create(BridgeAgent.prototype);
    let calls = 0;
    Object.assign(agent, {
        name: 'TestBot',
        _generation: 1,
        _lastState: null,
        _lastStateSeq: null,
        _pollIntervalMs: 2000,
        _bridgeReachable: true,
        _inboundQueue: [],
        _nextWorldRecordAt: Infinity,
        _pendingContinuation: false,
        _lastHadActions: false,
        history: { add: (role, content) => { historyAdds.push({ role, content }); } },
        bridge: {
            getState: async () => polls[Math.min(calls++, polls.length - 1)],
        },
        survivalReflex: { evaluate: () => null },
        _survivalSafePos: () => null,
        _tickBuildValidation: () => Promise.resolve(),
        eventDetector: { check: () => [] },
        _enqueueReasoningTask: () => {},
        _kickReasoningWorker: () => {},
        _activeTaskRecord: () => null,
    });
    return { agent, calls: () => calls };
}

test('a drained companion event is observed exactly once (no loss, no double-act)', async () => {
    const historyAdds = [];
    const fullState = {
        connected: true, seq: 7, player_name: 'TestBot', unchanged: false,
        chat: [], chat_events: [], recent_events: [],
        server_events: [{ ...COMPANION_EVENT }],
        queue: { status: 'idle' },
    };
    // Legacy-style unchanged poll carries no server_events field: merge would
    // resurrect the previous array unless the consumer guards against it.
    const unchangedLegacy = { connected: true, seq: 7, unchanged: true };
    const { agent } = companionAgent([fullState, unchangedLegacy], historyAdds);

    await agent._runObservationCycle();
    const companioned = historyAdds.filter(
        e => typeof e.content === 'string' && e.content.includes('player_death'));
    assert.equal(companioned.length, 1,
        `expected exactly one preserved companion event after drain, saw ${companioned.length}`);

    await agent._runObservationCycle();
    const afterSecond = historyAdds.filter(
        e => typeof e.content === 'string' && e.content.includes('player_death'));
    assert.equal(afterSecond.length, 1,
        `companion event must not double-act on a later unchanged merge (saw ${afterSecond.length})`);

    assert.deepEqual(agent._lastState.server_events ?? [], [],
        'consumed companion events must not linger for re-observation');
});

test('observation poll drains (it must be the explicit drain consumer)', async () => {
    const historyAdds = [];
    const seenOptions = [];
    const agent = Object.create(BridgeAgent.prototype);
    Object.assign(agent, {
        name: 'TestBot',
        _generation: 1,
        _lastState: null,
        _lastStateSeq: null,
        _pollIntervalMs: 2000,
        _bridgeReachable: true,
        _inboundQueue: [],
        _nextWorldRecordAt: Infinity,
        _pendingContinuation: false,
        _lastHadActions: false,
        history: { add: (role, content) => { historyAdds.push({ role, content }); } },
        bridge: {
            getState: async (_seq, options) => {
                seenOptions.push({ ...options });
                return {
                    connected: true, seq: 9, player_name: 'TestBot', unchanged: false,
                    chat: [], chat_events: [], recent_events: [],
                    server_events: [{ ...COMPANION_EVENT }],
                    queue: { status: 'idle' },
                };
            },
        },
        survivalReflex: { evaluate: () => null },
        _survivalSafePos: () => null,
        _tickBuildValidation: () => Promise.resolve(),
        eventDetector: { check: () => [] },
        _enqueueReasoningTask: () => {},
        _kickReasoningWorker: () => {},
        _activeTaskRecord: () => null,
    });
    await agent._runObservationCycle();
    assert.ok(seenOptions.length >= 1);
    assert.notEqual(seenOptions[0].drainChat, false,
        'observation poll must drain so the event is seen; peek reads must stay non-destructive');
});
