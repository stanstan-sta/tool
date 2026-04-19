import { strictFormat } from '../utils/text.js';

export class Ollama {
    static prefix = 'ollama';
    constructor(model_name, url, params) {
        this.model_name = model_name;
        this.params = params;
        this.url = url || 'http://127.0.0.1:11434';
        this.chat_endpoint = '/api/chat';
        this.embedding_endpoint = '/api/embeddings';
    }

    resolveModelName(defaultModel) {
        return (this.model_name && this.model_name !== 'auto') ? this.model_name : defaultModel;
    }

    async sendRequest(turns, systemMessage) {
        let model = this.resolveModelName('sweaterdog/andy-4:micro-q8_0');
        let messages = strictFormat(turns);
        messages.unshift({ role: 'system', content: systemMessage });
        const maxAttempts = 5;
        let attempt = 0;
        let finalRes = null;

        while (attempt < maxAttempts) {
            attempt++;
            console.log(`Awaiting local response... (model: ${model}, attempt: ${attempt})`);
            let res = null;
            try {
                let apiResponse = await this.send(this.chat_endpoint, {
                    model: model,
                    messages: messages,
                    stream: false,
                    ...(this.params || {})
                });
                if (apiResponse?.message?.content) {
                    res = apiResponse['message']['content'];
                } else {
                    res = 'No response data from Ollama.';
                }
            } catch (err) {
                if (err.message?.toLowerCase().includes('context length') && turns.length > 1) {
                    console.log('Context length exceeded, trying again with shorter context.');
                    return await this.sendRequest(turns.slice(1), systemMessage);
                } else if (err.status === 404) {
                    res = `Ollama returned 404. Verify the base URL (${this.url}) and that the model "${model}" is available.`;
                } else {
                    console.log(err);
                    res = err.message || 'My brain disconnected, try again.';
                }
            }

            const hasOpenTag = res.includes("<think>");
            const hasCloseTag = res.includes("</think>");

            if ((hasOpenTag && !hasCloseTag)) {
                console.warn("Partial <think> block detected. Re-generating...");
                if (attempt < maxAttempts) continue;
            }
            if (hasCloseTag && !hasOpenTag) {
                res = '<think>' + res;
            }
            if (hasOpenTag && hasCloseTag) {
                res = res.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
            }
            finalRes = res;
            break;
        }

        if (finalRes == null) {
            console.warn("Could not get a valid response after max attempts.");
            finalRes = 'I thought too hard, sorry, try again.';
        }
        return finalRes;
    }

    async embed(text) {
        let model = this.resolveModelName('embeddinggemma');
        let body = { model: model, input: text };
        let res = await this.send(this.embedding_endpoint, body);
        return res['embedding'];
    }

    async send(endpoint, body) {
        const url = new URL(endpoint, this.url);
        let method = 'POST';
        let headers = new Headers({ 'Content-Type': 'application/json' });
        const request = new Request(url, { method, headers, body: JSON.stringify(body) });
        let data = null;
        try {
            const res = await fetch(request);
            const responseText = await res.text();
            if (!res.ok) {
                let detail = responseText;
                try {
                    const parsed = JSON.parse(responseText);
                    if (parsed?.error) {
                        detail = typeof parsed.error === 'string' ? parsed.error : JSON.stringify(parsed.error);
                    }
                } catch (_) {}
                const statusText = res.statusText ? ` ${res.statusText}` : '';
                const message = detail
                    ? `Ollama request failed (${res.status}${statusText}): ${detail}`
                    : `Ollama request failed (${res.status}${statusText}).`;
                const error = new Error(message);
                error.status = res.status;
                error.detail = detail;
                throw error;
            }
            data = responseText ? JSON.parse(responseText) : null;
        } catch (err) {
            console.error('Failed to send Ollama request.');
            console.error(err);
            throw err;
        }
        return data;
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
}
