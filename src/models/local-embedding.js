import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { normaliseEmbeddingVector } from './embedding_normaliser.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const DEFAULT_MODEL = 'Qwen/Qwen3-Embedding-0.6B';
const DEFAULT_WORKER = resolve(__dirname, '../../services/local_embedding_worker.py');

export class LocalEmbedding {
    static prefix = 'local-embedding';

    constructor(model_name, _url, params) {
        this.model_name = model_name && model_name !== 'auto' ? model_name : DEFAULT_MODEL;
        this.params = params || {};
        this.python = this.params.python || process.env.LOCAL_EMBEDDING_PYTHON || 'python';
        this.workerPath = this.params.worker_path || DEFAULT_WORKER;
        this.device = this.params.device || process.env.LOCAL_EMBEDDING_DEVICE || 'auto';
        this.maxLength = Number(this.params.max_length || process.env.LOCAL_EMBEDDING_MAX_LENGTH || 8192);
        this.dim = this.params.dim ?? this.params.dimension ?? null;
        this.localFilesOnly = this.params.local_files_only === true
            || String(process.env.LOCAL_EMBEDDING_LOCAL_FILES_ONLY || '').toLowerCase() === 'true';
        this._proc = null;
        this._ready = null;
        this._seq = 0;
        this._pending = new Map();
        this._stderrTail = [];
    }

    async sendRequest() {
        throw new Error('LocalEmbedding is an embedding-only adapter.');
    }

    async embed(text, options = {}) {
        const isBatch = Array.isArray(text);
        const response = await this._request({
            texts: isBatch ? text.map(t => String(t ?? '')) : String(text ?? ''),
            intent: options.intent || 'document',
            instruction: options.instruction,
            dim: options.dim ?? this.dim,
        });
        const expectedDim = Number.isFinite(options.dim) ? options.dim
            : (Number.isFinite(this.dim) ? this.dim : null);
        if (isBatch) {
            if (!Array.isArray(response.embedding)) {
                throw new Error('local-embedding: batch response was not an array');
            }
            return response.embedding.map((v, i) => normaliseEmbeddingVector(v, {
                expectedDim,
                label: `local-embedding:${this.model_name}[${i}]`,
            }));
        }
        return normaliseEmbeddingVector(response.embedding, {
            expectedDim,
            label: `local-embedding:${this.model_name}`,
        });
    }

    async _request(payload) {
        await this._ensureWorker();
        const id = ++this._seq;
        return await new Promise((resolve, reject) => {
            this._pending.set(id, { resolve, reject });
            this._proc.stdin.write(JSON.stringify({ id, ...payload }) + '\n', err => {
                if (!err) return;
                this._pending.delete(id);
                reject(err);
            });
        });
    }

    async _ensureWorker() {
        if (this._ready) return this._ready;
        this._ready = new Promise((resolveReady, rejectReady) => {
            const args = [
                this.workerPath,
                '--model', this.model_name,
                '--device', this.device,
                '--max-length', String(this.maxLength),
            ];
            if (this.localFilesOnly) args.push('--local-files-only');

            const proc = spawn(this.python, args, {
                cwd: resolve(__dirname, '../..'),
                stdio: ['pipe', 'pipe', 'pipe'],
                windowsHide: true,
            });
            this._proc = proc;

            let settled = false;
            const settleReady = () => {
                if (settled) return;
                settled = true;
                resolveReady();
            };

            const stdout = createInterface({ input: proc.stdout });
            stdout.on('line', line => this._handleLine(line));
            proc.once('spawn', settleReady);

            proc.stderr.on('data', data => {
                const text = data.toString();
                this._stderrTail.push(text.trim());
                if (this._stderrTail.length > 20) this._stderrTail.shift();
                if (text.includes('[local-embedding] model ready')) settleReady();
                process.stderr.write(text);
            });

            proc.once('error', err => {
                if (!settled) {
                    settled = true;
                    rejectReady(err);
                }
                this._rejectAll(err);
            });

            proc.once('exit', (code, signal) => {
                const detail = this._stderrTail.filter(Boolean).slice(-5).join('\n');
                const err = new Error(
                    `local-embedding worker exited (code=${code}, signal=${signal})${detail ? `\n${detail}` : ''}`
                );
                if (!settled) {
                    settled = true;
                    rejectReady(err);
                }
                this._proc = null;
                this._ready = null;
                this._rejectAll(err);
            });
        });
        return this._ready;
    }

    _handleLine(line) {
        let msg;
        try {
            msg = JSON.parse(line);
        } catch {
            process.stderr.write(`[local-embedding] invalid worker JSON: ${line}\n`);
            return;
        }
        const pending = this._pending.get(msg.id);
        if (!pending) return;
        this._pending.delete(msg.id);
        if (msg.ok) {
            pending.resolve(msg);
        } else {
            pending.reject(new Error(msg.error || 'local embedding request failed'));
        }
    }

    _rejectAll(err) {
        for (const pending of this._pending.values()) pending.reject(err);
        this._pending.clear();
    }

    close() {
        if (!this._proc) return;
        const proc = this._proc;
        this._proc = null;
        this._ready = null;
        try {
            proc.stdin.end();
        } catch {}
        try {
            proc.kill();
        } catch {}
    }
}
