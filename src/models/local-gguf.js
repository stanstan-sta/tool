import { existsSync, readdirSync } from 'fs';
import { resolve } from 'path';

const MODELS_DIR = 'models';

function findDefaultModel() {
    if (!existsSync(MODELS_DIR)) return null;
    const file = readdirSync(MODELS_DIR).find(f => f.toLowerCase().endsWith('.gguf'));
    return file ? `${MODELS_DIR}/${file}` : null;
}

export class LocalGGUF {
    static prefix = 'local-gguf';

    constructor(model_name, _url, params) {
        this.model_path = (model_name && model_name !== 'auto') ? model_name : null;
        this.params = params || {};
        this._llama = null;
        this._model = null;
        this._nodeLlamaCpp = null;
    }

    async _ensureModel() {
        if (this._model) return;

        let nodeLlamaCpp;
        try {
            nodeLlamaCpp = await import('node-llama-cpp');
        } catch {
            throw new Error(
                'node-llama-cpp is not installed. Run `npm run setup-local` to install it.'
            );
        }

        const modelPath = this.model_path || findDefaultModel();
        if (!modelPath) {
            throw new Error(
                `No GGUF model found in "${MODELS_DIR}/". Run \`npm run setup-local\` to download one.`
            );
        }
        if (!existsSync(modelPath)) {
            throw new Error(
                `Model file not found: "${modelPath}". Run \`npm run setup-local\` to download a model.`
            );
        }

        const { getLlama } = nodeLlamaCpp;
        console.log(`[local-gguf] Loading model: ${resolve(modelPath)}`);
        this._llama = await getLlama();
        this._model = await this._llama.loadModel({ modelPath: resolve(modelPath) });
        this._nodeLlamaCpp = nodeLlamaCpp;
        console.log('[local-gguf] Model loaded.');
    }

    async sendRequest(turns, systemMessage) {
        await this._ensureModel();

        const { LlamaChatSession } = this._nodeLlamaCpp;
        const context = await this._model.createContext();
        const session = new LlamaChatSession({
            contextSequence: context.getSequence(),
            systemPrompt: systemMessage,
        });

        // Replay prior conversation pairs so the model has context
        const history = [];
        for (let i = 0; i + 1 < turns.length; i += 2) {
            const u = turns[i];
            const a = turns[i + 1];
            if (u?.role === 'user' && a?.role === 'assistant') {
                history.push({ type: 'user', text: u.content });
                history.push({ type: 'model', response: [a.content] });
            }
        }
        if (history.length > 0) {
            await session.addToHistory(history);
        }

        const lastUser = [...turns].reverse().find(t => t.role === 'user');
        if (!lastUser) {
            await context.dispose();
            return 'No user message found in conversation history.';
        }

        let res = 'My brain disconnected, try again.';
        try {
            res = await session.prompt(lastUser.content, this.params);
        } catch (err) {
            if (err.message?.toLowerCase().includes('context length') && turns.length > 1) {
                await context.dispose();
                return this.sendRequest(turns.slice(1), systemMessage);
            }
            console.error('[local-gguf] Error during inference:', err);
        }

        await context.dispose();
        return res;
    }
}
