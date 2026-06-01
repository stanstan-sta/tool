import { getSkillDocs } from './index.js';
import { wordOverlapScore } from '../../utils/text.js';
import { safeCosineSimilarity } from '../../models/embedding_normaliser.js';

export class SkillLibrary {
    constructor(agent, embedding_model) {
        this.agent = agent;
        this.embedding_model = embedding_model || null;
        // doc_text -> vector
        this.skill_docs_embeddings = {};
        // doc_text -> truncated header (used as the embed key)
        this.skill_docs_keys = {};
        this.skill_docs = null;
        this.always_show_skills = ['skills.placeBlock', 'skills.wait', 'skills.breakBlockAt'];
    }
    async initSkillLibrary() {
        const skillDocs = getSkillDocs();
        this.skill_docs = skillDocs;
        if (this.embedding_model) {
            try {
                const embeddingPromises = skillDocs.map((doc) => {
                    return (async () => {
                        const func_name_desc = doc.split('\n').slice(0, 2).join('');
                        this.skill_docs_keys[doc] = func_name_desc;
                        this.skill_docs_embeddings[doc] = await this.embedding_model.embed(func_name_desc);
                    })();
                });
                await Promise.all(embeddingPromises);
            } catch (error) {
                console.warn('Error with embedding model, using word-overlap instead.');
                this.embedding_model = null;
                this.skill_docs_embeddings = {};
            }
        } else {
            // No embedding model: still populate the key map so the fallback
            // path can use the truncated header as the comparison text.
            for (const doc of skillDocs) {
                this.skill_docs_keys[doc] = doc.split('\n').slice(0, 2).join('');
            }
        }
        this.always_show_skills_docs = {};
        for (const skillName of this.always_show_skills) {
            this.always_show_skills_docs[skillName] = this.skill_docs.find(doc => doc.includes(skillName));
        }
    }

    async getAllSkillDocs() {
        return this.skill_docs;
    }

    async getRelevantSkillDocs(message, select_num) {
        if (!message) // use filler message if none is provided
            message = '(no message)';
        let skill_doc_similarities = [];

        if (select_num === -1) {
            skill_doc_similarities = (this.skill_docs || []).map(doc => ({
                doc_key: doc,
                similarity_score: 0
            }));
        }
        else if (!this.embedding_model) {
            // No embedding model: compare against the original (truncated) doc
            // text, not the empty vector map. Iterating skill_docs_embeddings
            // would silently return nothing.
            skill_doc_similarities = (this.skill_docs || []).map(doc => ({
                doc_key: doc,
                similarity_score: wordOverlapScore(message, this.skill_docs_keys[doc] || doc)
            })).sort((a, b) => b.similarity_score - a.similarity_score);
        }
        else {
            let latest_message_embedding = null;
            try {
                latest_message_embedding = await this.embedding_model.embed(message);
                if (!Array.isArray(latest_message_embedding) || latest_message_embedding.length === 0
                    || !latest_message_embedding.every(Number.isFinite)) {
                    throw new Error('invalid embedding');
                }
            } catch (err) {
                console.warn('Skill library embed failed, falling back to word overlap:', err.message || err);
                this.embedding_model = null;
                return this.getRelevantSkillDocs(message, select_num);
            }
            skill_doc_similarities = (this.skill_docs || []).map(doc => {
                const emb = this.skill_docs_embeddings[doc];
                const score = Array.isArray(emb) && emb.length === latest_message_embedding.length
                    ? safeCosineSimilarity(latest_message_embedding, emb)
                    : 0;
                return { doc_key: doc, similarity_score: score };
            }).sort((a, b) => b.similarity_score - a.similarity_score);
        }

        let length = skill_doc_similarities.length;
        if (select_num === -1 || select_num > length) {
            select_num = length;
        }
        // Get initial docs from similarity scores
        let selected_docs = new Set(skill_doc_similarities.slice(0, select_num).map(doc => doc.doc_key));

        // Add always show docs
        Object.values(this.always_show_skills_docs).forEach(doc => {
            if (doc) {
                selected_docs.add(doc);
            }
        });

        let relevant_skill_docs = '#### RELEVANT CODE DOCS ###\nThe following functions are available to use:\n';
        relevant_skill_docs += Array.from(selected_docs).join('\n### ');

        console.log('Selected skill docs:', Array.from(selected_docs).map(doc => {
            const first_line_break = doc.indexOf('\n');
            return first_line_break > 0 ? doc.substring(0, first_line_break) : doc;
        }));
        return relevant_skill_docs;
    }
}
