import { safeCosineSimilarity } from '../models/embedding_normaliser.js';
import { wordOverlapScore } from '../utils/text.js';

// Default bridge action examples used when the embedding model is not
// available, or as the source of truth for the bridge semantic retriever.
// Each example pairs a user request shape with the canonical JSON
// response shape the bridge expects.
export const BRIDGE_EXAMPLE_LIBRARY = [
    {
        intent: 'chat',
        text: 'How are you doing?',
        snippet: 'Chat only: {"reply":"I am doing well, thanks for asking!"}'
    },
    {
        intent: 'move',
        text: 'Come over here',
        snippet: 'Move: {"reply":"On my way.","actions":[{"type":"move","x":100,"y":64,"z":-200}]}'
    },
    {
        intent: 'follow',
        text: 'Follow me please',
        snippet: 'Follow: {"reply":"Coming.","actions":[{"type":"follow","target":"player_name"}]}'
    },
    {
        intent: 'mine',
        text: 'Mine some iron ore for me',
        snippet: 'Mine explicit block: {"reply":"I will grab some.","actions":[{"type":"mine","target":"iron_ore","count":10}]}'
    },
    {
        intent: 'craft',
        text: 'Make me an iron pickaxe',
        snippet: 'Craft concrete item: {"reply":"I will make one.","actions":[{"type":"craft","item":"iron_pickaxe","count":1}]}'
    },
    {
        intent: 'obtain',
        text: 'Get me some fish',
        snippet: 'General acquisition: {"reply":"I will try to get that.","actions":[{"type":"obtain","item":"cod","count":3}]}'
    },
    {
        intent: 'armor_set',
        text: 'Make me a full set of iron armor',
        snippet: 'Armor set: {"reply":"I will make the set.","actions":[{"type":"craft","item":"iron_helmet","count":1},{"type":"craft","item":"iron_chestplate","count":1},{"type":"craft","item":"iron_leggings","count":1},{"type":"craft","item":"iron_boots","count":1}]}'
    },
    {
        intent: 'smith_ambiguous',
        text: 'Smith my iron armor',
        snippet: 'Ambiguous smithing: {"reply":"Do you want a netherite upgrade or an armor trim? Which template and material?","actions":[]}'
    },
    {
        intent: 'smith_netherite',
        text: 'Upgrade my chestplate to netherite',
        snippet: 'Netherite upgrade: {"reply":"I will upgrade it.","actions":[{"type":"smith","template":"netherite_upgrade_smithing_template","base":"diamond_chestplate","addition":"netherite_ingot","output":"netherite_chestplate"}]}'
    },
    {
        intent: 'smith_trim',
        text: 'Apply a dune trim with gold to my diamond chestplate',
        snippet: 'Armor trim: {"reply":"I will apply that trim.","actions":[{"type":"smith","template":"dune_armor_trim_smithing_template","base":"diamond_chestplate","addition":"gold_ingot"}]}'
    },
    {
        intent: 'combat',
        text: 'Clear out the zombies around me',
        snippet: 'Combat: {"reply":"I will clear them.","actions":[{"type":"clear_hostiles","radius":16}]}'
    },
    {
        intent: 'build_house',
        text: 'Build me a small oak cabin',
        snippet: 'House: {"reply":"Sure.","actions":[{"type":"build_house","template":"cabin","size":"small","material":"oak"}]}'
    },
    {
        intent: 'build_house_min',
        text: 'Make me a house',
        snippet: 'House missing details: {"reply":"Sure.","actions":[{"type":"build_house"}]}'
    },
    {
        intent: 'cancel_replace',
        text: 'Stop following that player and follow me instead',
        snippet: 'Cancel and replace: {"reply":"Okay, switching.","actions":[{"type":"cancel"},{"type":"follow","target":"player_name"}]}'
    },
    {
        intent: 'portal',
        text: 'Go to the nether',
        snippet: 'Portal travel: {"reply":"Heading over.","actions":[{"type":"portal_travel","dimension":"the_nether"}]}'
    },
    {
        intent: 'return',
        text: 'Come back to the overworld',
        snippet: 'Return: {"reply":"Coming back.","actions":[{"type":"return_to_overworld"}]}'
    },
    {
        intent: 'sleep',
        text: 'Go to sleep',
        snippet: 'Sleep: {"reply":"Going to bed.","actions":[{"type":"sleep_try"}]}'
    },
];

export class BridgeExampleRetriever {
    constructor(embedding_model) {
        this.embedding_model = embedding_model || null;
        this.snippet_embeddings = {}; // intent -> vector
        this._documentEmbeddingPromise = null;
        this.ready = false;
    }

    _ensureDocumentEmbeddings() {
        if (!this.embedding_model || Object.keys(this.snippet_embeddings).length === BRIDGE_EXAMPLE_LIBRARY.length) return;
        if (this._documentEmbeddingPromise) return this._documentEmbeddingPromise;

        this._documentEmbeddingPromise = (async () => {
            try {
                const promises = BRIDGE_EXAMPLE_LIBRARY.map(async (ex) => {
                    const embedding = await this.embedding_model.embed(ex.text, { intent: 'document' });
                    if (!Array.isArray(embedding) || embedding.length === 0 || !embedding.every(Number.isFinite)) {
                        throw new Error(`invalid embedding for ${ex.intent}`);
                    }
                    return [ex.intent, embedding];
                });
                const embeddings = await Promise.all(promises);
                this.snippet_embeddings = Object.fromEntries(embeddings);
            } catch (err) {
                // Keep the provider reference: a later request may recover from
                // a transient failure and rebuild the document vectors.
                this.snippet_embeddings = {};
                console.warn('BridgeExampleRetriever: embedding init failed, using word-overlap:', err.message || err);
            } finally {
                this._documentEmbeddingPromise = null;
            }
        })();
        return this._documentEmbeddingPromise;
    }

    async init() {
        if (!this.embedding_model) {
            this.ready = true;
            return;
        }
        try {
            await this._ensureDocumentEmbeddings();
            this.ready = true;
        } catch {
            // _ensureDocumentEmbeddings handles provider failures and retains
            // the model for a later retry.
            this.ready = true;
        }
    }

    pickRelevant(query, k = 3) {
        if (!query || BRIDGE_EXAMPLE_LIBRARY.length === 0) return [];
        let scored;
        const haveDocumentEmbeddings = Object.keys(this.snippet_embeddings).length > 0;
        if (!this.embedding_model || !haveDocumentEmbeddings) {
            scored = BRIDGE_EXAMPLE_LIBRARY.map(ex => ({
                ex,
                score: wordOverlapScore(query, ex.text)
            }));
        } else {
            const qVec = this._lastQueryVec;
            const haveQueryVec = Array.isArray(qVec) && qVec.length > 0;
            scored = BRIDGE_EXAMPLE_LIBRARY.map(ex => {
                const emb = this.snippet_embeddings[ex.intent];
                let score;
                if (haveQueryVec && Array.isArray(emb) && emb.length === qVec.length) {
                    score = safeCosineSimilarity(qVec, emb);
                } else if (!haveQueryVec) {
                    // No query vector at all — word overlap is the consistent metric.
                    score = wordOverlapScore(query, ex.text);
                } else {
                    // Query embedded but this snippet's vector is missing/mismatched.
                    // Rank it last on the cosine scale instead of mixing in a
                    // [0,1] word-overlap score that isn't comparable to cosine.
                    score = -1;
                }
                return { ex, score };
            });
        }
        scored.sort((a, b) => b.score - a.score);
        return scored.filter(s => s.score > 0).slice(0, Math.max(0, k)).map(s => s.ex);
    }

    async getRelevantSnippets(query, k = 3) {
        if (!query) return [];
        if (this.embedding_model) {
            try {
                if (Object.keys(this.snippet_embeddings).length !== BRIDGE_EXAMPLE_LIBRARY.length) {
                    await this._ensureDocumentEmbeddings();
                }
                this._lastQueryVec = await this.embedding_model.embed(query, {
                    intent: 'query',
                    instruction: 'Given a Minecraft player request, retrieve the most relevant bridge action example.'
                });
                if (!Array.isArray(this._lastQueryVec) || this._lastQueryVec.length === 0
                    || !this._lastQueryVec.every(Number.isFinite)) {
                    throw new Error('invalid embedding');
                }
                if (Object.values(this.snippet_embeddings).some(vector => vector.length !== this._lastQueryVec.length)) {
                    this.snippet_embeddings = {};
                    await this._ensureDocumentEmbeddings();
                    if (!Object.values(this.snippet_embeddings).some(vector => vector.length === this._lastQueryVec.length)) {
                        this._lastQueryVec = null;
                    }
                }
            } catch (err) {
                console.warn('BridgeExampleRetriever: query embed failed, falling back:', err.message || err);
                this._lastQueryVec = null;
            }
        } else {
            this._lastQueryVec = null;
        }
        return this.pickRelevant(query, k);
    }
}

export function formatBridgeExamples(snippets) {
    if (!Array.isArray(snippets) || snippets.length === 0) return '';
    const lines = ['BRIDGE EXAMPLES (semantically retrieved):'];
    for (const s of snippets) {
        lines.push(`- ${s.snippet}`);
    }
    return lines.join('\n');
}
