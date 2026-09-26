import test from 'node:test';
import assert from 'node:assert/strict';
import { letterProbabilities, renderOptions, SystemOne } from '../src/bridge/system_one.js';

test('letterProbabilities applies temperature over the option letters only', () => {
    const p = letterProbabilities({ A: Math.log(0.6), B: Math.log(0.2), Z: Math.log(0.2) }, 2, 1);
    assert.ok(Math.abs(p[0] - 0.75) < 1e-9);
    assert.ok(Math.abs(p[1] - 0.25) < 1e-9);
    const hot = letterProbabilities({ A: Math.log(0.6), B: Math.log(0.2) }, 2, 2);
    assert.ok(hot[0] < 0.75 && hot[0] > 0.5);
});

test('letterProbabilities rejects incomplete or malformed option scores', () => {
    assert.equal(letterProbabilities({ B: -1 }, 2, 1.3), null);
    assert.equal(letterProbabilities({ A: Math.log(1e-30) }, 2, 1.3), null);
    assert.equal(letterProbabilities({ A: 0, B: NaN }, 2), null);
    assert.equal(letterProbabilities({ A: 0, B: Infinity }, 2), null);
    assert.equal(letterProbabilities({ A: 0, B: '0' }, 2), null);
    assert.equal(letterProbabilities({}, 2), null);
});

test('letterProbabilities calibrates complete finite option scores', () => {
    const p = letterProbabilities({ A: Math.log(0.8), B: Math.log(0.2) }, 2, 1);
    assert.ok(Math.abs(p[0] - 0.8) < 1e-9);
    assert.ok(Math.abs(p[1] - 0.2) < 1e-9);
});

test('renderOptions matches the upstream "name: description" rendering', () => {
    assert.deepEqual(renderOptions({ flee: 'run away', fight: null }), { names: ['flee', 'fight'], text: ['flee: run away', 'fight'] });
    assert.throws(() => renderOptions({ only: null }));
});

test('decide builds the state-first prompt and reads the letter log-probs', async () => {
    const calls = [];
    const s1 = new SystemOne({ url: 'http://s1.test' });
    s1._post = async (path, body) => {
        calls.push({ path, body });
        if (path === '/tokenize') return { tokens: [body.content.length] };
        return { completion_probabilities: [{ top_logprobs: [
            { token: 'B', logprob: Math.log(0.7) }, { token: 'A', logprob: Math.log(0.2) }, { token: 'the', logprob: Math.log(0.1) },
        ] }] };
    };
    const r = await s1.decide({ hp: 4 }, 'Flee?', { stay: null, flee: 'run' });
    assert.equal(r.choice, 'flee');
    assert.ok(r.probs.flee > r.probs.stay);
    const tokenized = calls.filter(c => c.path === '/tokenize').map(c => c.body.content);
    assert.deepEqual(tokenized, ['Context:\n{"hp":4}', '\n\nQuestion: Flee?\nOptions:\n(A) stay\n(B) flee: run\nAnswer: (']);
    assert.equal(calls.at(-1).body.n_predict, 1);
});
