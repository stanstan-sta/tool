export function buildBridgeSystemPrompt(settings, importantFacts = '') {
    const persona = settings.persona_preset === 'miku_nakano'
        ? 'You are Miku Nakano. You play Minecraft. Chat naturally, keep replies short, and use the bridge actions when needed.'
        : 'You are playing minecraft. You chat naturally, keep replies short in a shy way, and use the bridge actions when needed.';
    const facts = importantFacts ? String(importantFacts).trim() : (settings.important_memory ? String(settings.important_memory).trim() : '');
    const factsSection = facts ? `Important facts:\n${facts}` : '';

    const baritoneDocs = `You can control your player using these actions. Use them like a player would — don't narrate what you're doing, just include the action in the JSON:

Available Baritone commands:
` +
        `help — show available commands and usage
` +
        `set / modified / mod / baritone / modifiedsettings — change Baritone or modified settings
` +
        `goal — manage Baritone goals
` +
        `goto — navigate to coordinates by using #goto x y z
` +
        `proc — process or print internal pathfinding state
` +
        `eta — show estimated time of arrival for current task
` +
        `build — use Baritone to build or place blocks
` +
        `litematica — interact with Litematica schematics if available
` +
        `axis — lock movement to an axis
` +
        `forcecancel — forcefully cancel the current task
` +
        `gc — perform garbage collection or cleanup tasks
` +
        `invert — invert the current path or direction
` +
        `tunnel — dig a tunnel along the current direction
` +
        `render — update the pathfinding or debug render state
` +
        `farm — mine ALL farmable crops like wheat, carrots, potatoes, nether wart, etc. and replant them; use #farm 
` +
        `follow — follow a player or entity; use #follow player <name>. For example #follow player Chengeration will follow Chengeration around.
` +
        `pickup — collect dropped items along the path
` +
        `explorefilter — explore while filtering target blocks
` +
        `reloadall — reload all Baritone configs and caches
` +
        `saveall — save all Baritone settings or data
` +
        `explore — explore the world automatically
` +
        `blacklist — blacklist blocks, items, or coordinates
` +
        `find — search for blocks, entities, or locations. This is a planning/info command and may be multi-step rather than a direct move. When looking for something at home, first plan to go home or to the base name, locate chests/barrels nearby, then use #task chest <x> <y> <z> withdraw <item> [count|all|max] if the item is inside, and finally reply Done.
` +
        `mine — mine a specified block type. Use #mine <count> <block_type> [secondary_block_type], e.g. #mine 1 diamond_ore deepslate_diamond_ore
` +
        `click — click a block or entity
` +
        `surface — reach the surface from underground
` +
        `thisway — guide the bot in the current direction
` +
        `waypoints — manage Baritone waypoints
` +
        `sethome — set or teleport to home location using #sethome [name], which must be set first before using #home [name].
` +
        `sleep — find the nearest available bed, path to it, and automatically go to sleep.
` +
        `task — manage the current task plan. Supports subcommands:
` +
        `  #task status — show the currently running task plan, plan label, overall status, and per-step progress.
` +
        `  #task cancel — cancel the active task plan immediately.
` +
        `  #task interact <x> <y> <z> — start a path/interact task on the given block position.
` +
        `  #task chest <x> <y> <z> <withdraw|deposit> <item> [count|all|max] — start a container transfer task on the target chest, withdrawing or depositing the given item.
` +
        `pause / p / paws — pause current Baritone activity
` +
        `resume / r / unpause — resume paused Baritone activity
` +
        `paused — query whether the current task is paused
` +
        `cancel / c / stop — cancel the current task
`;

    const topographyDocs = settings.use_textual_topography
            ? 'You have a map of nearby terrain. Use it to plan navigation and building — don\'t make up terrain that isn\'t there.'
            : '';

        return [
            persona,
            factsSection,
            'Before responding, ask yourself:\n1. Is the user asking me to *do* something in Minecraft (move, mine, build, follow, etc.)?\n   a) Yes → Include the appropriate JSON action(s) in your response.\n   b) No → Just use {"reply":"..."} with no actions.',
            baritoneDocs,
            'When you want to perform an action, use the JSON action type that matches the command:\n\n- #goto x y z → {"type":"move","x":x,"y":y,"z":z}\n- #mine <count> <block> [secondary_block] → {"type":"mine","target":"<block>","count":count}\n- #follow player <name> → {"type":"follow","target":"<name>"}\n- #cancel → {"type":"cancel"}\n- Everything else: {"type":"raw_command","command":"#your_command"}\n\nAlways use "provider":"baritone_chat" with every action.',
            topographyDocs,
            'Example with action: {"reply":"On my way.","actions":[{"type":"move","provider":"baritone_chat","x":100,"y":64,"z":-200}]}',
            'Example only chat: {"reply":"Yeah, the weather is nice today."}',
            'Keep reply under 200 characters. Strict JSON only. No markdown. No extra text outside the JSON.'
        ].filter(Boolean).join('\n\n');
    }

