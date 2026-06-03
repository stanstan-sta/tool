import OpenAIApi from 'openai';
import { toolCallToCommand } from '../agent/commands/index.js';

const VISION_UNSUPPORTED_TOKEN = 'vision_model_unsupported';

function normalizeMessages(turns, systemMessage) {
    const messages = [];
    if (systemMessage) messages.push({ role: 'system', content: systemMessage });
    for (const turn of turns || []) {
        if (!turn || !turn.role) continue;
        let role = turn.role;
        let content = turn.content;
        if (role === 'system') {
            role = 'user';
            content = typeof content === 'string' ? `SYSTEM: ${content}` : content;
        }
        if (!['user', 'assistant', 'tool'].includes(role)) role = 'user';
        messages.push({ role, content });
    }
    return messages.length > 0 ? messages : [{ role: 'user', content: '_' }];
}

function isVisionUnsupported(error) {
    const text = String(error?.message || error?.detail || error || '');
    return /image_url|image input|vision|unsupported image|does not support image/i.test(text);
}

export class LlamaCpp {
    static prefix = 'llamacpp';
    static supportsTools = true;
    static supportsStructuredOutput = true;

    constructor(model_name, url, params) {
        this.model_name = model_name;
        this.params = params || {};
        this.openai = new OpenAIApi({
            baseURL: url || 'http://127.0.0.1:8080/v1',
            apiKey: this.params.apiKey || this.params.api_key || 'llama.cpp',
        });
    }

    async sendRequest(turns, systemMessage, tools = null) {
        const model = this.model_name || this.params.model || 'local-model';
        const messages = normalizeMessages(turns, systemMessage);
        try {
            console.log('Awaiting llama.cpp response from model', model);
            const pack = {
                model,
                messages,
                ...(tools && tools.length > 0 ? { tools } : { stop: '***' }),
                ...this.params,
            };
            delete pack.apiKey;
            delete pack.api_key;
            if (!pack.response_format && this.params.structured_output_schema) {
                pack.response_format = {
                    type: 'json_schema',
                    json_schema: this.params.structured_output_schema,
                };
            }

            const completion = await this.openai.chat.completions.create(pack);
            const choice = completion.choices?.[0];
            if (choice?.finish_reason === 'length') throw new Error('Context length exceeded');
            console.log('Received.');

            if (choice?.message?.tool_calls?.length > 0) {
                const toolCall = choice.message.tool_calls[0];
                const funcName = toolCall.function.name;
                const funcArgs = typeof toolCall.function.arguments === 'string'
                    ? JSON.parse(toolCall.function.arguments || '{}')
                    : (toolCall.function.arguments || {});
                const cmdStr = toolCallToCommand(funcName, funcArgs);
                const textContent = choice.message.content?.trim() || '';
                return cmdStr ? (textContent ? `${textContent} ${cmdStr}` : cmdStr) : textContent;
            }

            let res = choice?.message?.content || '';
            if (res.includes('</think>')) {
                if (!res.includes('<think>')) res = '<think>' + res;
                res = res.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
            }
            return res || 'No response data from llama.cpp.';
        } catch (err) {
            if ((err.message === 'Context length exceeded' || err.code === 'context_length_exceeded') && turns.length > 1) {
                console.log('Context length exceeded, trying again with shorter context.');
                return this.sendRequest(turns.slice(1), systemMessage, tools);
            }
            if (isVisionUnsupported(err)) return VISION_UNSUPPORTED_TOKEN;
            console.log(err);
            return err.message || 'My brain disconnected, try again.';
        }
    }

    async sendVisionRequest(messages, systemMessage, imageBuffer) {
        const imageMessages = [...(messages || [])];
        imageMessages.push({
            role: 'user',
            content: [
                { type: 'text', text: systemMessage },
                {
                    type: 'image_url',
                    image_url: { url: `data:image/jpeg;base64,${imageBuffer.toString('base64')}` },
                },
            ],
        });
        return this.sendRequest(imageMessages, systemMessage);
    }

    async embed() {
        throw new Error('llama.cpp adapter does not implement embeddings.');
    }
}
