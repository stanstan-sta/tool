// Voyager-style skill library for the bridge runtime.
//
// A "skill" is an action batch that the outcome verifier confirmed actually worked in
// this world (inventory gained what the batch promised). Skills are keyed by the task
// text that produced them, retrieved by similarity for new tasks, and shown to the LLM
// as proven plans. Failed batches become short critic "lessons" attached to the task so
// the next attempt sees what went wrong. Nothing here executes actions: retrieval only
// shapes the prompt, and the existing queue/continuation loop still runs everything.

import { readFileSync, writeFileSync, existsSync } from 'fs';
import { safeCosineSimilarity } from '../models/embedding_normaliser.js';
import { wordOverlapScore } from '../utils/text.js';

const MAX_SKILLS = 300;
const MAX_LESSONS = 100;
const EMBED_INSTRUCTION = 'Given a Minecraft task, retrieve previously successful plans for similar tasks.';

const normItem = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();

export function normaliseTask(text) {
    return String(text || '').toLowerCase().replace(/[^a-z0-9_ ]+/g, ' ').replace(/\s+/g, ' ').trim();
}

// Canonical form of an action batch: stable key order, no bookkeeping fields, and
// position-specific coordinates dropped so a plan generalises beyond where it ran.
export function canonicalActions(actions) {
    const out = [];
    for (const a of actions || []) {
        if (!a || typeof a !== 'object' || !a.type) continue;
        const c = {};
        for (const key of Object.keys(a).sort()) {
            if (['x', 'y', 'z', 'id', 'generation', 'provider'].includes(key)) continue;
            const v = a[key];
            if (v === undefined || v === null || v === '') continue;
            c[key] = typeof v === 'string' && /^(item|target|block)$/.test(key) ? normItem(v) : v;
        }
        out.push(c);
    }
    return out;
}

export function actionSignature(actions) {
    return JSON.stringify(canonicalActions(actions));
}

export class SkillLibrary {
    /**
     * @param {string} filePath JSON persistence path
     * @param {{embed?: (text: string, opts?: object) => Promise<number[]>}|null} embeddingModel
     */
    constructor(filePath, embeddingModel = null) {
        this.filePath = filePath;
        this.embeddingModel = embeddingModel;
        this.data = { skills: [], lessons: [] };
    }

    async _embed(text) {
        if (!this.embeddingModel || !text) return null;
        try {
            const v = await this.embeddingModel.embed(text, { intent: 'query', instruction: EMBED_INSTRUCTION });
            return Array.isArray(v) && v.length > 0 && v.every(Number.isFinite) ? v : null;
        } catch (err) {
            console.warn('SkillLibrary: embed failed, using lexical retrieval:', err.message || err);
            this.embeddingModel = null;
            return null;
        }
    }

    /**
     * Record the verified outcome of one dispatched batch.
     * @param {string} task the request/goal that produced the batch
     * @param {object[]} actions the batch as dispatched
     * @param {{met: boolean, results: object[]}} verification from verifyOutcome
     * @returns {Promise<object|null>} the skill or lesson that was written
     */
    async recordOutcome(task, actions, verification) {
        const taskNorm = normaliseTask(task);
        const canon = canonicalActions(actions);
        if (!taskNorm || canon.length === 0 || !verification) return null;
        const signature = JSON.stringify(canon);
        const now = Date.now();
        let skill = this.data.skills.find(s => s.taskNorm === taskNorm && s.signature === signature);

        if (verification.met) {
            if (!skill) {
                skill = {
                    id: `skill_${now.toString(36)}_${this.data.skills.length}`,
                    task: String(task).trim().slice(0, 200),
                    taskNorm,
                    signature,
                    actions: canon,
                    successes: 0,
                    failures: 0,
                    createdAt: now,
                    embedding: await this._embed(String(task)),
                };
                this.data.skills.push(skill);
            }
            skill.successes += 1;
            skill.lastUsedAt = now;
            this._prune();
            this.save();
            return skill;
        }

        if (skill) {
            skill.failures += 1;
            skill.lastUsedAt = now;
        }
        const missing = (verification.results || []).filter(r => !r.met)
            .map(r => `${r.item} +${r.gained}/${r.expectedGain}`).join(', ');
        const lesson = {
            task: String(task).trim().slice(0, 200),
            taskNorm,
            text: `Plan ${signature.slice(0, 160)} fell short (${missing || 'no gain'}). Try a different approach or gather prerequisites first.`,
            at: now,
            embedding: await this._embed(String(task)),
        };
        this.data.lessons.push(lesson);
        if (this.data.lessons.length > MAX_LESSONS) this.data.lessons.splice(0, this.data.lessons.length - MAX_LESSONS);
        this.save();
        return lesson;
    }

    // A skill is trusted while it has succeeded more often than it has failed.
    static isTrusted(skill) {
        return skill.successes > 0 && skill.successes > skill.failures;
    }

    _score(query, queryVec, entry) {
        if (queryVec && Array.isArray(entry.embedding) && entry.embedding.length === queryVec.length) {
            return safeCosineSimilarity(queryVec, entry.embedding);
        }
        return wordOverlapScore(query, entry.task);
    }

    /**
     * @returns {Promise<{skills: object[], lessons: object[]}>}
     */
    async retrieve(query, { k = 3, lessonK = 2, minScore = 0.35 } = {}) {
        if (!query || (this.data.skills.length === 0 && this.data.lessons.length === 0)) return { skills: [], lessons: [] };
        const queryVec = await this._embed(query);
        const threshold = queryVec ? minScore : 0.2;
        const rank = list => list
            .map(entry => ({ entry, score: this._score(query, queryVec, entry) }))
            .filter(r => Number.isFinite(r.score) && r.score >= threshold)
            .sort((a, b) => b.score - a.score);
        const skills = rank(this.data.skills.filter(SkillLibrary.isTrusted))
            .slice(0, k).map(r => r.entry);
        const lessons = rank(this.data.lessons).slice(0, lessonK).map(r => r.entry);
        return { skills, lessons };
    }

    // Tasks the bot has demonstrably mastered, for the curriculum.
    masteredTasks() {
        return new Set(this.data.skills.filter(SkillLibrary.isTrusted).map(s => s.taskNorm));
    }

    failureCount(task) {
        const t = normaliseTask(task);
        return this.data.lessons.filter(l => l.taskNorm === t).length;
    }

    _prune() {
        if (this.data.skills.length <= MAX_SKILLS) return;
        // Drop untrusted skills first, then the least recently used.
        this.data.skills.sort((a, b) =>
            (SkillLibrary.isTrusted(b) - SkillLibrary.isTrusted(a)) || ((b.lastUsedAt || 0) - (a.lastUsedAt || 0)));
        this.data.skills.length = MAX_SKILLS;
    }

    load() {
        try {
            if (existsSync(this.filePath)) {
                const raw = JSON.parse(readFileSync(this.filePath, 'utf8'));
                if (raw && typeof raw === 'object') {
                    this.data.skills = Array.isArray(raw.skills) ? raw.skills : [];
                    this.data.lessons = Array.isArray(raw.lessons) ? raw.lessons : [];
                }
            }
        } catch { /* ignore corrupt library */ }
        return this.data;
    }

    save() {
        try { writeFileSync(this.filePath, JSON.stringify(this.data)); } catch { /* non-fatal */ }
    }
}

export function formatSkillContext({ skills = [], lessons = [] } = {}) {
    const lines = [];
    if (skills.length) {
        lines.push('PROVEN PLANS (verified to work in this world; reuse or adapt when the task matches):');
        for (const s of skills) {
            lines.push(`- "${s.task}" (${s.successes} ok/${s.failures} failed): ${JSON.stringify({ actions: s.actions })}`);
        }
    }
    if (lessons.length) {
        lines.push('PAST FAILURES ON SIMILAR TASKS (avoid repeating):');
        for (const l of lessons) lines.push(`- "${l.task}": ${l.text}`);
    }
    return lines.join('\n');
}
