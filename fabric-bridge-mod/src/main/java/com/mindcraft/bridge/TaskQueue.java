package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FIFO task queue for the Fabric Bridge Mod.
 *
 * Each queued entry declares how it completes. This is important because normal
 * Baritone commands, Baritone task-plan commands, bridge-side craft actions, and
 * cancel commands do not share one lifecycle.
 */
public class TaskQueue {

    private static final TaskQueue INSTANCE = new TaskQueue();

    public static TaskQueue getInstance() {
        return INSTANCE;
    }

    public enum TaskKind {
        BARITONE_TASK,
        BRIDGE_CRAFT,
        IMMEDIATE,
        RAW_BARITONE
    }

    public enum CompletionPolicy {
        BARITONE_TASK_CHAT,
        BRIDGE_CALLBACK,
        IMMEDIATE,
        UNTRACKED_TIMEOUT
    }

    private record PendingTask(
            long id,
            String command,
            TaskKind kind,
            CompletionPolicy completion,
            Runnable postAction,
            long timeoutMs
    ) {}

    private final Queue<PendingTask> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong ids = new AtomicLong(1);

    private volatile PendingTask activeTask = null;
    private volatile boolean activePostActionStarted = false;
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
        discardPausedFailure();

        int queued = 0;
        for (String command : commands) {
            if (command == null || command.isBlank()) continue;
            pending.add(classify(command, null));
            queued++;
        }
        dispatchIfIdle();
        return queued;
    }

    public int enqueue(String command) {
        if (command == null || command.isBlank()) return 0;
        return enqueue(List.of(command));
    }

    public int enqueueWithCallback(String command, Runnable postAction) {
        if (command == null || command.isBlank()) return 0;
        if (!enabled) {
            CommandExecutor.execute(command);
            runPostAction(postAction);
            return 1;
        }
        discardPausedFailure();

        pending.add(classify(command, postAction));
        dispatchIfIdle();
        return 1;
    }

    public void onBaritoneComplete() {
        PendingTask active = activeTask;
        if (active == null) {
            chatDebug("[Bridge] DEBUG: ignored Baritone complete with no active task");
            return;
        }

        if (active.completion() == CompletionPolicy.BARITONE_TASK_CHAT) {
            completeActiveId(active.id(), null);
            return;
        }

        if (active.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
            runActivePostActionOnce(active.id());
            return;
        }

        chatDebug("[Bridge] DEBUG: ignored Baritone complete for " + active.kind()
                + " command=" + active.command());
    }

    public void onBaritoneFailed(String reason) {
        PendingTask active = activeTask;
        if (active == null) {
            chatDebug("[Bridge] DEBUG: ignored Baritone failure with no active task - " + reason);
            return;
        }

        if (active.completion() == CompletionPolicy.BARITONE_TASK_CHAT
                || active.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
            chatDebug("[Bridge] DEBUG: onBaritoneFailed - " + reason);
            lastFailureReason = reason;
            paused = true;
            activePostActionStarted = false;
            return;
        }

        chatDebug("[Bridge] DEBUG: ignored Baritone failure for " + active.kind()
                + " command=" + active.command() + " - " + reason);
    }

    public void cancelAll() {
        pending.clear();
        activeTask = null;
        activePostActionStarted = false;
        paused = false;
        lastFailureReason = null;
        CommandExecutor.execute("#cancel");
    }

    public boolean discardPausedFailure() {
        if (!paused) return false;
        pending.clear();
        activeTask = null;
        activePostActionStarted = false;
        paused = false;
        lastFailureReason = null;
        return true;
    }

    public boolean completeActiveIf(String command) {
        PendingTask active = activeTask;
        if (command == null || active == null) return false;
        if (!active.command().trim().equalsIgnoreCase(command.trim())) return false;
        return completeActiveId(active.id(), null);
    }

    public void resume() {
        paused = false;
        lastFailureReason = null;
        if (activeTask == null) {
            dispatchIfIdle();
        } else {
            CommandExecutor.execute(activeTask.command());
        }
    }

    public void skip() {
        if (!paused) return;
        activeTask = null;
        activePostActionStarted = false;
        paused = false;
        lastFailureReason = null;
        dispatchIfIdle();
    }

    public void retry() {
        if (!paused || activeTask == null) return;
        paused = false;
        lastFailureReason = null;
        CommandExecutor.execute(activeTask.command());
    }

    public QueueState getQueueState() {
        PendingTask active = activeTask;
        String status;
        if (!enabled) {
            status = "disabled";
        } else if (paused) {
            status = "paused";
        } else if (active != null) {
            status = "executing";
        } else if (!pending.isEmpty()) {
            status = "draining";
        } else {
            status = "idle";
        }

        return new QueueState(
                active == null ? null : active.command(),
                active == null ? null : active.kind().name(),
                active == null ? null : active.completion().name(),
                pending.size(),
                paused,
                lastFailureReason,
                status
        );
    }

    public record QueueState(
            String active,
            String kind,
            String completion,
            int pending,
            boolean paused,
            String lastFailure,
            String status
    ) {}

    public boolean hasQueuedOrActiveBridgeCraft() {
        PendingTask active = activeTask;
        if (active != null && active.kind() == TaskKind.BRIDGE_CRAFT) {
            return true;
        }
        for (PendingTask task : pending) {
            if (task.kind() == TaskKind.BRIDGE_CRAFT) {
                return true;
            }
        }
        return false;
    }

    private PendingTask classify(String command, Runnable postAction) {
        String trimmed = String.valueOf(command).trim();
        long id = ids.getAndIncrement();

        if (isCancelCommand(trimmed)) {
            return new PendingTask(id, trimmed, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0);
        }

        if (postAction != null || trimmed.equalsIgnoreCase("#craft")) {
            return new PendingTask(id, trimmed, TaskKind.BRIDGE_CRAFT,
                    CompletionPolicy.BRIDGE_CALLBACK, postAction, 90_000L);
        }

        if (trimmed.regionMatches(true, 0, "#task ", 0, 6)) {
            return new PendingTask(id, trimmed, TaskKind.BARITONE_TASK,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, 0);
        }

        return new PendingTask(id, trimmed, TaskKind.RAW_BARITONE,
                CompletionPolicy.UNTRACKED_TIMEOUT, null, timeoutFor(trimmed));
    }

    private long timeoutFor(String command) {
        String lower = command.toLowerCase();
        if (lower.startsWith("#mine ")) return 180_000L;
        if (lower.startsWith("#explore") || lower.startsWith("#farm")) return 120_000L;
        return 30_000L;
    }

    private void dispatchIfIdle() {
        if (activeTask == null && !paused) {
            dispatchNext();
        }
    }

    private void dispatchNext() {
        PendingTask next = pending.poll();
        if (next == null) return;
        activeTask = next;
        activePostActionStarted = false;
        CommandExecutor.execute(next.command());

        if (next.completion() == CompletionPolicy.IMMEDIATE) {
            completeActiveId(next.id(), null);
        } else if (next.completion() == CompletionPolicy.UNTRACKED_TIMEOUT) {
            startTimeoutWatcher(next);
        } else if (next.completion() == CompletionPolicy.BRIDGE_CALLBACK
                || next.completion() == CompletionPolicy.BARITONE_TASK_CHAT) {
            startBaritonePlanWatcher(next);
            if (next.completion() == CompletionPolicy.BRIDGE_CALLBACK && next.timeoutMs() > 0) {
                startTimeoutWatcher(next);
            }
        }
    }

    private void startTimeoutWatcher(PendingTask task) {
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(task.timeoutMs());
            } catch (InterruptedException ignored) {}
            completeActiveId(task.id(), "timeout");
        }, "mindcraft-task-timeout-" + task.id());
        watcher.setDaemon(true);
        watcher.start();
    }

    private void startBaritonePlanWatcher(PendingTask task) {
        Thread watcher = new Thread(() -> {
            boolean sawPending = false;
            long start = System.currentTimeMillis();
            sleepQuietly(300);

            while (true) {
                PendingTask active = activeTask;
                if (active == null || active.id() != task.id() || paused) return;

                Integer pendingCount = readBaritoneTaskPlanPendingCount();
                if (pendingCount == null) return;
                if (pendingCount > 0) {
                    sawPending = true;
                }

                long elapsed = System.currentTimeMillis() - start;
                if (pendingCount == 0 && (sawPending || elapsed > 1_000L)) {
                    if (task.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
                        chatDebug("[Bridge] DEBUG: Baritone task plan idle; starting bridge callback for "
                                + task.command());
                        runActivePostActionOnce(task.id());
                    } else {
                        completeActiveId(task.id(), "baritone-idle");
                    }
                    return;
                }

                sleepQuietly(100);
            }
        }, "mindcraft-baritone-plan-watch-" + task.id());
        watcher.setDaemon(true);
        watcher.start();
    }

    private Integer readBaritoneTaskPlanPendingCount() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            if (baritone == null) return null;
            Object taskPlanProcess = baritone.getClass().getMethod("getTaskPlanProcess").invoke(baritone);
            if (taskPlanProcess == null) return null;
            Object pending = taskPlanProcess.getClass().getMethod("pendingCount").invoke(taskPlanProcess);
            return pending instanceof Number ? ((Number) pending).intValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private boolean completeActiveId(long id, String reason) {
        PendingTask active = activeTask;
        if (active == null || active.id() != id) return false;
        activeTask = null;
        activePostActionStarted = false;
        paused = false;
        lastFailureReason = null;
        if (reason != null) {
            chatDebug("[Bridge] DEBUG: completed " + active.command() + " by " + reason);
        }
        dispatchIfIdle();
        return true;
    }

    private void runActivePostActionOnce(long id) {
        PendingTask active = activeTask;
        if (active == null || active.id() != id) return;
        if (active.postAction() == null || activePostActionStarted) return;
        activePostActionStarted = true;
        runPostAction(active.postAction());
    }

    private void runPostAction(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            chatDebug("[Bridge] DEBUG: client is null - cannot execute post-action");
            return;
        }
        client.execute(() -> {
            if (action != null) {
                try {
                    action.run();
                } catch (Exception e) {
                    System.err.println("[TaskQueue] Post-Baritone action failed: " + e.getMessage());
                    e.printStackTrace();
                }
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
