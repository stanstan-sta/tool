const settings = {
    "launch_mode": "fabric_ui", // fabric_ui or fabric_headless. fabric_* starts BridgeAgent runtime.
    "bridge_mode": true, // when true, the agent connects to a Fabric Bridge Mod HTTP server instead of joining Minecraft directly via Mineflayer
    "bridge_url": "http://localhost:8765", // URL of the Fabric Bridge Mod HTTP server
    "bridge_chat_whitelist": [], // list of player names allowed to trigger the bridge agent
    "bridge_chat_blacklist": [], // list of player names blocked from triggering the bridge agent
    "bridge_structured_output": false, // request structured JSON replies for bridge runtime and execute only parsed actions
    "bridge_queue_enabled": true, // enable sequential task queue: actions execute one at a time, advancing on Baritone completion signals. disable for old fire-and-forget behavior
    "bridge_prompt_packs_enabled": true, // retrieve compact task-specific bridge guidance with the configured embedding model, falling back to lexical routing
    "bridge_prompt_pack_count": 4, // number of prompt guidance packs to retrieve per bridge LLM turn
    "bridge_prompt_pack_max_chars": 2400, // max characters of retrieved bridge task guidance inserted into the prompt
    "bridge_prompt_packs_compact_core": true, // when retrieved guidance exists, shrink the static bridge prompt to core invariants
    "bridge_proactive_enabled": true, // master switch: false = pure turn-based (respond only to player chat)
    "bridge_auto_defend": true, // armed+healthy bot auto-attacks hostiles that enter range
    "bridge_ambient_enabled": true, // enable ambient ticks: bot self-initiates speech/actions when idle
    "bridge_ambient_vision_enabled": true, // when allow_vision is true, send occasional screenshots to ambient/improvise turns
    "bridge_ambient_vision_min_gap_ms": 180000, // minimum time between ambient screenshots
    "bridge_vision_quality": 0.8, // JPEG quality for bridge screenshots sent to vision models
    "bridge_vision_downscale": 2, // client screenshot downscale factor: 1 = full size, 2 = half size
    "bridge_events_enabled": true, // enable reactive events: nightfall, hostiles, weather, etc.
    "bridge_ambient_budget_per_hour": 4, // max spontaneous lines per hour (token-bucket)
    "bridge_ambient_min_gap_ms": 45000,
    "bridge_ambient_max_gap_ms": 180000,
    "bridge_goal_enabled": true,
    "bridge_goal_tick_ms": 4000,
    "bridge_world_memory_enabled": true,
    "bridge_world_memory_record_ms": 8000,
    "bridge_reward_enabled": true,
    "bridge_survival_reflex_enabled": true,
    "bridge_survival_flee_hp": 6,
    "bridge_skill_library_enabled": true, // Voyager-style: save action batches the outcome verifier confirmed as reusable skills, record failures as lessons, and retrieve both into the prompt
    "bridge_skill_retrieve_count": 3, // max proven plans retrieved per prompt
    "bridge_curriculum_enabled": false, // Voyager-style automatic curriculum: when idle with no goal and no recent player chat, set the next survival milestone as an autonomous goal
    "bridge_curriculum_idle_ms": 120000, // quiet period after the last player chat before the curriculum may start practising
    "bridge_curriculum_max_failures": 3, // failed attempts (lessons) before a milestone is deferred
    "bridge_system_one_shadow": false, // run Decider-2b (System One) beside the active-task evaluator and log both choices; never acts
    "bridge_system_one_active": true, // System One decides continue/cancel/append for mid-task chat and stops tasks instantly; the LLM still writes replies and actions
    "bridge_system_one_min_confidence": 0.6, // below this, active mode defers to the LLM's decision
    "bridge_system_one_url": "http://127.0.0.1:8781", // llama-server serving decider-2b-q8_0.gguf
    "bridge_server_data_enabled": false, // consume server-companion-mod data (server_players/events/facts) when present

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

