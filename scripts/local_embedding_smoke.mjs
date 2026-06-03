import { LocalEmbedding } from '../src/models/local-embedding.js';
import { safeCosineSimilarity } from '../src/models/embedding_normaliser.js';

const model = new LocalEmbedding('Qwen/Qwen3-Embedding-0.6B', null, {
    device: process.env.LOCAL_EMBEDDING_DEVICE || 'auto',
    max_length: Number(process.env.LOCAL_EMBEDDING_MAX_LENGTH || 8192),
    dim: Number(process.env.LOCAL_EMBEDDING_DIM || 1024),
});

const instruction = 'Given a Minecraft player request, retrieve the most relevant bridge action example.';
let exitCode = 0;
try {
    const query = await model.embed('please go to sleep', { intent: 'query', instruction });
    const sleepDoc = await model.embed('Go to sleep', { intent: 'document' });
    const mineDoc = await model.embed('Mine diamond ore', { intent: 'document' });

    const sleepScore = safeCosineSimilarity(query, sleepDoc);
    const mineScore = safeCosineSimilarity(query, mineDoc);

    console.log(JSON.stringify({
        ok: sleepScore > mineScore,
        dimension: query.length,
        sleepScore,
        mineScore,
    }, null, 2));

    if (sleepScore <= mineScore) {
        exitCode = 1;
    }
} finally {
    model.close();
    process.exitCode = exitCode;
}
