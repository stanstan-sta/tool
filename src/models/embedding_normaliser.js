/**
 * Shared embedding output normalization.
 *
 * Different providers return embeddings in many shapes:
 *   - flat number[]:                       [0.1, 0.2, ...]
 *   - 1-wrapped array:                     [[0.1, 0.2, ...]]
 *   - nested token vectors:                [[0.1, ...], [0.2, ...]]  (per-token)
 *   - provider object:                     { data: [{ embedding: [...] }] }
 *                                         { embedding: [...] }
 *                                         { embeddings: [[...]] }
 *                                         { results: [{ embedding: [...] }] }
 *
 * normaliseEmbeddingVector() flattens any of these to a single finite number[]
 * and rejects empty / non-finite / dimension-mismatched input with a useful error.
 */

const ZERO_TOLERANCE = 1e-12;

function isFiniteNumber(x) {
    return typeof x === 'number' && Number.isFinite(x);
}

function flattenIfAllNumbers(arr) {
    if (!Array.isArray(arr) || arr.length === 0) return null;
    if (arr.every(isFiniteNumber)) return arr;
    // nested: [[...], [...]] -> mean-pool to a flat vector
    const inner = arr.filter(Array.isArray);
    if (inner.length === 0) return null;
    if (!inner.every(a => Array.isArray(a) && a.length === inner[0].length)) {
        // ragged token shapes cannot be mean-pooled; bail out
        return null;
    }
    const dim = inner[0].length;
    const out = new Array(dim).fill(0);
    for (const v of inner) {
        for (let i = 0; i < dim; i++) {
            const n = v[i];
            if (!isFiniteNumber(n)) return null;
            out[i] += n;
        }
    }
    for (let i = 0; i < dim; i++) out[i] /= inner.length;
    return out;
}

function pickProviderObject(obj) {
    if (!obj || typeof obj !== 'object') return null;
    // OpenAI-style { data: [{ embedding: number[] }] }
    if (Array.isArray(obj.data) && obj.data.length > 0) {
        const first = obj.data[0];
        if (first && Array.isArray(first.embedding)) return first.embedding;
        if (first && Array.isArray(first.values)) return first.values;
    }
    // CoHERE / generic { embedding: number[] }
    if (Array.isArray(obj.embedding)) return obj.embedding;
    // generic { embeddings: number[][] }
    if (Array.isArray(obj.embeddings) && obj.embeddings.length > 0 && Array.isArray(obj.embeddings[0])) {
        return obj.embeddings[0];
    }
    // generic { results: [{ embedding: number[] }] }
    if (Array.isArray(obj.results) && obj.results.length > 0) {
        const first = obj.results[0];
        if (first && Array.isArray(first.embedding)) return first.embedding;
    }
    // Mistral / others { vectors: number[][] }
    if (Array.isArray(obj.vectors) && obj.vectors.length > 0 && Array.isArray(obj.vectors[0])) {
        return obj.vectors[0];
    }
    return null;
}

export function normaliseEmbeddingVector(raw, { expectedDim = null, label = 'embedding' } = {}) {
    if (raw === null || raw === undefined) {
        throw new Error(`${label}: response is null/undefined`);
    }

    // Provider object
    if (!Array.isArray(raw) && typeof raw === 'object') {
        const candidate = pickProviderObject(raw);
        if (!candidate) {
            throw new Error(`${label}: unsupported provider object shape`);
        }
        return normaliseEmbeddingVector(candidate, { expectedDim, label });
    }

    if (!Array.isArray(raw) || raw.length === 0) {
        throw new Error(`${label}: response is not an array`);
    }

    // Already flat number[]
    if (raw.every(isFiniteNumber)) {
        if (expectedDim !== null && raw.length !== expectedDim) {
            throw new Error(`${label}: dimension mismatch (got ${raw.length}, expected ${expectedDim})`);
        }
        return raw.slice();
    }

    // Unwrap a single-element outer array regardless of element type
    // (handles 1-wrapped [[vec]] and [[token_vec, token_vec, ...]] shapes)
    if (raw.length === 1 && Array.isArray(raw[0])) {
        const unwrapped = raw[0];
        if (unwrapped.every(isFiniteNumber)) {
            if (expectedDim !== null && unwrapped.length !== expectedDim) {
                throw new Error(`${label}: dimension mismatch (got ${unwrapped.length}, expected ${expectedDim})`);
            }
            return unwrapped.slice();
        }
        // Nested token vectors from a single input: unwrap and mean-pool
        const flat = flattenIfAllNumbers(unwrapped);
        if (flat) {
            if (expectedDim !== null && flat.length !== expectedDim) {
                throw new Error(`${label}: dimension mismatch (got ${flat.length}, expected ${expectedDim})`);
            }
            if (flat.length === 0) {
                throw new Error(`${label}: flattened vector is empty`);
            }
            if (!flat.every(isFiniteNumber)) {
                throw new Error(`${label}: vector contains non-finite values`);
            }
            return flat;
        }
    }

    // Multi-token response: each raw element is a separate token vector
    const flat = flattenIfAllNumbers(raw);
    if (flat) {
        if (expectedDim !== null && flat.length !== expectedDim) {
            throw new Error(`${label}: dimension mismatch (got ${flat.length}, expected ${expectedDim})`);
        }
        if (flat.length === 0) {
            throw new Error(`${label}: flattened vector is empty`);
        }
        if (!flat.every(isFiniteNumber)) {
            throw new Error(`${label}: vector contains non-finite values`);
        }
        return flat;
    }

    throw new Error(`${label}: response is not a flat number[] and could not be flattened`);
}

/**
 * Hardened cosine similarity. Returns a finite number in [-1, 1].
 *  - length mismatch: 0
 *  - missing / empty vectors: 0
 *  - zero magnitude: 0
 *  - non-finite values: 0
 */
export function safeCosineSimilarity(a, b) {
    if (!Array.isArray(a) || !Array.isArray(b)) return 0;
    if (a.length === 0 || b.length === 0) return 0;
    if (a.length !== b.length) return 0;
    let dot = 0;
    let magA = 0;
    let magB = 0;
    for (let i = 0; i < a.length; i++) {
        const x = a[i];
        const y = b[i];
        if (!isFiniteNumber(x) || !isFiniteNumber(y)) return 0;
        dot += x * y;
        magA += x * x;
        magB += y * y;
    }
    if (magA < ZERO_TOLERANCE || magB < ZERO_TOLERANCE) return 0;
    const sim = dot / (Math.sqrt(magA) * Math.sqrt(magB));
    if (!Number.isFinite(sim)) return 0;
    if (sim > 1) return 1;
    if (sim < -1) return -1;
    return sim;
}
