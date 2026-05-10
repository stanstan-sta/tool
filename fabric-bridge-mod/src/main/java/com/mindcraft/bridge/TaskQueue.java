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
            long timeoutMs,
            long settleMs
    ) {}

    private final Queue<PendingTask> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong ids = new AtomicLong(1);

    private volatile PendingTask activeTask = null;
    private volatile boolean activePostActionStarted = false;
    private volatile boolean activeSettleStarted = false;
    private volatile boolean paused = false;
    private volatile String lastFailureReason = null;
    private volatile boolean enabled = true;
    private volatile long lastActivityMs = System.currentTimeMillis();

    private TaskQueue() {}

    public long getLastActivityMs() {
        return lastActivityMs;
    }

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
        lastActivityMs = System.currentTimeMillis();
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
        lastActivityMs = System.currentTimeMillis();
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
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            StateCollector.pushWorldEvent("queue_failed", active.command() + " - " + reason);
            return;
        }

        chatDebug("[Bridge] DEBUG: ignored Baritone failure for " + active.kind()
                + " command=" + active.command() + " - " + reason);
    }

    public void cancelAll() {
        pending.clear();
        activeTask = null;
        activePostActionStarted = false;
        activeSettleStarted = false;
        paused = false;
        lastFailureReason = null;
        lastActivityMs = System.currentTimeMillis();
        CommandExecutor.execute("#cancel");
    }

    public boolean discardPausedFailure() {
        if (!paused) return false;
        pending.clear();
        activeTask = null;
        activePostActionStarted = false;
        activeSettleStarted = false;
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

    public boolean failActiveIf(String command, String reason) {
        PendingTask active = activeTask;
        if (command == null || active == null) return false;
        if (!active.command().trim().equalsIgnoreCase(command.trim())) return false;
        chatDebug("[Bridge] DEBUG: bridge task failed - " + reason);
        lastFailureReason = reason == null ? "unknown" : reason;
        paused = true;
        activePostActionStarted = false;
        activeSettleStarted = false;
        lastActivityMs = System.currentTimeMillis();
        StateCollector.pushWorldEvent("queue_failed", active.command() + " - " + reason);
        return true;
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
        activeSettleStarted = false;
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
        if (trimmed.equalsIgnoreCase("#task sleep") || trimmed.equalsIgnoreCase("sleep")) {
            trimmed = "#sleep";
        }
        long id = ids.getAndIncrement();

        if (isCancelCommand(trimmed)) {
            return new PendingTask(id, trimmed, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0, 0);
        }

        if (postAction != null || trimmed.equalsIgnoreCase("#craft")) {
            return new PendingTask(id, trimmed, TaskKind.BRIDGE_CRAFT,
                    CompletionPolicy.BRIDGE_CALLBACK, postAction, 90_000L, settleFor(trimmed));
        }

        if (trimmed.regionMatches(true, 0, "#task ", 0, 6)) {
            return new PendingTask(id, trimmed, TaskKind.BARITONE_TASK,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, timeoutFor(trimmed), settleFor(trimmed));
        }

        if (isTrackableRawBaritoneCommand(trimmed)) {
            return new PendingTask(id, trimmed, TaskKind.RAW_BARITONE,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, timeoutFor(trimmed), settleFor(trimmed));
        }

        return new PendingTask(id, trimmed, TaskKind.RAW_BARITONE,
                CompletionPolicy.UNTRACKED_TIMEOUT, null, timeoutFor(trimmed), settleFor(trimmed));
    }

    private long timeoutFor(String command) {
        String lower = command.toLowerCase();
        if (lower.startsWith("#task smelt ")) return 600_000L;
        if (lower.startsWith("#mine ")) return 180_000L;
        if (lower.startsWith("#goto ")) return 180_000L;
        if (lower.equals("#sleep")) return 60_000L;
        if (lower.startsWith("#surface") || lower.startsWith("#path")) return 120_000L;
        if (lower.startsWith("#explore") || lower.startsWith("#farm")) return 120_000L;
        return 30_000L;
    }

    private long settleFor(String command) {
        String lower = command.toLowerCase();
        if ((lower.startsWith("#goto ") || lower.startsWith("#task "))
                && (lower.contains("portal")
                    || lower.contains("nether")
                    || lower.contains("overworld")
                    || lower.contains("end_portal"))) {
            return 10_000L;
        }
        if (lower.startsWith("#goto ")) return 1_500L;
        return 0L;
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
        activeSettleStarted = false;
        CommandExecutor.execute(next.command());

        if (next.completion() == CompletionPolicy.IMMEDIATE) {
            completeActiveId(next.id(), null);
        } else if (next.completion() == CompletionPolicy.UNTRACKED_TIMEOUT) {
            startTimeoutWatcher(next);
        } else if (next.completion() == CompletionPolicy.BRIDGE_CALLBACK
                || next.completion() == CompletionPolicy.BARITONE_TASK_CHAT) {
            startBaritonePlanWatcher(next);
            if (next.timeoutMs() > 0) {
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
            boolean sawBusy = false;
            long start = System.currentTimeMillis();
            long lastBusyAt = start;
            sleepQuietly(300);

            while (true) {
                PendingTask active = activeTask;
                if (active == null || active.id() != task.id() || paused) return;

                Integer pendingCount = readBaritoneTaskPlanPendingCount();
                Boolean pathingBusy = readBaritonePathingBusy();
                Boolean processBusy = readRelevantBaritoneProcessBusy(task.command());
                boolean hasSignal = pendingCount != null || pathingBusy != null || processBusy != null;
                boolean busy = (pendingCount != null && pendingCount > 0)
                        || Boolean.TRUE.equals(pathingBusy)
                        || Boolean.TRUE.equals(processBusy);

                if (busy) {
                    sawBusy = true;
                    lastBusyAt = System.currentTimeMillis();
                }

                long elapsed = System.currentTimeMillis() - start;
                if (!hasSignal && elapsed > 5_000L) {
                    chatDebug("[Bridge] DEBUG: no Baritone state signal for " + task.command()
                            + "; waiting for timeout fallback");
                    return;
                }

                if (!busy && (sawBusy || elapsed > startupGraceFor(task.command()))
                        && System.currentTimeMillis() - lastBusyAt > quietPeriodFor(task.command())) {
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

    private long startupGraceFor(String command) {
        String lower = command.toLowerCase();
        if (lower.equals("#sleep")) return 4_000L;
        if (lower.startsWith("#goto ") && !looksLikeCoordinateGoto(lower)) return 8_000L;
        if (lower.startsWith("#mine ")) return 4_000L;
        return 2_500L;
    }

    private long quietPeriodFor(String command) {
        String lower = command.toLowerCase();
        if (lower.contains("portal") || lower.contains("nether") || lower.contains("overworld")) {
            return 2_000L;
        }
        return 1_000L;
    }

    private boolean looksLikeCoordinateGoto(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 4) return false;
        return isNumber(parts[1]) && isNumber(parts[2]) && isNumber(parts[3]);
    }

    private boolean isNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Integer readBaritoneTaskPlanPendingCount() {
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return null;
            Object taskPlanProcess = baritone.getClass().getMethod("getTaskPlanProcess").invoke(baritone);
            if (taskPlanProcess == null) return null;
            Object pending = taskPlanProcess.getClass().getMethod("pendingCount").invoke(taskPlanProcess);
            return pending instanceof Number ? ((Number) pending).intValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Boolean readBaritonePathingBusy() {
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return null;
            Object pathing = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            if (pathing == null) return null;
            Object isPathing = pathing.getClass().getMethod("isPathing").invoke(pathing);
            if (Boolean.TRUE.equals(isPathing)) return true;
            Object hasPath = pathing.getClass().getMethod("hasPath").invoke(pathing);
            if (Boolean.TRUE.equals(hasPath)) return true;
            Object inProgress = pathing.getClass().getMethod("getInProgress").invoke(pathing);
            return optionalPresent(inProgress);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Boolean readRelevantBaritoneProcessBusy(String command) {
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return null;
            String lower = String.valueOf(command).trim().toLowerCase();

            if (lower.startsWith("#mine ")) {
                return processActive(baritone, "getMineProcess");
            }
            if (lower.startsWith("#follow ")) {
                return processActive(baritone, "getFollowProcess");
            }
            if (lower.startsWith("#explore")) {
                return processActive(baritone, "getExploreProcess");
            }
            if (lower.startsWith("#farm")) {
                return processActive(baritone, "getFarmProcess");
            }
            if (lower.startsWith("#goto ")) {
                Boolean custom = processActive(baritone, "getCustomGoalProcess");
                Boolean getToBlock = processActive(baritone, "getGetToBlockProcess");
                if (Boolean.TRUE.equals(custom) || Boolean.TRUE.equals(getToBlock)) return true;
                if (custom != null || getToBlock != null) return false;
            }
            if (lower.regionMatches(true, 0, "#task ", 0, 6)) {
                return processActive(baritone, "getTaskPlanProcess");
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getPrimaryBaritone() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiClass.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    private Boolean processActive(Object baritone, String getter) {
        try {
            Object process = baritone.getClass().getMethod(getter).invoke(baritone);
            if (process == null) return null;
            Object active = process.getClass().getMethod("isActive").invoke(process);
            return active instanceof Boolean ? (Boolean) active : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean optionalPresent(Object optional) {
        try {
            if (optional == null) return false;
            Object present = optional.getClass().getMethod("isPresent").invoke(optional);
            return Boolean.TRUE.equals(present);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private void maybeIdleBleed(PendingTask task, String reason) {
        if (task.kind() != TaskKind.BARITONE_TASK && task.kind() != TaskKind.RAW_BARITONE) return;
        if (reason != null && (reason.contains("timeout") || reason.contains("failed"))) return;
        long bleed = 300L + (long) (Math.random() * 500L);
        sleepQuietly(bleed);
        maybeLookAtNearestPlayer(task);
    }

    private void maybeLookAtNearestPlayer(PendingTask task) {
        if (task == null) return;
        String cmd = String.valueOf(task.command()).trim().toLowerCase();
        boolean nextIsSocial = cmd.startsWith("chat:") || task.kind() == TaskKind.BRIDGE_CRAFT;
        if (!nextIsSocial) return;
        lookAtNearestPlayer();
    }

    private static void lookAtNearestPlayer() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) return;
            net.minecraft.entity.player.PlayerEntity self = client.player;
            net.minecraft.util.math.Box box = self.getBoundingBox().expand(32);
            java.util.List<net.minecraft.entity.Entity> players = client.world.getOtherEntities(self, box,
                    e -> e instanceof net.minecraft.entity.player.PlayerEntity);
            net.minecraft.entity.player.PlayerEntity nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (net.minecraft.entity.Entity e : players) {
                double d = e.squaredDistanceTo(self);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = (net.minecraft.entity.player.PlayerEntity) e;
                }
            }
            if (nearest == null) return;
            double dx = nearest.getX() - self.getX();
            double dy = nearest.getY() - self.getY();
            double dz = nearest.getZ() - self.getZ();
            double distXZ = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
            float pitch = (float) (Math.toDegrees(Math.atan2(-dy, distXZ)));
            self.setYaw(yaw);
            self.setPitch(pitch);
        } catch (Throwable ignored) {}
    }

    private boolean completeActiveId(long id, String reason) {
        PendingTask active = activeTask;
        if (active == null || active.id() != id) return false;
        if (paused) return false;
        if (active.settleMs() > 0 && !activeSettleStarted && !"timeout".equals(reason)) {
            activeSettleStarted = true;
            chatDebug("[Bridge] DEBUG: completed " + active.command()
                    + " by " + (reason == null ? "baritone" : reason)
                    + "; settling " + active.settleMs() + "ms");
            startSettleWatcher(active, reason);
            return true;
        }
        if (activeSettleStarted) return true;
        return finishActiveId(id, reason);
    }

    private void startSettleWatcher(PendingTask task, String reason) {
        Thread watcher = new Thread(() -> {
            sleepQuietly(task.settleMs());
            finishActiveId(task.id(), reason == null ? "settled" : reason + "+settled");
        }, "mindcraft-task-settle-" + task.id());
        watcher.setDaemon(true);
        watcher.start();
    }

    private boolean finishActiveId(long id, String reason) {
        PendingTask active = activeTask;
        if (active == null || active.id() != id) return false;
        activeTask = null;
        activePostActionStarted = false;
        activeSettleStarted = false;
        paused = false;
        lastFailureReason = null;
        lastActivityMs = System.currentTimeMillis();
        if (reason != null) {
            chatDebug("[Bridge] DEBUG: completed " + active.command() + " by " + reason);
        }
        if (reason == null || !(reason.contains("timeout") || reason.contains("failed"))) {
            StateCollector.pushWorldEvent("queue_complete", active.command());
        }
        maybeIdleBleed(active, reason);
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

    private static boolean isTrackableRawBaritoneCommand(String command) {
        String lower = String.valueOf(command).trim().toLowerCase();
        return lower.startsWith("#goto ")
                || lower.startsWith("#mine ")
                || lower.equals("#sleep")
                || lower.startsWith("#surface")
                || lower.startsWith("#path");
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
