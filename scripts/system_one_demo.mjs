// Demo System One (Decider-2b) against a running llama-server.
//   npm run system-one:serve                      (in another terminal)
//   npm run system-one:demo                       built-in Minecraft cases
//   npm run system-one:demo -- "<state>" "<question>" option1 option2 [...]
import { SystemOne } from '../src/bridge/system_one.js';
import settings from '../settings.js';

const s1 = new SystemOne({ url: settings.bridge_system_one_url, timeoutMs: 5000 });

function show(result, gold) {
    const bars = Object.entries(result.probs)
        .map(([k, v]) => `    ${k.padEnd(22)} ${'#'.repeat(Math.round(v * 30)).padEnd(30)} ${(v * 100).toFixed(0)}%`)
        .join('\n');
    const mark = gold === undefined ? '' : (result.choice === gold ? '  [matches label]' : `  [label was ${gold}]`);
    console.log(`  -> ${result.choice} (${result.ms} ms)${mark}\n${bars}\n`);
}

async function main() {
    const [state, question, ...options] = process.argv.slice(2);
    if (state && question && options.length >= 2) {
        console.log(`State: ${state}\nQuestion: ${question}`);
        show(await s1.decide(state, question, Object.fromEntries(options.map(o => [o, null]))));
    } else {
        const busy = { continue: 'keep doing the current task; the message is chat, praise, or a question',
            cancel_replace: 'stop the current task and do what the player now asks instead',
            append_after_current: 'finish the current task, then do what the player asks' };
        const busyQ = 'What should the bot do about this new message?';
        const cases = [
            [`Bot is busy with: #mine 16 iron_ore (1 more queued).\nSteve says: "nice work!"`, busyQ, busy, 'continue'],
            [`Bot is busy with: #mine 16 iron_ore (1 more queued).\nSteve says: "stop mining and come here right now"`, busyQ, busy, 'cancel_replace'],
            [`Bot is busy with: #mine 16 iron_ore (1 more queued).\nSteve says: "after that, craft me an iron pickaxe"`, busyQ, busy, 'append_after_current'],
            [{ hp: 4, max_hp: 20, hostiles: [{ type: 'creeper', distance: 3 }], task: 'mining' }, 'What should the bot do right now?',
                { flee: 'run away from the danger', continue: 'keep mining', fight: 'attack the mob' }, 'flee'],
            [{ hp: 20, max_hp: 20, hostiles: [], time: 'day', task: 'mining' }, 'What should the bot do right now?',
                { flee: 'run away from the danger', continue: 'keep mining', fight: 'attack the mob' }, 'continue'],
            [{ inventory: { oak_log: 0 }, request: 'Steve wants a crafting table' }, 'What does the bot need first?',
                { gather_wood: 'collect logs', craft_now: 'craft the table immediately', mine_stone: 'mine cobblestone' }, 'gather_wood'],
        ];
        for (const [st, q, c, gold] of cases) {
            console.log(`State: ${typeof st === 'string' ? st.replace('\n', ' | ') : JSON.stringify(st)}\nQuestion: ${q}`);
            show(await s1.decide(st, q, c), gold);
        }
    }
}

main().catch(err => {
    console.error(`System One unavailable at ${settings.bridge_system_one_url}: ${err.message}\nStart it first: npm run system-one:serve`);
    process.exit(1);
});
