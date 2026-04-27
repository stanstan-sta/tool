package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * FIFO task queue for the Fabric Bridge Mod.
 *
 * Commands are dispatched one at a time to the Minecraft client. The queue
 * advances reactively when Baritone emits completion/failure chat messages
 * that {@link StateCollector} detects and routes back here.
 *
 * Integration contract with Baritone (other workspace):
 *   Success → Baritone logs "[Baritone] All queued tasks complete"
 *   Failure → Baritone logs "[Baritone] Task failed: <label> - <outcome>"
 *
 * Post-Baritone actions: some operations (like crafting) need Java code to
 * run after Baritone finishes (e.g. interacting with a container GUI).
 * Use {@link #setPostAction(Runnable)} to schedule such a callback.
 */
public class TaskQueue {

    private static final TaskQueue INSTANCE = new TaskQueue();

    public static TaskQueue getInstance() {
        return INSTANCE;
    }

    private final Queue<String> pending = new ConcurrentLinkedQueue<>();

    private volatile String activeCommand = null;
    private volatile boolean paused = false;
    private volatile String lastFailureReason = null;
    private volatile boolean enabled = true; // toggle via settings
    private volatile Runnable postBaritoneAction = null;

    private TaskQueue() {}

    // ── Public API ──────────────────────────────────────────────────────────

    /** Whether queue management is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            // Drain and release
            cancelAll();
        }
    }

    /**
     * Add a batch of raw commands to the queue. Dispatches the first one
     * immediately if nothing is currently active and the queue is not paused.
     *
     * @return number of commands enqueued (including the one dispatched)
     */
    public int enqueue(List<String> commands) {
        if (commands == null || commands.isEmpty()) return 0;
        if (!enabled) {
            // Queue disabled — fire all immediately (backward-compat)
            for (String cmd : commands) {
                CommandExecutor.execute(cmd);
            }
            return commands.size();
        }

        pending.addAll(commands);
        if (activeCommand == null && !paused) {
            dispatchNext();
        }
        return commands.size();
    }

    /**
     * Add a single command. Convenience wrapper for single-command backward compat.
     */
    public int enqueue(String command) {
        if (command == null || command.isBlank()) return 0;
        return enqueue(List.of(command));
    }

    /**
     * Schedule a Runnable to execute on the Minecraft thread after the
     * currently-active Baritone command completes successfully.
     * Only one post-action can be pending at a time.
     */
    public void setPostAction(Runnable action) {
        this.postBaritoneAction = action;
        chatDebug("[Bridge] DEBUG: setPostAction called — action " + (action != null ? "present" : "null"));
    }

    /**
     * Called when Baritone signals successful completion of the active command.
     * Advances the queue to the next pending command.
     * If a post-action was scheduled, it executes first on the MC thread.
     */
    public void onBaritoneComplete() {
        final Runnable action = postBaritoneAction;
        postBaritoneAction = null;
        activeCommand = null;
        paused = false;
        lastFailureReason = null;

        chatDebug("[Bridge] DEBUG: onBaritoneComplete  action=" + (action != null ? "present" : "null"));

        if (action != null) {
            // Run the post-Baritone action on a daemon thread so that
            // Thread.sleep() calls (needed for GUI timing) don't freeze
            // the game's render thread.
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                Thread worker = new Thread(() -> {
                    chatDebug("[Bridge] DEBUG: executing post-action on worker thread...");
                    try {
                        action.run();
                    } catch (Exception e) {
                        System.err.println("[TaskQueue] Post-Baritone action failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                    // After post-action, advance queue if idle (schedule on main thread)
                    client.execute(() -> {
                        if (activeCommand == null && !paused && !pending.isEmpty()) {
                            dispatchNext();
                        }
                    });
                }, "mindcraft-craft-worker");
                worker.setDaemon(true);
                worker.start();
            } else {
                chatDebug("[Bridge] DEBUG: client is null — cannot execute post-action");
            }
        } else if (!pending.isEmpty()) {
            dispatchNext();
        } else {
            chatDebug("[Bridge] DEBUG: no post-action and no pending commands");
        }
    }

    /**
     * Called when Baritone signals failure of the active command.
     * Pauses the queue so the agent can decide to retry, skip, or cancel.
     */
    public void onBaritoneFailed(String reason) {
        chatDebug("[Bridge] DEBUG: onBaritoneFailed — " + reason);
        lastFailureReason = reason;
        paused = true;
        postBaritoneAction = null; // discard any pending post-action
        // Do NOT clear activeCommand — the agent may want to retry it.
    }

    /** Cancel all pending and active tasks. Sends #cancel to Baritone. */
    public void cancelAll() {
        pending.clear();
        activeCommand = null;
        paused = false;
        lastFailureReason = null;
        postBaritoneAction = null;
        CommandExecutor.execute("#cancel");
    }

    /** Resume from a paused state. Dispatches next queued command if any. */
    public void resume() {
        paused = false;
        lastFailureReason = null;
        if (activeCommand == null && !pending.isEmpty()) {
            dispatchNext();
        }
    }

    /**
     * Skip the currently-failed task and advance to the next queued command.
     * Only meaningful when paused.
     */
    public void skip() {
        if (!paused) return;
        activeCommand = null;
        paused = false;
        lastFailureReason = null;
        postBaritoneAction = null;
        if (!pending.isEmpty()) {
            dispatchNext();
        }
    }

    /** Retry the currently-failed task. Only meaningful when paused. */
    public void retry() {
        if (!paused || activeCommand == null) return;
        paused = false;
        lastFailureReason = null;
        CommandExecutor.execute(activeCommand);
        // activeCommand stays set — will complete on next [Baritone] signal
    }

    // ── State queries ───────────────────────────────────────────────────────

    public QueueState getQueueState() {
        String status;
        if (!enabled) {
            status = "disabled";
        } else if (paused) {
            status = "paused";
        } else if (activeCommand != null) {
            status = "executing";
        } else if (!pending.isEmpty()) {
            status = "draining"; // shouldn't normally happen, but safety net
        } else {
            status = "idle";
        }

        return new QueueState(
                activeCommand,
                pending.size(),
                paused,
                lastFailureReason,
                status
        );
    }

    /** Snapshot of current queue state. */
    public record QueueState(
            String active,
            int pending,
            boolean paused,
            String lastFailure,
            String status
    ) {}

    // ── Internal ────────────────────────────────────────────────────────────

    private void dispatchNext() {
        String next = pending.poll();
        if (next != null) {
            activeCommand = next;
            CommandExecutor.execute(next);
        }
    }

    private static void chatDebug(String msg) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(net.minecraft.text.Text.literal(msg), false);
                    }
                });
            }
        } catch (Throwable ignored) {}
    }
}
