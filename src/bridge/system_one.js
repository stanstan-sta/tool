// System One: fast multiple-choice decisions from Decider-2b (an open Jev-style model)
// served by a stock llama-server. Decider does not generate text; it reads a state and one
// question with lettered options, and the answer is the softmax over the option-letter
// logits at the "Answer: (" slot. Prompt layout mirrors upstream decider/prompt.py
// (state-first) and option rendering mirrors decider/systemone.py.

const LETTERS = 'ABCDEFGHIJ';
const TEMPERATURE = 1.30; // upstream calibration for the state-first layout

export function renderState(state) {
    return typeof state === 'string' ? state : JSON.stringify(state);
}

// criteria: {name: description | null}. Returns option lines as upstream renders them.
export function renderOptions(criteria) {
    const names = Object.keys(criteria || {});
    if (names.length < 2 || names.length > LETTERS.length) {
        throw new Error(`SystemOne: need 2..${LETTERS.length} options, got ${names.length}`);
    }
    const text = names.map(n => {
        const d = criteria[n];
        if (d === null || d === undefined || d === '') return n;
        return `${n}: ${typeof d === 'string' ? d : JSON.stringify(d)}`;
    });
    return { names, text };
}

// Restrict to the option letters and apply temperature: softmax(logit/T) over the subset
// equals p^(1/T) renormalised, so full-vocabulary log-probs are enough.
export function letterProbabilities(logprobByLetter, n, temperature = TEMPERATURE) {
    const scaled = [];
    for (let i = 0; i < n; i++) {
        const lp = logprobByLetter[LETTERS[i]];
        if (!Number.isFinite(lp)) return null;
        scaled.push(lp / temperature);
    }
    const max = Math.max(...scaled);
    if (!Number.isFinite(max)) return null;
    const exps = scaled.map(s => Math.exp(s - max));
    const sum = exps.reduce((a, b) => a + b, 0);
    return exps.map(e => e / sum);
}

export class SystemOne {
    constructor({ url = 'http://127.0.0.1:8781', timeoutMs = 2000 } = {}) {
        this.url = url.replace(/\/$/, '');
        this.timeoutMs = timeoutMs;
    }

    async _post(path, body) {
        const res = await fetch(this.url + path, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify(body),
            signal: AbortSignal.timeout(this.timeoutMs),
        });
        if (!res.ok) throw new Error(`SystemOne ${path} HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
        return await res.json();
    }

    async _tokenize(text) {
        const { tokens } = await this._post('/tokenize', { content: text, add_special: false });
        return tokens;
    }

    /**
     * @param {string|object} state
     * @param {string} question
     * @param {Object<string, string|null>} criteria option name -> description
     * @returns {Promise<{choice: string, probs: Object<string, number>, ms: number}>}
     */
    async decide(state, question, criteria) {
        const started = Date.now();
        const { names, text } = renderOptions(criteria);
        // Upstream tokenizes the context and the question block separately; do the same so the
        // token boundary at the join matches training.
        const piece = `\n\nQuestion: ${question}\nOptions:` + text.map((t, i) => `\n(${LETTERS[i]}) ${t}`).join('') + '\nAnswer: (';
        const [ctx, tail] = await Promise.all([
            this._tokenize('Context:\n' + renderState(state)),
            this._tokenize(piece),
        ]);
        const out = await this._post('/completion', {
            prompt: [...ctx, ...tail],
            n_predict: 1,
            n_probs: 40,
            temperature: 0,
            post_sampling_probs: false,
            cache_prompt: true,
        });
        const top = out?.completion_probabilities?.[0]?.top_logprobs || [];
        const byLetter = {};
        for (const t of top) {
            const tok = String(t.token ?? '');
            if (tok.length === 1 && LETTERS.includes(tok) && byLetter[tok] === undefined) byLetter[tok] = t.logprob;
        }
        const p = letterProbabilities(byLetter, names.length);
        if (!p) throw new Error('SystemOne: no option letter in the top log-probs');
        const probs = Object.fromEntries(names.map((n, i) => [n, p[i]]));
        const best = names[p.indexOf(Math.max(...p))];
        return { choice: best, probs, ms: Date.now() - started };
    }
}
