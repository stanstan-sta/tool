import test from 'node:test';
import assert from 'node:assert/strict';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';
import { serverProxy } from '../src/agent/mindserver_proxy.js';

serverProxy.socket = { emit() {} };

test('vision sequence preserves accepted queue count across an inspected boundary', async () => {
    const sent = [];
    const agent = Object.create(BridgeAgent.prototype);
    Object.assign(agent, {
        _generation: 1,
        _lastState: { connected: true, dimension: 'minecraft:overworld', x: 0, y: 64, z: 0 },
        bridge: {
            async sendBatch(actions) {
                sent.push(structuredClone(actions));
                return { success: true, accepted: true, queued: actions.length, results: actions.map(() => ({ status: 'queued' })) };
            },
            async readBlocks() { return null; },
        },
        async _waitForInspectionState() { return null; },
        async _consumeVisionInspectActions(actions) { return { results: ['screen inspected'] }; },
    });

    const result = await agent._sendBatchWithBuildExpansion([
        { type: 'mine', target: 'oak_log', count: 1 },
        { type: 'inspect_screen_with_vision', question: 'what is open?' },
    ], 1);

    assert.equal(sent.length, 1, 'the action before vision was accepted by the bridge');
    assert.equal(sent[0][0].type, 'mine');
    assert.ok(result.queued > 0, 'aggregate result must report accepted prefix work so task tracking arms continuation');
    assert.deepEqual(result.sentActions, sent[0], 'the accepted prefix remains available for task outcome accounting');
});

test('failed verification refresh does not strand a completed task record', async () => {
    const agent = Object.create(BridgeAgent.prototype);
    const idle = { connected: true, inventory: [{ item: 'minecraft:oak_log', count: 1 }], queue: { status: 'idle', pending: 0, active: null } };
    let reads = 0;
    Object.assign(agent, {
        name: 'Review', _generation: 1, _taskRecord: null, _taskSeq: 0,
        _lastTaskLabel: '', _lastTaskAttempt: 0, _lastState: { connected: true, inventory: [] },
        _lastHadActions: true, _pendingContinuation: true,
        _buildStateContext: () => '',
        history: { add() {}, getHistory: () => [], async save() {} },
        async _promptConvoLocked() { return '{"reply":"done"}'; },
        bridge: {
            async getState() { reads++; return reads === 1 ? idle : null; },
            async getQueueState() { return null; },
        },
    });
    const record = agent._startTaskRecord('get one oak log', 'player', 1);
    agent._trackDispatchResult(record, [{ type: 'mine', target: 'oak_log', count: 1 }], { success: true, queued: 1 });
    await agent._continuePlan(1);

    assert.equal(record.closed, true, 'a transient verification refresh failure must retire or retry the record');
    assert.equal(agent._activeTaskRecord(), null);
});
