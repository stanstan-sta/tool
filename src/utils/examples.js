import { safeCosineSimilarity } from '../models/embedding_normaliser.js';
import { stringifyTurns, wordOverlapScore } from './text.js';

export class Examples {
    constructor(model, select_num=2) {
        this.examples = [];
        this.model = model || null;
        this.select_num = select_num;
        this.embeddings = {};
    }

    turnsToText(turns) {
        let messages = '';
        for (let turn of turns) {
            if (turn.role !== 'assistant')
                messages += turn.content.substring(turn.content.indexOf(':')+1).trim() + '\n';
        }
        return messages.trim();
    }

    async load(examples) {
        this.examples = examples;
        if (!this.model) return; // Early return if no embedding model
        if (this.select_num === 0)
            return;

        try {
            // Create array of promises first
            const embeddingPromises = examples.map(example => {
                const turn_text = this.turnsToText(example);
                return this.model.embed(turn_text)
                    .then(embedding => {
                        this.embeddings[turn_text] = embedding;
                    });
            });

            // Wait for all embeddings to complete
            await Promise.all(embeddingPromises);
        } catch (err) {
            console.warn('Error with embedding model, using word-overlap instead.');
            this.model = null;
            this.embeddings = {};
        }
    }

    async getRelevant(turns) {
        if (this.select_num === 0)
            return [];

        let turn_text = this.turnsToText(turns);

        // No embedding model -> always fall back to word overlap.
        if (this.model === null) {
            const scored = this.examples.map(example => ({
                example,
                score: wordOverlapScore(turn_text, this.turnsToText(example))
            }));
            scored.sort((a, b) => b.score - a.score);
            return JSON.parse(JSON.stringify(scored.slice(0, this.select_num).map(s => s.example)));
        }

        // Embedding model exists, but try the call defensively. Any failure
        // (network, mismatch, dim) demotes the model and falls back to word overlap.
        let queryEmbedding = null;
        try {
            queryEmbedding = await this.model.embed(turn_text);
            if (!Array.isArray(queryEmbedding) || queryEmbedding.length === 0 || !queryEmbedding.every(Number.isFinite)) {
                throw new Error('invalid embedding response');
            }
        } catch (err) {
            console.warn('Error with embedding model, using word-overlap instead.');
            this.model = null;
            return this._wordOverlapRelevant(turn_text);
        }

        const scored = this.examples.map(example => {
            const key = this.turnsToText(example);
            const emb = this.embeddings[key];
            const score = Array.isArray(emb) && emb.length === queryEmbedding.length
                ? safeCosineSimilarity(queryEmbedding, emb)
                : wordOverlapScore(turn_text, key);
            return { example, score };
        });
        scored.sort((a, b) => b.score - a.score);
        return JSON.parse(JSON.stringify(scored.slice(0, this.select_num).map(s => s.example)));
    }

    _wordOverlapRelevant(turn_text) {
        const scored = this.examples.map(example => ({
            example,
            score: wordOverlapScore(turn_text, this.turnsToText(example))
        }));
        scored.sort((a, b) => b.score - a.score);
        return JSON.parse(JSON.stringify(scored.slice(0, this.select_num).map(s => s.example)));
    }

    async createExampleMessage(turns) {
        let selected_examples = await this.getRelevant(turns);

        console.log('selected examples:');
        for (let example of selected_examples) {
            console.log('Example:', example[0].content)
        }

        let msg = 'Examples of how to respond:\n';
        for (let i=0; i<selected_examples.length; i++) {
            let example = selected_examples[i];
            msg += `Example ${i+1}:\n${stringifyTurns(example)}\n\n`;
        }
        return msg;
    }
}
