import { toSinglePrompt } from '../utils/text.js';
import { getKey } from '../utils/keys.js';
import { HfInference } from "@huggingface/inference";
import { normaliseEmbeddingVector } from './embedding_normaliser.js';

export class HuggingFace {
  static prefix = 'huggingface';
  constructor(model_name, url, params) {
    // Remove 'huggingface/' prefix if present
    this.model_name = typeof model_name === 'string'
      ? model_name.replace('huggingface/', '')
      : null;
    this.url = url;
    this.params = params;

    if (this.url) {
      console.warn("Hugging Face doesn't support custom urls!");
    }

    this.huggingface = new HfInference(getKey('HUGGINGFACE_API_KEY'));
  }

  async sendRequest(turns, systemMessage) {
    const stop_seq = '***';
    // Build a single prompt from the conversation turns
    const prompt = toSinglePrompt(turns, null, stop_seq);
    // Fallback model if none was provided
    const model_name = this.model_name || 'meta-llama/Meta-Llama-3-8B';
    // Combine system message with the prompt
    const input = systemMessage + "\n" + prompt;

    // We'll try up to 5 times in case of partial <think> blocks for DeepSeek-R1 models.
    const maxAttempts = 5;
    let attempt = 0;
    let finalRes = null;

    while (attempt < maxAttempts) {
      attempt++;
      console.log(`Awaiting Hugging Face API response... (model: ${model_name}, attempt: ${attempt})`);
      let res = '';
      try {
        // Consume the streaming response chunk by chunk
        for await (const chunk of this.huggingface.chatCompletionStream({
          model: model_name,
          messages: [{ role: "user", content: input }],
          ...(this.params || {})
        })) {
          res += (chunk.choices[0]?.delta?.content || "");
        }
      } catch (err) {
        console.log(err);
        res = 'My brain disconnected, try again.';
        // Break out immediately; we only retry when handling partial <think> tags.
        break;
      }

      // If the model is DeepSeek-R1, check for mismatched <think> blocks.
        const hasOpenTag = res.includes("<think>");
        const hasCloseTag = res.includes("</think>");

        // If there's a partial mismatch, warn and retry the entire request.
        if ((hasOpenTag && !hasCloseTag)) {
          console.warn("Partial <think> block detected. Re-generating...");
          continue;
        }

        // If both tags are present, remove the <think> block entirely.
        if (hasOpenTag && hasCloseTag) {
          res = res.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
        }

      finalRes = res;
      break; // Exit loop if we got a valid response.
    }

    // If no valid response was obtained after max attempts, assign a fallback.
    if (finalRes == null) {
      console.warn("Could not get a valid <think> block or normal response after max attempts.");
      finalRes = 'I thought too hard, sorry, try again.';
    }
    console.log('Received.');
    console.log(finalRes);
    return finalRes;
  }

  /**
   * Embedding adapter.
   *
   * Supports the Qwen3-Embedding-0.6B model and other HuggingFace feature-
   * extraction models. Qwen3 embeddings are instruction-aware: queries should
   * be prefixed with a task instruction and documents left bare (or vice versa).
   * Callers can pass { intent: 'query' | 'document' } to opt in. Unknown intent
   * values, or when the model is not instruction-aware, fall back to raw text.
   *
   * @param {string|string[]} text
   * @param {{intent?: 'query'|'document', instruction?: string, dim?: number}} [options]
   * @returns {Promise<number[]>}
   */
  async embed(text, options = {}) {
    const model = this.model_name || 'Qwen/Qwen3-Embedding-0.6B';
    const inputs = Array.isArray(text) ? text : String(text);

    const isQwen3 = /Qwen3-?Embedding/i.test(model);
    let effectiveInputs = inputs;
    if (isQwen3) {
      const intent = options.intent === 'query' || options.intent === 'document'
        ? options.intent
        : (Array.isArray(inputs) ? 'document' : 'document');
      const instruction = typeof options.instruction === 'string'
        ? options.instruction
        : (intent === 'query'
          ? 'Given a web search query, retrieve relevant passages that answer the query'
          : '');
      effectiveInputs = Array.isArray(inputs)
        ? inputs.map(t => instruction ? `Instruct: ${instruction}\nQuery: ${t}` : t)
        : (instruction ? `Instruct: ${instruction}\nQuery: ${inputs}` : inputs);
    }

    try {
      const res = await this.huggingface.featureExtraction({
        model,
        inputs: effectiveInputs,
      });
      const expectedDim = Number.isFinite(options.dim) ? options.dim : null;
      // If a single string was sent, the response is [[...]]; flatten to one vector.
      // If an array was sent, the response is a per-input list of vectors.
      if (Array.isArray(text)) {
        return res.map(v => normaliseEmbeddingVector(v, { expectedDim, label: `embed:${model}` }));
      }
      return normaliseEmbeddingVector(res, { expectedDim, label: `embed:${model}` });
    } catch (err) {
      const reason = err && err.message ? err.message : String(err);
      throw new Error(`HuggingFace embed(${model}) failed: ${reason}`);
    }
  }
}
