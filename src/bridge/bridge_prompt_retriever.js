import { safeCosineSimilarity } from '../models/embedding_normaliser.js';
import { wordOverlapScore } from '../utils/text.js';

const DEFAULT_QUERY_INSTRUCTION = 'Given a Minecraft player request and bridge state, retrieve relevant bridge task guidance.';

function asArray(value) {
    return Array.isArray(value) ? value : [];
}

function normalizeText(value) {
    return String(value || '').trim();
}

function normalizeToken(value) {
    return normalizeText(value).toLowerCase().replace(/^minecraft:/, '');
}

function containsToken(text, token) {
    const t = normalizeToken(token);
    if (!t) return false;
    return normalizeToken(text).includes(t);
}

function buildPackDocument(pack) {
    return [
        pack.id,
        pack.title,
        asArray(pack.actions).join(' '),
        asArray(pack.triggers).join(' '),
        normalizeText(pack.content),
    ].filter(Boolean).join('\n');
}

function estimateTokens(text) {
    return Math.ceil(normalizeText(text).length / 4);
}

function scorePackLexically(pack, query, context = {}) {
    const document = buildPackDocument(pack);
    let score = wordOverlapScore(query, document);

    const queryText = normalizeText(query);
    for (const trigger of asArray(pack.triggers)) {
        if (containsToken(queryText, trigger)) score += 0.45;
    }

    const actionSet = new Set(asArray(context.actions).map(normalizeToken));
    for (const action of asArray(pack.actions)) {
        if (actionSet.has(normalizeToken(action))) score += 0.35;
        if (containsToken(queryText, action)) score += 0.25;
    }

    const stateText = normalizeText(context.stateSummary || '');
    for (const trigger of asArray(pack.triggers)) {
        if (containsToken(stateText, trigger)) score += 0.12;
    }

    if (context.dimension && containsToken(document, context.dimension)) score += 0.2;
    if (context.openScreen && containsToken(document, 'container')) score += 0.2;

    return score + Number(pack.priority || 0) / 1000;
}

export class BridgePromptPackRetriever {
    constructor(embeddingModel, packs = []) {
        this.embeddingModel = embeddingModel || null;
        this.packs = asArray(packs);
        this.packEmbeddings = new Map();
        this.ready = false;
    }

    async init() {
        if (!this.embeddingModel) {
            this.ready = true;
            return;
        }
        try {
            const docs = this.packs.map(pack => buildPackDocument(pack));
            const embeddings = await this.embeddingModel.embed(docs, {
                intent: 'document',
                instruction: 'Represent Minecraft bridge prompt-pack guidance for retrieval.',
            });
            if (!Array.isArray(embeddings)) throw new Error('prompt-pack batch embedding was not an array');
            embeddings.forEach((embedding, index) => {
                if (Array.isArray(embedding) && embedding.every(Number.isFinite)) {
                    this.packEmbeddings.set(this.packs[index].id, embedding);
                }
            });
            this.ready = true;
        } catch (err) {
            console.warn('BridgePromptPackRetriever: embedding init failed, using lexical routing:', err.message || err);
            this.embeddingModel = null;
            this.packEmbeddings.clear();
            this.ready = true;
        }
    }

    async getRelevantPacks(query, options = {}) {
        const cleanQuery = normalizeText(query);
        if (!cleanQuery || this.packs.length === 0) return [];

        let queryEmbedding = null;
        if (this.embeddingModel) {
            try {
                queryEmbedding = await this.embeddingModel.embed(cleanQuery, {
                    intent: 'query',
                    instruction: options.instruction || DEFAULT_QUERY_INSTRUCTION,
                });
                if (!Array.isArray(queryEmbedding) || !queryEmbedding.every(Number.isFinite)) {
                    throw new Error('invalid prompt-pack query embedding');
                }
            } catch (err) {
                console.warn('BridgePromptPackRetriever: query embed failed, using lexical routing:', err.message || err);
                this.embeddingModel = null;
                queryEmbedding = null;
            }
        }

        const scored = this.packs.map(pack => {
            let score = scorePackLexically(pack, cleanQuery, options);
            const emb = this.packEmbeddings.get(pack.id);
            if (queryEmbedding && emb && emb.length === queryEmbedding.length) {
                score += safeCosineSimilarity(queryEmbedding, emb);
            }
            return { pack, score };
        });
        scored.sort((a, b) => b.score - a.score);

        const k = Math.max(0, Number(options.k || 4));
        const selected = scored.filter(s => s.score > 0).slice(0, k).map(s => s.pack);
        return this._withDependencies(selected);
    }

    _withDependencies(selected) {
        const byId = new Map(this.packs.map(pack => [pack.id, pack]));
        const out = [];
        const seen = new Set();
        const add = pack => {
            if (!pack || seen.has(pack.id)) return;
            seen.add(pack.id);
            for (const req of asArray(pack.requires)) add(byId.get(req));
            out.push(pack);
        };
        for (const pack of selected) add(pack);
        out.sort((a, b) => Number(b.priority || 0) - Number(a.priority || 0));
        return out;
    }
}

export function formatBridgePromptPacks(packs, options = {}) {
    if (!Array.isArray(packs) || packs.length === 0) return '';
    const budget = Math.max(200, Number(options.tokenBudget || 2200));
    const lines = ['BRIDGE TASK GUIDANCE (retrieved):'];
    let used = estimateTokens(lines[0]);
    for (const pack of packs) {
        const content = normalizeText(pack.content);
        if (!content) continue;
        const block = [
            `# ${pack.title || pack.id}`,
            content,
        ].join('\n');
        const cost = estimateTokens(block);
        if (used + cost > budget && lines.length > 1) break;
        lines.push(block);
        used += cost;
    }
    return lines.length > 1 ? lines.join('\n\n') : '';
}

export function buildPromptPackQuery({ history = [], stateContext = '', activeTask = '', failure = '' } = {}) {
    let user = '';
    if (Array.isArray(history)) {
        for (let i = history.length - 1; i >= 0; i--) {
            const msg = history[i];
            if (msg?.role === 'user' && normalizeText(msg.content)) {
                user = msg.content;
                break;
            }
        }
    }
    return [
        user ? `User request: ${user}` : '',
        stateContext ? `State: ${stateContext}` : '',
        activeTask ? `Active task: ${activeTask}` : '',
        failure ? `Recent failure: ${failure}` : '',
    ].filter(Boolean).join('\n');
}

