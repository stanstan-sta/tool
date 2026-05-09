# Fabric Bridge + Baritone Mindmap

```mermaid
mindmap
  root((Mindcraft Fabric Bridge))
    WebUI / Chat
      Player sends chat
      WebUI sends commands
      Bridge-only controls
      Shows bridge logs and queue updates
    BridgeAgent Node side
      Poll loop
        GET /state
        Reads new chat messages
        Reads queue status
        Backs off when idle
      Normal chat handling
        Adds state summary to prompt
        LLM returns reply actions commands
        Parses structured JSON or ACTION/COMMAND lines
        Sends chat reply when needed
      Active task handling
        Used only when queue is executing or draining
        Narrow LLM evaluator
        continue
          Casual chat
          No queue mutation
        cancel_replace
          POST /queue/cancel
          Clears continuation state
          Dispatches replacement work
        append_after_current
          Leaves active queue alone
          Queues new work behind current task
      Continuation handling
        Waits for queue completion
        Re-prompts with latest state
        Continues multi-step jobs
      Craft fallback
        Detects missing-material replies
        Can queue gather then craft
        Handles basics like wood planks sticks cobblestone iron coal
    FabricBridge HTTP client
      Health and discovery
        GET /ping
        GET /capabilities
        GET /commands
      State
        GET /state
        GET /queue/state
      Dispatch
        POST /command
        POST /action
        POST /batch
      Queue control
        POST /queue/skip
        POST /queue/resume
        POST /queue/cancel
    Fabric bridge mod
      BridgeHttpServer
        Exposes local HTTP API
        Converts JSON actions to bridge commands
        Queues Baritone and craft work
        Lets chat/message commands execute immediately
      StateCollector
        Player position
        Health hunger XP
        Inventory
        Nearby blocks/entities
        Surface map/topography
        Chat messages
        Queue snapshot
      TaskQueue
        Sequential execution
        Status
          idle
          draining
          executing
          paused
          disabled
        Tracks active command
        Tracks pending count
        Tracks last failure
        Timeout watchdogs
        Baritone idle watcher
        Cancel skip resume
        Normalizes sleep to #sleep
      CommandExecutor
        Raw commands
        Typed actions
        Craft planning
        Client-thread interaction
    Baritone
      Movement
        #goto x y z
        Pathfinds through world
      Mining
        #mine count block
        Finds and mines blocks
      Following
        #follow player name
      Sleeping
        #sleep
      Crafting table navigation
        #craft opens crafting table
        Bridge post-action fills recipe
      Task plan signals
        Started
        Completed
        Failed
        Idle/pending count
    Minecraft game client
      World state
      Player inventory
      Player movement
      Chat stream
      Crafting screen
      Block breaking and interactions
    Current capabilities
      Providers
        baritone_chat
      Action types
        move
        mine
        follow
        cancel
        raw_command
        craft
      Raw commands
        #goto x y z
        #mine count block
        #follow player name
        #craft
        #sleep
        Other Baritone commands through raw_command
      Crafting
        Recipe database in mod
        Opens crafting table via Baritone
        Fills recipe in crafting screen
        Auto-plans prerequisite craft steps
        Supports sticks planks basic tools and many common recipes
      Queue behavior
        Batch actions run sequentially
        Chat still received while busy
        Busy chat is evaluated for interrupt append or continue
        Paused queue uses normal recovery path
    Important boundaries
      LLM decides intent
      Node does not directly control Minecraft
      Fabric mod owns game/client interaction
      Baritone owns pathfinding and long-running automation
      Bridge queue prevents overlapping Baritone tasks
      Crafting still requires reachable crafting table and materials
```

## Short Flow

1. A player chats or the WebUI sends a message.
2. `BridgeAgent` polls Fabric state and decides whether the queue is idle or active.
3. If idle, the normal planning prompt can create replies, actions, and commands.
4. If active, the narrow evaluator chooses `continue`, `cancel_replace`, or `append_after_current`.
5. `FabricBridge` sends work over HTTP to the Fabric mod.
6. `BridgeHttpServer` accepts commands/actions and hands queued work to `TaskQueue`.
7. `TaskQueue` runs one task at a time through `CommandExecutor`.
8. `CommandExecutor` either calls Baritone commands or performs client-side actions like crafting.
9. `StateCollector` reports chat, state, queue status, and failures back to Node.

