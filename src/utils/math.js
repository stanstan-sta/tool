import { safeCosineSimilarity } from '../models/embedding_normaliser.js';

/**
 * Cosine similarity in [-1, 1] (or 0 for invalid input).
 * Safe against length mismatch, missing vectors, zero magnitude, and
 * non-finite values. See safeCosineSimilarity in embedding_normaliser.js.
 */
export function cosineSimilarity(a, b) {
    return safeCosineSimilarity(a, b);
}
