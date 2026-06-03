import test from 'node:test';
import assert from 'node:assert/strict';

import {
    normaliseEmbeddingVector,
    safeCosineSimilarity
} from '../src/models/embedding_normaliser.js';

import { wordOverlapScore } from '../src/utils/text.js';

import { formatBridgeExamples, BRIDGE_EXAMPLE_LIBRARY, BridgeExampleRetriever } from '../src/bridge/bridge_examples.js';
import {
    BridgePromptPackRetriever,
    buildPromptPackQuery,
    formatBridgePromptPacks,
} from '../src/bridge/bridge_prompt_retriever.js';

import { buildBridgeSystemPrompt } from '../src/bridge/bridge_prompt.js';
import { HuggingFace } from '../src/models/huggingface.js';
import { selectAPI } from '../src/models/_model_map.js';
import { LocalEmbedding } from '../src/models/local-embedding.js';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';

// ─── normaliseEmbeddingVector ─────────────────────────────────────────────

test('normaliser flattens [[...]] to one array', () => {
    const v = normaliseEmbeddingVector([[1, 2, 3]], { expectedDim: 3 });
    assert.deepEqual(v, [1, 2, 3]);
});

test('normaliser passes flat array through', () => {
    const v = normaliseEmbeddingVector([0.5, 0.25, 0.125], { expectedDim: 3 });
    assert.deepEqual(v, [0.5, 0.25, 0.125]);
});

test('normaliser mean-pools nested token arrays', () => {
    // 2 tokens, each length-4
    const v = normaliseEmbeddingVector([
        [[1, 2, 3, 4], [5, 6, 7, 8]]
    ], { expectedDim: 4 });
    assert.deepEqual(v, [3, 4, 5, 6]);
});

test('normaliser extracts provider-body shape', () => {
    const v = normaliseEmbeddingVector({
        data: [{ embedding: [10, 20, 30] }]
    }, { expectedDim: 3 });
    assert.deepEqual(v, [10, 20, 30]);
});

test('normaliser rejects non-finite values', () => {
    assert.throws(() => normaliseEmbeddingVector([1, NaN, 3], { expectedDim: 3 }));
});

test('normaliser rejects dimension mismatch', () => {
    assert.throws(() => normaliseEmbeddingVector([1, 2], { expectedDim: 3 }));
});

test('normaliser rejects empty input', () => {
    assert.throws(() => normaliseEmbeddingVector([], { expectedDim: 3 }));
});

test('normaliser rejects null/undefined', () => {
    assert.throws(() => normaliseEmbeddingVector(null, { expectedDim: 3 }));
    assert.throws(() => normaliseEmbeddingVector(undefined, { expectedDim: 3 }));
});

test('normaliser mean-pools only when all token dims match', () => {
    // ragged inner arrays → throw
    assert.throws(() => normaliseEmbeddingVector([
        [[1, 2], [3, 4, 5]]
    ], { expectedDim: 2 }));
});

// ─── safeCosineSimilarity ──────────────────────────────────────────────────

test('safeCosineSimilarity normal case', () => {
    const a = [1, 0, 0];
    const b = [0, 1, 0];
    assert.equal(safeCosineSimilarity(a, b), 0);
});

test('safeCosineSimilarity identical vectors', () => {
    const a = [3, 4, 0];
    assert.equal(safeCosineSimilarity(a, a), 1);
});

test('safeCosineSimilarity opposite direction', () => {
    const a = [1, 0];
    const b = [-1, 0];
    assert.equal(safeCosineSimilarity(a, b), -1);
});

test('safeCosineSimilarity length mismatch returns 0', () => {
    assert.equal(safeCosineSimilarity([1, 2], [1, 2, 3]), 0);
});

test('safeCosineSimilarity zero magnitude returns 0', () => {
    assert.equal(safeCosineSimilarity([0, 0], [1, 0]), 0);
    assert.equal(safeCosineSimilarity([1, 0], [0, 0]), 0);
});

test('safeCosineSimilarity missing/empty returns 0', () => {
    assert.equal(safeCosineSimilarity(null, [1, 2]), 0);
    assert.equal(safeCosineSimilarity([1, 2], undefined), 0);
    assert.equal(safeCosineSimilarity([], [1]), 0);
});

test('safeCosineSimilarity non-finite values returns 0', () => {
    assert.equal(safeCosineSimilarity([NaN, 1], [1, 0]), 0);
    assert.equal(safeCosineSimilarity([1, 0], [Infinity, 0]), 0);
});

// ─── wordOverlapScore ──────────────────────────────────────────────────────

test('wordOverlapScore null input', () => {
    assert.equal(wordOverlapScore(null, 'hello world'), 0);
    assert.equal(wordOverlapScore('hello world', undefined), 0);
    assert.equal(wordOverlapScore(null, null), 0);
});

test('wordOverlapScore empty input', () => {
    assert.equal(wordOverlapScore('', 'hello'), 0);
    assert.equal(wordOverlapScore('hello', ''), 0);
});

test('wordOverlapScore partial overlap', () => {
    const s = wordOverlapScore('hello world', 'world foo');
    assert(s > 0);
    assert(s < 1);
});

test('wordOverlapScore no overlap', () => {
    assert.equal(wordOverlapScore('abc def', 'ghi jkl'), 0);
});

// ─── formatBridgeExamples ──────────────────────────────────────────────────

test('formatBridgeExamples returns formatted string', () => {
    const snippets = BRIDGE_EXAMPLE_LIBRARY.slice(0, 2);
    const result = formatBridgeExamples(snippets);
    assert(result.includes('BRIDGE EXAMPLES'));
    assert(result.includes(snippets[0].snippet));
    assert(result.includes(snippets[1].snippet));
});

test('formatBridgeExamples empty input returns empty string', () => {
    assert.equal(formatBridgeExamples([]), '');
    assert.equal(formatBridgeExamples(null), '');
    assert.equal(formatBridgeExamples(undefined), '');
});

// ─── BridgeExampleRetriever (word-overlap fallback) ────────────────────────

test('BridgeExampleRetriever fallback returns relevant by word overlap', async () => {
    const retriever = new BridgeExampleRetriever(null);
    await retriever.init();
    assert.equal(retriever.ready, true);

    const snippets = await retriever.getRelevantSnippets('mine diamond ore');
    assert(snippets.length > 0);
    // Should include the mining example
    const hasMine = snippets.some(s => s.intent === 'mine');
    assert(hasMine);
});

test('BridgeExampleRetriever empty query returns empty', async () => {
    const retriever = new BridgeExampleRetriever(null);
    await retriever.init();
    const snippets = await retriever.getRelevantSnippets('');
    assert.deepEqual(snippets, []);
});

test('BridgeExampleRetriever passes document and query intents to embedding model', async () => {
    const calls = [];
    const model = {
        async embed(text, options = {}) {
            calls.push({ text, options });
            if (options.intent === 'query') return [0, 1];
            return String(text).toLowerCase().includes('sleep') ? [0, 1] : [1, 0];
        }
    };
    const retriever = new BridgeExampleRetriever(model);
    await retriever.init();
    const snippets = await retriever.getRelevantSnippets('please go to sleep', 1);

    assert(calls.some(c => c.options.intent === 'document'));
    assert(calls.some(c => c.options.intent === 'query'));
    assert.equal(snippets[0].intent, 'sleep');
});

// ─── BridgePromptPackRetriever ──────────────────────────────────────────────

const TEST_PROMPT_PACKS = [
    {
        id: 'containers',
        title: 'Containers',
        actions: ['container_deposit', 'container_withdraw', 'inspect_screen_with_vision'],
        triggers: ['chest', 'barrel', 'container', 'slots'],
        requires: [],
        priority: 70,
        token_budget: 300,
        content: 'Use open_screen.slots for known container contents. Do not invent unopened container contents.',
    },
    {
        id: 'smithing',
        title: 'Smithing',
        actions: ['smith'],
        triggers: ['smith', 'armor trim', 'netherite upgrade'],
        requires: ['containers'],
        priority: 90,
        token_budget: 400,
        content: 'Smith only with a known template, base, and addition. Ask when the smithing request is ambiguous.',
    },
    {
        id: 'dimension_travel',
        title: 'Dimension Travel',
        actions: ['portal_travel', 'return_to_overworld'],
        triggers: ['nether', 'overworld', 'return'],
        requires: [],
        priority: 80,
        token_budget: 350,
        content: 'Never send coordinates for one dimension while still in another dimension.',
    },
];

test('BridgePromptPackRetriever fallback expands dependencies', async () => {
    const retriever = new BridgePromptPackRetriever(null, TEST_PROMPT_PACKS);
    await retriever.init();
    const packs = await retriever.getRelevantPacks('can you smith my diamond armor?', { k: 1 });
    assert.deepEqual(packs.map(p => p.id), ['smithing', 'containers']);
});

test('BridgePromptPackRetriever uses embedding document and query intents', async () => {
    const calls = [];
    const model = {
        async embed(text, options = {}) {
            calls.push({ text, options });
            if (Array.isArray(text)) {
                return text.map(doc => String(doc).includes('Dimension') ? [0, 1] : [1, 0]);
            }
            return [0, 1];
        }
    };
    const retriever = new BridgePromptPackRetriever(model, TEST_PROMPT_PACKS);
    await retriever.init();
    const packs = await retriever.getRelevantPacks('mine netherrack then come back to me', { k: 1 });

    assert(calls.some(c => c.options.intent === 'document'));
    assert(calls.some(c => c.options.intent === 'query'));
    assert.equal(packs[0].id, 'dimension_travel');
});

test('formatBridgePromptPacks emits bounded retrieved guidance', () => {
    const formatted = formatBridgePromptPacks(TEST_PROMPT_PACKS, { tokenBudget: 500 });
    assert(formatted.includes('BRIDGE TASK GUIDANCE'));
    assert(formatted.includes('Smith only with a known template'));
    assert(formatted.length < 2500);
});

test('buildPromptPackQuery uses latest user request and state', () => {
    const query = buildPromptPackQuery({
        history: [
            { role: 'user', content: 'old request' },
            { role: 'assistant', content: 'ok' },
            { role: 'user', content: 'mine netherrack then come back' },
        ],
        stateContext: 'dimension=minecraft:overworld open_screen=false',
        activeTask: 'none',
    });
    assert(query.includes('mine netherrack then come back'));
    assert(query.includes('dimension=minecraft:overworld'));
});

test('HuggingFace Qwen3 embed uses featureExtraction and query instruction prefix', async () => {
    process.env.HUGGINGFACE_API_KEY = process.env.HUGGINGFACE_API_KEY || 'test-key';
    const hf = new HuggingFace('Qwen/Qwen3-Embedding-0.6B');
    let seen = null;
    hf.huggingface = {
        async featureExtraction(args) {
            seen = args;
            return [[1, 0, 0]];
        }
    };

    const vector = await hf.embed('mine netherrack', {
        intent: 'query',
        instruction: 'Find the relevant bridge example.',
        dim: 3,
    });

    assert.deepEqual(vector, [1, 0, 0]);
    assert.equal(seen.model, 'Qwen/Qwen3-Embedding-0.6B');
    assert.equal(seen.inputs, 'Instruct: Find the relevant bridge example.\nQuery: mine netherrack');
});

test('selectAPI routes HuggingFace org/model embedding ids to huggingface', () => {
    const qwen = selectAPI('Qwen/Qwen3-Embedding-0.6B');
    assert.equal(qwen.api, 'huggingface');
    assert.equal(qwen.model, 'Qwen/Qwen3-Embedding-0.6B');

    const prefixed = selectAPI('huggingface/Qwen/Qwen3-Embedding-0.6B');
    assert.equal(prefixed.api, 'huggingface');
    assert.equal(prefixed.model, 'Qwen/Qwen3-Embedding-0.6B');
});

test('selectAPI routes local embedding profile prefix to local-embedding', () => {
    const profile = selectAPI('local-embedding/Qwen/Qwen3-Embedding-0.6B');
    assert.equal(profile.api, 'local-embedding');
    assert.equal(profile.model, 'Qwen/Qwen3-Embedding-0.6B');
});

test('LocalEmbedding adapter normalises single and batch worker responses', async () => {
    const adapter = new LocalEmbedding('Qwen/Qwen3-Embedding-0.6B', null, { dim: 3 });
    const seen = [];
    adapter._request = async payload => {
        seen.push(payload);
        if (Array.isArray(payload.texts)) {
            return { embedding: [[1, 0, 0], [0, 1, 0]] };
        }
        return { embedding: [[1, 2, 3]] };
    };

    const single = await adapter.embed('mine netherrack', { intent: 'query', instruction: 'Find bridge examples' });
    assert.deepEqual(single, [1, 2, 3]);

    const batch = await adapter.embed(['a', 'b'], { intent: 'document' });
    assert.deepEqual(batch, [[1, 0, 0], [0, 1, 0]]);

    assert.equal(seen[0].intent, 'query');
    assert.equal(seen[0].instruction, 'Find bridge examples');
    assert.equal(seen[0].dim, 3);
    assert.equal(seen[1].intent, 'document');
});

// ─── buildBridgeSystemPrompt with bridgeExamples ─────────────────────────

test('buildBridgeSystemPrompt includes bridgeExamples when provided', () => {
    const settings = { persona_preset: 'miku_nakano', bridge_structured_output: true };
    const examplesText = 'BRIDGE EXAMPLES:\n- {"reply":"test"}';
    const prompt = buildBridgeSystemPrompt(settings, '', null, examplesText);
    assert(prompt.includes(examplesText));
});

test('buildBridgeSystemPrompt includes retrieved task guidance when provided', () => {
    const settings = { persona_preset: 'miku_nakano', bridge_structured_output: true };
    const guidance = 'BRIDGE TASK GUIDANCE (retrieved):\n# Smithing\nAsk when smithing is ambiguous.';
    const prompt = buildBridgeSystemPrompt(settings, '', null, '', guidance);
    assert(prompt.includes(guidance));
});

test('buildBridgeSystemPrompt omits bridgeExamples section when empty', () => {
    const settings = { persona_preset: 'miku_nakano', bridge_structured_output: true };
    const prompt = buildBridgeSystemPrompt(settings, '', null, '');
    // Should not have the bridge examples section if none were passed
    assert(!prompt.includes('BRIDGE EXAMPLES'));
});

test('buildBridgeSystemPrompt includes static examples section', () => {
    const settings = { persona_preset: 'miku_nakano', bridge_structured_output: true };
    const prompt = buildBridgeSystemPrompt(settings, '', null);
    assert(prompt.includes('EXAMPLES:'));
    assert(prompt.includes('Chat only:'));
});

// ─── BRIDGE_EXAMPLE_LIBRARY integrity ──────────────────────────────────────

test('BRIDGE_EXAMPLE_LIBRARY every entry has required fields', () => {
    for (const ex of BRIDGE_EXAMPLE_LIBRARY) {
        assert(typeof ex.intent === 'string' && ex.intent, `intent missing for ${ex.snippet}`);
        assert(typeof ex.text === 'string' && ex.text, `text missing for ${ex.intent}`);
        assert(typeof ex.snippet === 'string' && ex.snippet, `snippet missing for ${ex.intent}`);
    }
});

// ─── Prompt building without persona preset ────────────────────────────────

test('buildBridgeSystemPrompt defaults to generic persona', () => {
    const settings = { bridge_structured_output: true };
    const prompt = buildBridgeSystemPrompt(settings, '');
    assert(prompt.includes('Minecraft through the Fabric bridge'));
    assert(!prompt.includes('Miku Nakano'));
});

test('BridgeAgent refreshes semantic examples into non-structured prompt before prompting', async () => {
    const agent = Object.create(BridgeAgent.prototype);
    agent.name = 'TestBridge';
    agent._promptQueue = [];
    agent._capabilities = null;
    agent.history = {
        memory: '',
        turns: [{ role: 'user', content: 'ADMIN: please sleep' }],
        add() {}
    };
    agent._bridgeExamples = {
        async getRelevantSnippets(query, k) {
            assert.equal(query, 'ADMIN: please sleep');
            assert.equal(k, 3);
            return [{ intent: 'sleep', text: 'Go to sleep', snippet: 'Sleep: {"reply":"Going to bed.","actions":[{"type":"sleep_try"}]}' }];
        }
    };
    agent.prompter = {
        profile: { conversing: '' },
        async promptConvo() {
            return agent.prompter.profile.conversing;
        }
    };

    const response = await agent._runPromptConvoNow(1, 'smoke', agent.history.turns, { mode: 'queue' });
    assert(response.includes('BRIDGE EXAMPLES'));
    assert(response.includes('Sleep: {"reply":"Going to bed."'));
});
