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
 * Integration contract with Baritone:
 *   Success -> Baritone logs "[Baritone] All queued tasks complete"
 *   Failure -> Baritone logs "[Baritone] Task failed: <label> - <outcome>"
 */
public class TaskQueue {

    private static final TaskQueue INSTANCE = new TaskQueue();

    public static TaskQueue getInstance() {
        return INSTANCE;
    }

    private record PendingTask(String command, Runnable postAction) {}

    private final Queue<PendingTask> pending = new ConcurrentLinkedQueue<>();

    private volatile String activeCommand = null;
    private volatile Runnable activePostAction = null;
    private volatile boolean paused = false;
    private volatile String lastFailureReason = null;
    private volatile boolean enabled = true;

    private TaskQueue() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            cancelAll();
        }
    }

    public int enqueue(List<String> commands) {
        if (commands == null || commands.isEmpty()) return 0;
        if (!enabled) {
            int fired = 0;
            for (String cmd : commands) {
                if (cmd == null || cmd.isBlank()) continue;
                CommandExecutor.execute(cmd);
                fired++;
            }
            return fired;
        }

        int queued = 0;
        for (String command : commands) {
            if (command == null || command.isBlank()) continue;
            if (isCancelCommand(command)) {
                cancelAll();
                return queued + 1;
            }
            pending.add(new PendingTask(command, null));
            queued++;
        }
        if (activeCommand == null && !paused) {
            dispatchNext();
        }
        return queued;
    }

    public int enqueue(String command) {
        if (command == null || command.isBlank()) return 0;
        return enqueue(List.of(command));
    }

    /**
     * Enqueue a command with a callback owned by that exact command.
     * The callback is copied into activePostAction when the command dispatches,
     * so it is still available when Baritone later reports completion.
     */
    public int enqueueWithCallback(String command, Runnable postAction) {
        if (command == null || command.isBlank()) return 0;
        if (!enabled) {
            CommandExecutor.execute(command);
            runPostActionThenDispatch(postAction);
            return 1;
        }

        pending.add(new PendingTask(command, postAction));
        if (activeCommand == null && !paused) {
            dispatchNext();
        }
        return 1;
    }

    public void onBaritoneComplete() {
        final Runnable action = activePostAction;
        activeCommand = null;
        activePostAction = null;
        paused = false;
        lastFailureReason = null;

        chatDebug("[Bridge] DEBUG: onBaritoneComplete action=" + (action != null ? "present" : "null"));
        if (action != null) {
            runPostActionThenDispatch(action);
        } else if (!pending.isEmpty()) {
            dispatchNext();
        } else {
            chatDebug("[Bridge] DEBUG: no post-action and no pending commands");
        }
    }

    public void onBaritoneFailed(String reason) {
        chatDebug("[Bridge] DEBUG: onBaritoneFailed - " + reason);
        lastFailureReason = reason;
        paused = true;
        activePostAction = null;
        // Keep activeCommand so retry() can resend it.
    }

    public void cancelAll() {
        pending.clear();
        activeCommand = null;
        activePostAction = null;
        paused = false;
        lastFailureReason = null;
        CommandExecutor.execute("#cancel");
    }

    public void resume() {
        paused = false;
        lastFailureReason = null;
        if (activeCommand == null && !pending.isEmpty()) {
            dispatchNext();
        }
    }

    public void skip() {
        if (!paused) return;
        activeCommand = null;
        activePostAction = null;
        paused = false;
        lastFailureReason = null;
        if (!pending.isEmpty()) {
            dispatchNext();
        }
    }

    public void retry() {
        if (!paused || activeCommand == null) return;
        paused = false;
        lastFailureReason = null;
        CommandExecutor.execute(activeCommand);
    }

    public QueueState getQueueState() {
        String status;
        if (!enabled) {
            status = "disabled";
        } else if (paused) {
            status = "paused";
        } else if (activeCommand != null) {
            status = "executing";
        } else if (!pending.isEmpty()) {
            status = "draining";
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

    public record QueueState(
            String active,
            int pending,
            boolean paused,
            String lastFailure,
            String status
    ) {}

    private void dispatchNext() {
        PendingTask next = pending.poll();
        if (next == null) return;
        activeCommand = next.command();
        activePostAction = next.postAction();
        CommandExecutor.execute(next.command());
    }

    private void runPostActionThenDispatch(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            chatDebug("[Bridge] DEBUG: client is null - cannot execute post-action");
            return;
        }
        client.execute(() -> {
            if (action != null) {
                try {
                    chatDebug("[Bridge] DEBUG: executing post-action on client thread...");
                    action.run();
                } catch (Exception e) {
                    System.err.println("[TaskQueue] Post-Baritone action failed: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            if (activeCommand == null && !paused && !pending.isEmpty()) {
                dispatchNext();
            }
        });
    }

    private static boolean isCancelCommand(String command) {
        return "#cancel".equalsIgnoreCase(String.valueOf(command).trim());
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
