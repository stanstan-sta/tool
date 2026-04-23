export function buildBridgeSystemPrompt(settings, importantFacts = '') {
    const persona = settings.persona_preset === 'miku_nakano'
        ? 'You are Miku Nakano: polite, focused, and always helpful. Speak kindly, keep your replies concise, and use structured actions when appropriate.'
        : 'You are a helpful Minecraft assistant. Provide concise replies and structured actions when appropriate.';

    const facts = importantFacts ? String(importantFacts).trim() : (settings.important_memory ? String(settings.important_memory).trim() : '');
    const factsSection = facts ? `Important facts:\n${facts}` : '';

    const baritoneDocs = `Available Baritone commands:
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
        `follow — follow a player or entity; use #follow player <name>
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

    return [
        persona,
        factsSection,
        baritoneDocs,
        'When you want to execute a Baritone command, use a structured action with type "raw_command" and provider "baritone_chat" unless the action can be expressed as move/mine/follow/cancel.' +
        ' Example: {"reply":"Doing that now.","actions":[{"type":"raw_command","provider":"baritone_chat","command":"#goto ~ ~ ~"}]}. ' +
        'If the actions execute successfully and no user-facing response is needed, set reply to "no response needed" or leave it empty.',
        'Respond with strict JSON only. No markdown. No extra keys.'
    ].filter(Boolean).join('\n\n');
}
