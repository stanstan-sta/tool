const settings = {
    "launch_mode": "fabric_ui", // fabric_ui or fabric_headless. fabric_* starts BridgeAgent runtime.
    "bridge_mode": true, // when true, the agent connects to a Fabric Bridge Mod HTTP server instead of joining Minecraft directly via Mineflayer
    "bridge_url": "http://localhost:8765", // URL of the Fabric Bridge Mod HTTP server
    "bridge_chat_whitelist": [], // list of player names allowed to trigger the bridge agent
    "bridge_chat_blacklist": [], // list of player names blocked from triggering the bridge agent
    "bridge_structured_output": false, // request structured JSON replies for bridge runtime and execute only parsed actions
    "bridge_queue_enabled": true, // enable sequential task queue: actions execute one at a time, advancing on Baritone completion signals. disable for old fire-and-forget behavior

    "auto_open_ui": true, // opens UI in browser on startup

    "base_profile": "assistant", // survival, assistant, creative, or god_mode
    "profiles": [
        "./miku.json",
        // "./profiles/gpt.json",
        // "./profiles/claude.json",
        // "./profiles/gemini.json",
        // "./profiles/llama.json",
        // "./profiles/qwen.json",
        // "./profiles/grok.json",
        // "./profiles/mistral.json",
        // "./profiles/deepseek.json",
        // "./profiles/mercury.json",
        // "./profiles/andy-4.json", // Supports up to 75 messages!
    ],

    "load_memory": false, // load memory from previous session
    "init_message": "say haiii in a shy way", // sends to all on spawn
    "only_chat_with": [], // users that the bots listen to and send general messages to. if empty it will chat publicly

    "speak": false,
    "chat_ingame": true, // bot responses are shown in minecraft chat
    "language": "en", // translate to/from this language
    "render_bot_view": false, // show bot's view in browser at localhost:3000, 3001...

    "allow_insecure_coding": true, // allows newAction command and model can write/run code on your computer. enable at own risk
    "allow_vision": true, // allows vision model to interpret screenshots as inputs
    "blocked_actions" : ["!checkBlueprint", "!checkBlueprintLevel", "!getBlueprint", "!getBlueprintLevel"] , // commands to disable and remove from docs. Ex: ["!setMode"]
    "use_baritone": true, // enable Baritone bridge commands (!baritoneGoto, !baritoneCancel, etc.). Requires a Baritone-enabled Minecraft client or server plugin.

    "code_timeout_mins": -1, // minutes code is allowed to run. -1 for no timeout
    "relevant_docs_count": 5, // number of relevant code function docs to select for prompting. -1 for all

    "max_messages": 50, // max number of messages to keep in context
    "num_examples": 2, // number of examples to give to the model
    "max_commands": -1, // max number of commands that can be used in consecutive responses. -1 for no limit
    "show_command_syntax": "full", // "full", "shortened", or "none"
    "narrate_behavior": true, // chat simple automatic actions ('Picking up item!')
    "chat_bot_messages": true, // publicly chat messages to other bots

    "log_all_prompts": false, // log ALL prompts to file

    "enable_wiki": true, // enable the built-in offline Minecraft wiki/cheatsheet (!wiki command and $WIKI_DATA prompt placeholder)
    "wiki_in_prompt": false, // inject a short wiki summary into agent prompts via $WIKI_DATA (can increase token usage slightly)
};

export default settings;

