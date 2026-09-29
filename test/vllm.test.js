import test from 'node:test';
import assert from 'node:assert/strict';

import { VLLM } from '../src/models/vllm.js';

test('VLLM constructs against an unauthenticated local OpenAI-compatible server', () => {
    let adapter;
    assert.doesNotThrow(() => {
        adapter = new VLLM('local-test-model', 'http://127.0.0.1:8000/v1');
    });

    assert.ok(adapter?.vllm, 'OpenAI-compatible client should be constructed');
    assert.equal(adapter.vllm.apiKey, 'vllm-local',
        'local vLLM uses a non-empty placeholder required by the OpenAI SDK');
    assert.equal(adapter.vllm.baseURL, 'http://127.0.0.1:8000/v1');
});
