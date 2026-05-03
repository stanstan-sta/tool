import OpenAIApi from 'openai';
import { getKey } from '../utils/keys.js';
import { strictFormat } from '../utils/text.js';
import { toolCallToCommand } from '../agent/commands/index.js';

export class OpenRouter {
    static prefix = 'openrouter';
    static supportsTools = true;
    static supportsStructuredOutput = true;

    constructor(model_name, url, params) {
        this.model_name = model_name;
        this.params = params || {};
        this.url = url || 'https://openrouter.ai/api/v1';

        const config = {
            baseURL: this.url,
        };

        const apiKey = getKey('OPENROUTER_API_KEY');
        if (!apiKey) {
            console.error('Error: OPENROUTER_API_KEY not found. Make sure it is set properly.');
        }

        config.apiKey = apiKey;

        this.openai = new OpenAIApi(config);
    }

    resolveModelName(defaultModel = 'openai/gpt-4o-mini') {
        return this.model_name || defaultModel;
    }

    async sendRequest(turns, systemMessage, tools = null, stop_seq = '***') {
        const messages = strictFormat([{ role: 'system', content: systemMessage }, ...turns]);
        const model = this.resolveModelName();

        const pack = {
            model,
            messages,
            ...(tools && tools.length > 0 ? { tools } : { stop: stop_seq }),
            ...(this.params || {})
        };
        if (model.includes('o1') || model.includes('o3') || model.includes('5')) {
            delete pack.stop;
        }

        let res = null;
        try {
            console.log('Awaiting openrouter api response...');
            const completion = await this.openai.chat.completions.create(pack);
            if (!completion?.choices?.[0]) {
                console.error('No completion or choices returned:', completion);
                return 'No response received.';
            }
            if (completion.choices[0].finish_reason === 'length') {
                throw new Error('Context length exceeded');
            }
            console.log('Received.');

            const choice = completion.choices[0];
            if (choice.message.tool_calls && choice.message.tool_calls.length > 0) {
                const toolCall = choice.message.tool_calls[0];
                const funcName = toolCall.function.name;
                let funcArgs = null;
                try {
                    funcArgs = typeof toolCall.function.arguments === 'string'
                        ? JSON.parse(toolCall.function.arguments)
                        : (toolCall.function.arguments || {});
                } catch (err) {
                    console.warn('Failed to parse OpenRouter tool call arguments:', err);
                }
                const cmdStr = funcArgs && typeof funcArgs === 'object' ? toolCallToCommand(funcName, funcArgs) : null;
                const textContent = choice.message.content?.trim() || '';
                const fallbackText = (() => {
                    if (typeof funcArgs === 'string') return funcArgs.trim();
                    if (funcArgs && typeof funcArgs === 'object') {
                        if (Array.isArray(funcArgs.actions) || funcArgs.command) {
                            return JSON.stringify(funcArgs);
                        }
                        if (typeof funcArgs.reply === 'string') return funcArgs.reply.trim();
                        if (typeof funcArgs.text === 'string') return funcArgs.text.trim();
                        if (typeof funcArgs.content === 'string') return funcArgs.content.trim();
                        const stringValues = Object.values(funcArgs).filter(v => typeof v === 'string');
                        if (stringValues.length > 0) return stringValues.join(' ').trim();
                    }
                    return null;
                })();

                if (cmdStr) {
                    res = textContent ? `${textContent} ${cmdStr}` : cmdStr;
                } else if (fallbackText) {
                    res = textContent ? `${textContent} ${fallbackText}` : fallbackText;
                } else {
                    console.warn(`OpenRouter returned unknown tool call: ${funcName}`);
                    res = textContent || 'No response data from OpenRouter.';
                }
            } else {
                res = choice.message.content;
            }

            if (res && res.includes('</think>')) {
                if (!res.includes('<think>')) res = '<think>' + res;
                res = res.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
            }
        } catch (err) {
            if ((err.message === 'Context length exceeded' || err.code === 'context_length_exceeded') && turns.length > 1) {
                console.log('Context length exceeded, trying again with shorter context.');
                return await this.sendRequest(turns.slice(1), systemMessage, tools, stop_seq);
            } else if (err.message?.includes('image_url')) {
                console.log(err);
                res = 'Vision is only supported by certain models.';
            } else {
                console.error('Error while awaiting response:', err);
                res = err.message || 'My brain disconnected, try again.';
            }
        }
        return res;
    }

    async sendVisionRequest(messages, systemMessage, imageBuffer) {
        const imageMessages = [...messages];
        imageMessages.push({
            role: "user",
            content: [
                { type: "text", text: systemMessage },
                {
                    type: "image_url",
                    image_url: {
                        url: `data:image/jpeg;base64,${imageBuffer.toString('base64')}`
                    }
                }
            ]
        });

        return this.sendRequest(imageMessages, systemMessage);
    }

    async embed(text) {
        throw new Error('Embeddings are not supported by Openrouter.');
    }
}