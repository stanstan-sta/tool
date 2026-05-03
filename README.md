<h1 align="center">🧠mindcraft⛏️</h1>

<p align="center">Crafting minds for Minecraft with LLMs and <a href="https://prismarinejs.github.io/mineflayer/#/">Mineflayer!</a></p>

<p align="center">
  <a href="FAQ.md">FAQ</a> |
  <a href="https://discord.gg/mp73p35dzC">Discord</a> |
  <a href="minecollab.md">MineCollab</a>
</p>

> [!Caution]
> Do not connect this bot to public servers with coding enabled. Code writing is disabled by default; enable it with `allow_insecure_coding: true` in `settings.js` at your own risk.

---

## 🚀 Minimal Quickstart (Text Guide)

1. **Install Node.js** — [nodejs.org](https://nodejs.org/) (v18 or v20 LTS).
2. **Clone & install** — `git clone <this repo>`, then `npm install` inside the folder.
3. **Set up a model**:
   - **Local (no API key):** Install [Ollama](https://ollama.com/), run `ollama pull sweaterdog/andy-4:micro-q8_0`, and set `"model": "ollama/sweaterdog/andy-4:micro-q8_0"` in `andy.json`.
   - **Cloud:** Rename `keys.example.json` → `keys.json`, add your API key, and set `"model": "gpt-4o"` (or another model) in `andy.json`.
   - **LM Studio:** Start the LM Studio local server and set `"model": "lmstudio/<your-model-name>"` in `andy.json`.
4. **Configure the bot name** — Open `andy.json` and set `"name"` to exactly match your Minecraft account username (important for online servers).
5. **Open your Minecraft world** — In singleplayer, open to LAN (default port `55916`). For online servers, edit `host` and `port` in `settings.js`.
6. **Start the bot** — Run `node main.js` (or `npm start`). Open `http://localhost:8080` to view the live console.
7. **Chat with the bot** — In Minecraft chat, type `<botname> hello` to talk to it.

> **Tip:** To add personality or change the system prompt, edit `andy.json` directly, or use the WebUI at `http://localhost:8080` → click the agent → open Settings.

---

## ⚡ Quick Start — Local Only (no API key required)

> **Requirements:** [Node.js v18 or v20 LTS](https://nodejs.org/) · [Minecraft Java Edition](https://www.minecraft.net/en-us/store/minecraft-java-bedrock-edition-pc) (up to v1.21.11)
>
> If installing Node.js on Windows, check **"Automatically install the necessary tools"** during setup.

### Step 1 — Install & download model

| Platform | Command |
|----------|---------|
| **Windows** | Double-click `SETUP_LOCAL.bat` |
| **macOS / Linux** | `chmod +x setup_local.sh && ./setup_local.sh` |
| **Any (npm)** | `npm run setup-local` |

This will install dependencies and download a small GGUF model (~400 MB) into the `models/` folder automatically.

### Step 2 — Open your Minecraft world

Start a **Singleplayer** world and click **Open to LAN** → **Start LAN World** (default port `55916`).

### Step 3 — Run the bot

| Platform | Command |
|----------|---------|
| **Windows** | Double-click `START_LOCAL.bat` |
| **macOS / Linux** | `./start_local.sh` |
| **Any (npm)** | `npm run start-local` |

Open **http://localhost:8080** in your browser to see the live debug console.

---

## ☁️ Cloud Models (OpenAI, Claude, Gemini, …)

1. Rename `keys.example.json` → `keys.json` and add your API key  
   (or run `npm run setup` to do this automatically).
2. Edit `andy.json` and set `"model"` to your chosen model, e.g. `"gpt-4o"`.
3. Run `npm start`.

---

## 🔧 Configuration

- **Bot settings** — `settings.js` (host, port, auth mode, active profiles …)
- **Bot personality & model** — profile files in `profiles/` (e.g. `andy.json`)
- **Local model** — any `.gguf` file placed in `models/` is auto-detected

### Specifying profiles on the command line

```bash
node main.js --profiles ./profiles/andy.json ./profiles/jill.json
```

---

## 🧩 Fabric Bridge Tutorial (when this repo is **not** a standalone Fabric client)

Mindcraft itself is a Node.js controller.  
The Minecraft **Fabric client is launched separately** (Prism Launcher / Minecraft Launcher), then Mindcraft connects to it through the bridge mod HTTP API.

### What launches what?

- **Launcher / Prism / MultiMC** launches Minecraft + Fabric mods.
- **Mindcraft (`node main.js`)** launches the LLM agent runtime.
- They communicate via `bridge_url` (default `http://localhost:8765`).

### Setup flow

1. Build bridge mod:
   ```bash
   cd fabric-bridge-mod
   ./gradlew build
   ```
2. Copy the generated `mindcraft-bridge-*.jar` to your Fabric `mods/` folder.
3. Install the modified Baritone Fabric jar that this bridge works with in the same `mods/` folder.
4. Launch Minecraft with your Fabric profile first.
5. In this repo, enable Fabric runtime in `settings.js`:
   ```js
   "launch_mode": "fabric_ui", // or "fabric_headless"
   "bridge_url": "http://localhost:8765",
   "bridge_structured_output": true
   ```
6. Use a Fabric bridge profile (example: `profiles/fabric_bridge.json`) and run:
   ```bash
   node main.js --profiles ./profiles/fabric_bridge.json
   ```

### Important note about `.bat` / `.sh`

`START_LOCAL.bat` and `start_local.sh` start the packet/Mineflayer path, not a standalone Fabric game client.  
For Fabric Bridge mode, start Minecraft from your launcher, then start Mindcraft from terminal.

### If you build a fully custom Fabric client (optimization stack)

For true client-side optimization, consider baking in a curated mod stack (verify version compatibility first):

- Sodium — https://github.com/CaffeineMC/sodium-fabric
- Lithium — https://github.com/CaffeineMC/lithium-fabric
- FerriteCore — https://github.com/malte0811/FerriteCore
- Starlight (for supported versions) — https://github.com/PaperMC/Starlight
- C2ME — https://github.com/RelativityMC/C2ME-fabric
- ModernFix — https://github.com/embeddedt/ModernFix
- EntityCulling — https://github.com/tr7zw/EntityCulling
- ImmediatelyFast — https://github.com/RaphiMC/ImmediatelyFast
- Krypton (for supported versions) — https://github.com/astei/krypton

Suggested strategy:
- Keep automation-critical mods (bridge + baritone) minimal and stable.
- Add performance mods incrementally, testing for bridge API and Baritone regressions after each addition.
- Maintain a locked modpack manifest for reproducible behavior.

---

# Model Customization

You can configure project details in `settings.js`. [See file.](settings.js)

You can configure the agent's name, model, and prompts in their profile like `andy.json`. The model can be specified with the `model` field, with values like `model: "gemini-2.5-pro"`. You will need the correct API key for the API provider you choose. See all supported APIs below.

<details>
<summary><strong>⭐ VIEW SUPPORTED APIs ⭐</strong></summary>

| API Name | Config Variable| Docs |
|------|------|------|
| `openai` | `OPENAI_API_KEY` | [docs](https://platform.openai.com/docs/models) |
| `google` | `GEMINI_API_KEY` | [docs](https://ai.google.dev/gemini-api/docs/models/gemini) |
| `anthropic` | `ANTHROPIC_API_KEY` | [docs](https://docs.anthropic.com/claude/docs/models-overview) |
| `xai` | `XAI_API_KEY` | [docs](https://docs.x.ai/docs) |
| `deepseek` | `DEEPSEEK_API_KEY` | [docs](https://api-docs.deepseek.com/) |
| `ollama` (local) | n/a | [docs](https://ollama.com/library) |
| `qwen` | `QWEN_API_KEY` | [Intl.](https://www.alibabacloud.com/help/en/model-studio/developer-reference/use-qwen-by-calling-api)/[cn](https://help.aliyun.com/zh/model-studio/getting-started/models) |
| `mistral` | `MISTRAL_API_KEY` | [docs](https://docs.mistral.ai/getting-started/models/models_overview/) |
| `replicate` | `REPLICATE_API_KEY` | [docs](https://replicate.com/collections/language-models) |
| `groq` (not grok) | `GROQCLOUD_API_KEY` | [docs](https://console.groq.com/docs/models) |
| `huggingface` | `HUGGINGFACE_API_KEY` | [docs](https://huggingface.co/models) |
| `novita` | `NOVITA_API_KEY` | [docs](https://novita.ai/model-api/product/llm-api?utm_source=github_mindcraft&utm_medium=github_readme&utm_campaign=link) |
| `openrouter` | `OPENROUTER_API_KEY` | [docs](https://openrouter.ai/models) |
| `glhf` | `GHLF_API_KEY` | [docs](https://glhf.chat/user-settings/api) |
| `hyperbolic` | `HYPERBOLIC_API_KEY` | [docs](https://docs.hyperbolic.xyz/docs/getting-started) |
| `vllm` | n/a | n/a |
| `cerebras` | `CEREBRAS_API_KEY` | [docs](https://inference-docs.cerebras.ai/introduction) |
| `mercury` | `MERCURY_API_KEY` | [docs](https://www.inceptionlabs.ai/) |

</details>

For more comprehensive model configuration and syntax, see [Model Specifications](#model-specifications).

For local models we support [ollama](https://ollama.com/) and we provide our own finetuned models for you to use. 
To install our models, install ollama and run the following terminal command:
```bash
ollama pull sweaterdog/andy-4:micro-q8_0 && ollama pull embeddinggemma
```

## Online Servers
To connect to online servers your bot will need an official Microsoft/Minecraft account. You can use your own personal one, but will need another account if you want to connect too and play with it. To connect, change these lines in `settings.js`:
```javascript
"host": "111.222.333.444",
"port": 55920,
"auth": "microsoft",

// rest is same...
```
> [!Important]
> The bot's name in the profile.json must exactly match the Minecraft profile name! Otherwise the bot will spam talk to itself.
> If the Minecraft server broadcasts messages under a slightly different username, add it to the `"nicknames"` array in your profile JSON (e.g. `"nicknames": ["Nakano_chan", "Miku"]`) so the bot recognizes those as its own name and won't respond to its own echoed messages.

To use different accounts, Mindcraft will connect with the account that the Minecraft launcher is currently using. You can switch accounts in the launcher, then run `node main.js`, then switch to your main account after the bot has connected.

## Tasks

Tasks automatically start the bot with a prompt and a goal item to acquire or blueprint to construct. To run a simple task that involves collecting 4 oak_logs run 

`node main.js --task_path tasks/basic/single_agent.json --task_id gather_oak_logs`

Here is an example task json format: 

```
{
    "gather_oak_logs": {
      "goal": "Collect at least four logs",
      "initial_inventory": {
        "0": {
          "wooden_axe": 1
        }
      },
      "agent_count": 1,
      "target": "oak_log",
      "number_of_target": 4,
      "type": "techtree",
      "max_depth": 1,
      "depth": 0,
      "timeout": 300,
      "blocked_actions": {
        "0": [],
        "1": []
      },
      "missing_items": [],
      "requires_ctable": false
    }
}
```

The `initial_inventory` is what the bot will have at the start of the episode, `target` refers to the target item and `number_of_target` refers to the number of target items the agent needs to collect to successfully complete the task. 

If you want more optimization and automatic launching of the minecraft world, you will need to follow the instructions in [Minecollab Instructions](minecollab.md#installation)

## Docker Container

If you intend to `allow_insecure_coding`, it is a good idea to run the app in a docker container to reduce risks of running unknown code. This is strongly recommended before connecting to remote servers, although still does not guarantee complete safety.

```bash
docker build -t mindcraft . && docker run --rm --add-host=host.docker.internal:host-gateway -p 8080:8080 -p 3000-3003:3000-3003 -e SETTINGS_JSON='{"auto_open_ui":false,"profiles":["./profiles/gemini.json"],"host":"host.docker.internal"}' --volume ./keys.json:/app/keys.json --name mindcraft mindcraft
```
or simply
```bash
docker-compose up --build
```

When running in docker, if you want the bot to join your local minecraft server, you have to use a special host address `host.docker.internal` to call your localhost from inside your docker container. Put this into your [settings.js](settings.js):

```javascript
"host": "host.docker.internal", // instead of "localhost", to join your local minecraft from inside the docker container
```

To connect to an unsupported minecraft version, you can try to use [viaproxy](services/viaproxy/README.md)

## 📖 Built-in Minecraft Wiki / Cheatsheet

Mindcraft includes an offline Minecraft wiki/cheatsheet (`src/utils/minecraft_wiki.json`) covering recipes, item categories, biomes, mobs, enchantments, and survival mechanics for Minecraft 1.21+. This reduces model token usage by providing quick, reliable answers without relying on the LLM for basic game facts.

### Features

- **`!wiki <query>`** — query the local wiki in-game for instant answers
- **Chest memory** — the agent remembers scanned chest contents by coordinates, categorizes items using the wiki (fuel, food, tools, etc.), and notes if expected items are missing
- **`$WIKI_DATA` prompt placeholder** — injects context-relevant recipe/item info into prompts to reduce hallucinations (opt-in via `wiki_in_prompt: true`)

### Example Usage (in Minecraft chat)

```
<you> Andy !wiki cooked_beef
Andy  WIKI: cooked_beef: raw_beef + fuel -> 1x (furnace). Also works in smoker (2x faster)

<you> Andy !wiki iron_pickaxe
Andy  WIKI: iron_pickaxe: 3x iron_ingot, 2x stick -> 1x (crafting_table)

<you> Andy !wiki forest
Andy  WIKI biome "forest": Dense tree coverage. Resources: oak_log, birch_log, mushrooms, apples, wolves. Mobs: wolf, rabbit, fox

<you> Andy !wiki enchanting
Andy  WIKI mechanic "enchanting": Enchanting adds special abilities to tools/armor. ...
```

### Settings (`settings.js`)

```javascript
"enable_wiki": true,       // enable/disable the !wiki command and wiki features
"wiki_in_prompt": false,   // inject relevant wiki data into prompts (slightly more tokens)
```

### Extending the Wiki

Edit `src/utils/minecraft_wiki.json` to add new recipes, items, or game mechanics. The structure is documented inline:

- `categories` — item groups like `fuel`, `raw_food`, `ores`, `mob_drops`, `redstone`
- `recipes.crafting` — crafting table recipes
- `recipes.smelting` — furnace/smelting recipes
- `recipes.brewing` — potion brewing recipes
- `biomes` — biome descriptions with resources and mobs
- `mechanics` — game mechanics like cooking, farming, mining, enchanting

---

# Bot Profiles

Bot profiles are json files (such as `andy.json`) that define:

1. Bot backend LLMs to use for talking, coding, and embedding.
2. Prompts used to influence the bot's behavior.
3. Examples help the bot perform tasks.

## Model Specifications

LLM models can be specified simply as `"model": "gpt-4o"`, or more specifically with `"{api}/{model}"`, like `"openrouter/google/gemini-2.5-pro"`. See all supported APIs [here](#model-customization).

The `model` field can be a string or an object. A model object must specify an `api`, and optionally a `model`, `url`, and additional `params`. You can also use different models/providers for chatting, coding, vision, embedding, and voice synthesis. See the example below.

```json
"model": {
  "api": "openai",
  "model": "gpt-4o",
  "url": "https://api.openai.com/v1/",
  "params": {
    "max_tokens": 1000,
    "temperature": 1
  }
},
"code_model": {
  "api": "openai",
  "model": "gpt-4",
  "url": "https://api.openai.com/v1/"
},
"vision_model": {
  "api": "openai",
  "model": "gpt-4o",
  "url": "https://api.openai.com/v1/"
},
"embedding": {
  "api": "openai",
  "url": "https://api.openai.com/v1/",
  "model": "text-embedding-ada-002"
},
"speak_model": "openai/tts-1/echo"
```

`model` is used for chat, `code_model` is used for newAction coding, `vision_model` is used for image interpretation, `embedding` is used to embed text for example selection, and `speak_model` is used for voice synthesis. `model` will be used by default for all other models if not specified. Not all APIs support embeddings, vision, or voice synthesis.

OpenRouter defaults to `openai/gpt-4o-mini` when no model is specified. When using OpenRouter, keep the provider namespace in the model name, for example `openai/gpt-4o-mini` or `anthropic/claude-3.5-sonnet`.

All apis have default models and urls, so those fields are optional. The `params` field is optional and can be used to specify additional parameters for the model. It accepts any key-value pairs supported by the api. Is not supported for embedding models.

## Embedding Models

Embedding models are used to embed and efficiently select relevant examples for conversation and coding.

Supported Embedding APIs: `openai`, `google`, `replicate`, `huggingface`, `novita`

If you try to use an unsupported model, then it will default to a simple word-overlap method. Expect reduced performance. We recommend using supported embedding APIs.

## Voice Synthesis Models

Voice synthesis models are used to narrate bot responses and specified with `speak_model`. This field is parsed differently than other models and only supports strings formatted as `"{api}/{model}/{voice}"`, like `"openai/tts-1/echo"`. We only support `openai` and `google` for voice synthesis.

## Specifying Profiles via Command Line

By default, the program will use the profiles specified in `settings.js`. You can specify one or more agent profiles using the `--profiles` argument: `node main.js --profiles ./profiles/andy.json ./profiles/jill.json`


# Contributing

We welcome contributions to the project! We are generally less responsive to github issues, and more responsive to pull requests. Join the [discord](https://discord.gg/mp73p35dzC) for more active support and direction.

While AI generated code is allowed, please vet it carefully. Submitting tons of sloppy code and documentation actively harms development.

## Patches

Some of the node modules that we depend on have bugs in them. To add a patch, change your local node module file and run `npx patch-package [package-name]`

## Development Team
Thanks to all who contributed to the project, especially the official development team: [@MaxRobinsonTheGreat](https://github.com/MaxRobinsonTheGreat), [@kolbytn](https://github.com/kolbytn), [@icwhite](https://github.com/icwhite), [@Sweaterdog](https://github.com/Sweaterdog), [@Ninot1Quyi](https://github.com/Ninot1Quyi), [@riqvip](https://github.com/riqvip), [@uukelele-scratch](https://github.com/uukelele-scratch), [@mrelmida](https://github.com/mrelmida)


## Citation:
This work is published in the paper [Collaborating Action by Action: A Multi-agent LLM Framework for Embodied Reasoning](https://arxiv.org/abs/2504.17950). Please use this citation if you use this project in your research:
```
@article{mindcraft2025,
  title = {Collaborating Action by Action: A Multi-agent LLM Framework for Embodied Reasoning},
  author = {White*, Isadora and Nottingham*, Kolby and Maniar, Ayush and Robinson, Max and Lillemark, Hansen and Maheshwari, Mehul and Qin, Lianhui and Ammanabrolu, Prithviraj},
  journal = {arXiv preprint arXiv:2504.17950},
  year = {2025},
  url = {https://arxiv.org/abs/2504.17950},
}
```

## Contributors

Thanks to everyone who has submitted issues on and off Github, made suggestions, and generally helped make this a better project.

![Contributors](https://contrib.rocks/image?repo=mindcraft-bots/mindcraft)
