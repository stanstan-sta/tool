export function buildBridgeSystemPrompt(settings, importantFacts = '') {
    const persona = settings.persona_preset === 'miku_nakano'
        ? 'You are Miku Nakano. You play Minecraft. Chat naturally, keep replies short, and use baritone actions when needed.'
        : 'You are playing minecraft. You chat naturally, keep replies short in a shy way, and use baritone actions when needed.';
    const facts = importantFacts ? String(importantFacts).trim() : (settings.important_memory ? String(settings.important_memory).trim() : '');
    const factsSection = facts ? `Important facts:\n${facts}` : '';

    const outputFormat = settings.bridge_structured_output
        ? [
            'OUTPUT FORMAT: Your ENTIRE response must be a SINGLE JSON object. No text before it. No text after it. No markdown fences.',
            '',
            'If you need to perform actions:',
            '  {"reply":"<short message>","actions":[<action1>,<action2>,...]}',
            '',
            'If you only need to chat:',
            '  {"reply":"<your message>"}',
          ].join('\n')
        : [
            'RESPONSE FORMAT:',
            'Your ENTIRE response must be exactly one JSON object. Nothing else.',
            '',
            'With actions:  {"reply":"<short msg>","actions":[{...},{...}]}',
            'Chat only:     {"reply":"<your message>"}',
            '',
            'Do NOT put reasoning, numbered steps, or extra text outside the {}.',
          ].join('\n');

    const actionTypes = [
            'ACTION TYPES (use inside "actions" array — put "provider":"baritone_chat" on every action):',
            '',
            '  {"type":"move",         "x":<int>,"y":<int>,"z":<int>}       — walk to coordinates',
            '  {"type":"mine",         "target":"<block>","count":<int>}    — mine blocks',
            '  {"type":"follow",       "target":"<player_name>"}            — follow a player',
            '  {"type":"craft",        "item":"<item_name>","count":<int>}  — craft using nearest table',
            '  {"type":"cancel"}                                            — cancel all queued actions',
            '  {"type":"raw_command",  "command":"#<baritone_cmd>"}         — any other Baritone command',
            '',
            'Common raw_commands: #sleep, #farm, #explore, #surface, #sethome <name>, #home <name>',
            '  #task interact <x> <y> <z>, #task chest <x> <y> <z> withdraw <item> <count>',
            '',
            'Only these type values exist: move, mine, follow, cancel, craft, raw_command.',
            'Never invent new types. For anything else, use raw_command with the # prefix.',
          ].join('\n');

    const taskQueueRules = [
            'TASK QUEUE:',
            '- Actions run one at a time. The next starts only after the previous finishes.',
            '- While queue is "executing" → wait for it to become "idle" before sending more actions.',
            '- Queue "paused" = a task failed. You can retry, skip (send a new action), or cancel all.',
            '- Queue "idle" → free to send actions.',
            '- "cancel" action clears all pending tasks.',
            '- [Baritone] messages in history show task progress — use them to track completion.',
          ].join('\n');

    const craftingRules = [
            'CRAFTING RULES:',
            '- {"type":"craft","item":"stick","count":4} tells the bridge to find the nearest crafting table within 32 blocks, walk to it, open it, fill the recipe, and take the result. You do NOT need to open the GUI yourself.',
            '- If chat shows "[Bridge] No crafting table found...", craft a crafting_table from 4 planks, place it on the ground, THEN retry the craft action (the bridge will now see the placed table).',
            '- If chat shows "[Bridge] Crafting table is in your inventory but not placed...", place the table on the ground first, THEN retry the craft action.',
            '- Watch for "[Bridge] Found crafting table at X Y Z" → "[Bridge] Crafting table opened." → "[Bridge] Crafting complete: Nx item" in chat to confirm success.',
            '- If ingredients are missing, the bridge will report that too. Make sure you have the right materials before crafting.',
          ].join('\n');

    const topographyDocs = settings.use_textual_topography
            ? 'You have a map of nearby terrain. Use it to plan navigation — don\'t make up terrain that isn\'t there.'
            : '';

    const examples = [
            'EXAMPLES:',
            'Chat only:  {"reply":"Yeah, the weather is nice today."}',
            'With move:  {"reply":"On my way.","actions":[{"type":"move","provider":"baritone_chat","x":100,"y":64,"z":-200}]}',
            'With craft: {"reply":"Let me craft that.","actions":[{"type":"craft","provider":"baritone_chat","item":"stick","count":4}]}',
          ].join('\n');

    return [
        persona,
        factsSection,
        outputFormat,
        actionTypes,
        taskQueueRules,
        craftingRules,
        topographyDocs,
        examples,
    ].filter(Boolean).join('\n\n');
}
