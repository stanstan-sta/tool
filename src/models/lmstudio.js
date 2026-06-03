import OpenAIApi from 'openai';
import { strictFormat } from '../utils/text.js';
import { toolCallToCommand } from '../agent/commands/index.js';
const VISION_UNSUPPORTED_TOKEN = 'vision_model_unsupported';

export class LMStudio {
    static prefix = 'lmstudio';
    static supportsTools = true;
    static supportsStructuredOutput = true;

    constructor(model_name, url, params) {
        this.model_name = model_name;
        this.params = params;
        this.openai = new OpenAIApi({
            baseURL: url || 'http://localhost:1234/v1',
            apiKey: 'lm-studio', // LM Studio ignores this but the client requires a non-empty value
        });
    }

    async sendRequest(turns, systemMessage, tools=null) {
        let messages = [{ role: 'system', content: systemMessage }].concat(strictFormat(turns));
        let model = this.model_name || 'andy-4.1';
        let res = null;

        try {
            console.log('Awaiting LM Studio response from model', model);
            const pack = {
                model,
                messages,
                ...(tools && tools.length > 0 ? { tools } : { stop: '***' }),
                ...(this.params || {})
            };
            if (!pack.response_format && this.params?.structured_output_schema) {
                pack.response_format = {
                    type: 'json_schema',
                    json_schema: this.params.structured_output_schema,
                };
            }
            const completion = await this.openai.chat.completions.create(pack);
            if (completion.choices[0].finish_reason === 'length')
                throw new Error('Context length exceeded');
            console.log('Received.');

            const choice = completion.choices[0];
            if (choice.message.tool_calls && choice.message.tool_calls.length > 0) {
                // Native tool call: convert the first tool call to !command(...) format.
                const toolCall = choice.message.tool_calls[0];
                const funcName = toolCall.function.name;
                const funcArgs = typeof toolCall.function.arguments === 'string'
                    ? JSON.parse(toolCall.function.arguments)
                    : (toolCall.function.arguments || {});
                const cmdStr = toolCallToCommand(funcName, funcArgs);
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
                    console.warn(`LM Studio returned unknown tool call: ${funcName}`);
                    res = textContent || 'No response data from LM Studio.';
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
                return await this.sendRequest(turns.slice(1), systemMessage, tools);
            } else if (/image_url|image input|vision|unsupported image|does not support image/i.test(String(err.message || err))) {
                console.log(err);
                res = VISION_UNSUPPORTED_TOKEN;
            } else {
                console.log(err);
                res = 'My brain disconnected, try again.';
            }
        }
        return res;
    }

    async sendVisionRequest(messages, systemMessage, imageBuffer) {
        const imageMessages = [...messages];
        imageMessages.push({
            role: 'user',
            content: [
                { type: 'text', text: systemMessage },
                {
                    type: 'image_url',
                    image_url: { url: `data:image/jpeg;base64,${imageBuffer.toString('base64')}` }
                }
            ]
        });
        return this.sendRequest(imageMessages, systemMessage);
    }

    async embed(text) {
        if (text.length > 8191)
            text = text.slice(0, 8191);
        const embedding = await this.openai.embeddings.create({
            model: this.model_name || 'text-embedding-nomic-embed-text-v1.5',
            input: text,
            encoding_format: 'float',
        });
        return embedding.data[0].embedding;
    }
}
