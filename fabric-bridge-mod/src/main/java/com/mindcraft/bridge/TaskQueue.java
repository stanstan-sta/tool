package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;

import com.mindcraft.bridge.workers.ActionRegistry;
import com.mindcraft.bridge.workers.Worker;
import com.mindcraft.bridge.workers.WorkerContext;
import com.mindcraft.bridge.workers.WorkerResult;

import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;

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
        RAW_BARITONE,
        WORKER
    }

    public enum CompletionPolicy {
        BARITONE_TASK_CHAT,
        BRIDGE_CALLBACK,
        IMMEDIATE,
        UNTRACKED_TIMEOUT,
        WORKER_THREAD
    }

    public enum EnqueueStatus {
        QUEUED,
        EXECUTED_IMMEDIATELY,
        REJECTED
    }

    public record QueuedCommand(String command, String actionType) {}

    public record EnqueueResult(EnqueueStatus status, int queued, java.util.List<Long> taskIds, String error) {
        public boolean accepted() {
            return status == EnqueueStatus.QUEUED || status == EnqueueStatus.EXECUTED_IMMEDIATELY;
        }
        public static EnqueueResult queued(java.util.List<Long> taskIds) {
            return new EnqueueResult(EnqueueStatus.QUEUED, taskIds.size(), java.util.List.copyOf(taskIds), null);
        }
        public static EnqueueResult executedImmediately(int count) {
            return new EnqueueResult(EnqueueStatus.EXECUTED_IMMEDIATELY, count, java.util.List.of(), null);
        }
        public static EnqueueResult rejected(String error) {
            return new EnqueueResult(EnqueueStatus.REJECTED, 0, java.util.List.of(), error);
        }
    }

    private record PendingTask(
            long id,
            String command,
            String actionType,
            TaskKind kind,
            CompletionPolicy completion,
            Runnable postAction,
            long timeoutMs,
            long settleMs,
            String payloadJson
    ) {}

    private final Queue<PendingTask> pending = new ConcurrentLinkedQueue<>();
    private final Deque<PendingTask> suspended = new ConcurrentLinkedDeque<>();
    private final AtomicLong ids = new AtomicLong(1);

    private final Lock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mindcraft-task-scheduler");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> activeTimeoutFuture = null;

    private volatile PendingTask activeTask = null;
    private volatile boolean activePostActionStarted = false;
    private volatile boolean activeSettleStarted = false;
    private volatile boolean paused = false;
    private volatile String lastFailureReason = null;
    private volatile boolean enabled = true;
    private volatile long lastActivityMs = System.currentTimeMillis();
    private volatile boolean cancellationRequested = false;

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

    public int enqueue(java.util.List<String> commands) {
        java.util.List<QueuedCommand> queued = commands.stream()
                .map(c -> new QueuedCommand(c, null))
                .collect(java.util.stream.Collectors.toList());
        return enqueueInternal(queued, null).queued();
    }

    public EnqueueResult enqueueDetailed(java.util.List<QueuedCommand> commands) {
        return enqueueInternal(commands, null);
    }

    public int enqueueWithCallback(String command, Runnable postAction) {
        return enqueueWithCallback(command, null, postAction);
    }

    public int enqueueWithCallback(String command, String actionType, Runnable callback) {
        return enqueueInternal(
            java.util.List.of(new QueuedCommand(command, actionType)),
            callback
        ).queued();
    }

    public EnqueueResult enqueueWorkerAction(String actionType, String payloadJson) {
        String command = "#" + actionType;
        lock.lock();
        try {
            int max = Math.max(1, BridgeConfig.get().queueMaxCapacity);
            if (pending.size() + 1 > max) {
                return EnqueueResult.rejected("QUEUE_FULL");
            }
            resetCancellation();
            discardPausedFailure();
            long id = ids.getAndIncrement();
            PendingTask task = new PendingTask(
                id, command, actionType,
                TaskKind.WORKER,
                CompletionPolicy.WORKER_THREAD,
                null,
                BridgeConfig.get().defaultTimeoutMs,
                0L,
                payloadJson
            );
            pending.add(task);
            lastActivityMs = System.currentTimeMillis();
            dispatchIfIdle();
            return EnqueueResult.queued(java.util.List.of(id));
        } finally {
            lock.unlock();
        }
    }

    public int enqueue(String command) {
        if (command == null || command.isBlank()) return 0;
        return enqueue(java.util.List.of(command));
    }

    private EnqueueResult enqueueInternal(java.util.List<QueuedCommand> commands, Runnable callbackForSingleCommand) {
        if (commands == null || commands.isEmpty()) {
            return EnqueueResult.rejected("EMPTY");
        }

        java.util.List<QueuedCommand> valid = commands.stream()
                .filter(java.util.Objects::nonNull)
                .filter(c -> c.command() != null && !c.command().isBlank())
                .collect(java.util.stream.Collectors.toList());

        if (valid.isEmpty()) {
            return EnqueueResult.rejected("EMPTY");
        }

        if (!enabled) {
            int executed = 0;
            for (QueuedCommand c : valid) {
                CommandExecutor.execute(c.command());
                executed++;
            }
            if (callbackForSingleCommand != null) {
                runPostAction(callbackForSingleCommand);
            }
            return EnqueueResult.executedImmediately(executed);
        }

        lock.lock();
        try {
            int max = Math.max(1, BridgeConfig.get().queueMaxCapacity);
            if (pending.size() + valid.size() > max) {
                return EnqueueResult.rejected("QUEUE_FULL");
            }

            resetCancellation();
            discardPausedFailure();

            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (QueuedCommand c : valid) {
                Runnable postAction = valid.size() == 1 ? callbackForSingleCommand : null;
                PendingTask task = classify(c.command(), postAction, c.actionType());
                pending.add(task);
                ids.add(task.id());
            }

            lastActivityMs = System.currentTimeMillis();
            dispatchIfIdle();

            return EnqueueResult.queued(ids);
        } finally {
            lock.unlock();
        }
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
        lock.lock();
        try {
            cancellationRequested = true;
            pending.clear();
            suspended.clear();
            activeTask = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            paused = false;
            lastFailureReason = "cancelled";
            lastActivityMs = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
        try {
            CommandExecutor.execute("#cancel");
        } catch (Throwable t) {
            MindcraftBridgeMod.LOGGER.warn("Failed to send Baritone cancel", t);
        }
    }

    public void requestCancellation() {
        cancellationRequested = true;
        cancelAll();
    }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public void resetCancellation() {
        cancellationRequested = false;
    }

    public boolean discardPausedFailure() {
        resetCancellation();
        if (!paused) return false;
        pending.clear();
        suspended.clear();
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

    private boolean failActiveId(long id, String reason) {
        lock.lock();
        try {
            if (activeTask == null || activeTask.id() != id) {
                return false;
            }
            lastFailureReason = reason;
            activeTask = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            return true;
        } finally {
            lock.unlock();
        }
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
        suspended.clear();
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

    public long createActiveTrackingTask(String command, String actionType) {
        lock.lock();
        try {
            if (activeTask != null || paused) return -1L;
            long id = ids.getAndIncrement();
            PendingTask task = new PendingTask(id, command, actionType, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0, 0, null);
            activeTask = task;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            return id;
        } finally {
            lock.unlock();
        }
    }

    public long createNestedTrackingTask(String command, String actionType) {
        lock.lock();
        try {
            PendingTask active = activeTask;
            if (active != null) {
                suspended.push(active);
            }
            long id = ids.getAndIncrement();
            PendingTask task = new PendingTask(id, command, actionType, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0, 0, null);
            activeTask = task;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            return id;
        } finally {
            lock.unlock();
        }
    }

    public long startNestedCommand(String command, String actionType) {
        lock.lock();
        try {
            if (command == null || command.isBlank()) return -1L;
            PendingTask active = activeTask;
            if (active != null) {
                suspended.push(active);
            }
            PendingTask task = classify(command, null, actionType);
            activeTask = task;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            startActiveTask(task);
            return task.id();
        } finally {
            lock.unlock();
        }
    }

    public boolean suspendActiveTask() {
        lock.lock();
        try {
            PendingTask active = activeTask;
            if (active == null) return false;
            suspended.push(active);
            activeTask = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean restoreSuspendedTask() {
        lock.lock();
        try {
            if (activeTask != null) return false;
            PendingTask parent = suspended.poll();
            if (parent == null) return false;
            activeTask = parent;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean dismissActiveTask(long id) {
        lock.lock();
        try {
            PendingTask active = activeTask;
            if (active == null || active.id() != id) return false;
            activeTask = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            paused = false;
            lastFailureReason = null;
            restoreSuspendedOrDispatch();
            return true;
        } finally {
            lock.unlock();
        }
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

        Long elapsed = null;
        Long timeout = null;
        Boolean cancellable = null;
        String failureCode = null;

        if (active != null) {
            elapsed = System.currentTimeMillis() - lastActivityMs;
            timeout = active.timeoutMs() > 0 ? active.timeoutMs() : null;
            cancellable = true;
        }

        if (paused && lastFailureReason != null) {
            failureCode = inferFailureCode(lastFailureReason);
        }

        return new QueueState(
                active == null ? null : active.id(),
                active == null ? null : active.actionType(),
                active == null ? null : active.command(),
                active == null ? null : active.kind().name(),
                active == null ? null : active.completion().name(),
                pending.size(),
                paused,
                lastFailureReason,
                status,
                failureCode,
                elapsed,
                timeout,
                cancellable
        );
    }

    private static String inferFailureCode(String reason) {
        if (reason == null) return null;
        String lower = reason.toLowerCase();
        if (lower.contains("no recipe")) return "NO_RECIPE";
        if (lower.contains("no provider")) return "UNSUPPORTED_ITEM";
        if (lower.contains("no ") && lower.contains("nearby")) return "MISSING_STATION";
        if (lower.contains("timeout")) return "TIMEOUT";
        if (lower.contains("cancelled")) return "CANCELLED";
        return "UNKNOWN";
    }

    public record QueueState(
            Long activeId,
            String activeActionType,
            String active,
            String kind,
            String completion,
            int pending,
            boolean paused,
            String lastFailure,
            String status,
            String failureCode,
            Long elapsedMs,
            Long timeoutMs,
            Boolean cancellable
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

    private PendingTask classify(String command, Runnable postAction, String actionType) {
        String trimmed = String.valueOf(command).trim();
        if (trimmed.equalsIgnoreCase("#task sleep") || trimmed.equalsIgnoreCase("sleep")) {
            trimmed = "#sleep";
        }
        long id = ids.getAndIncrement();

        // When actionType is "craft" (from batch craft planner), tag it as BRIDGE_CRAFT
        String resolvedType = actionType;
        if (resolvedType == null && (postAction != null || trimmed.equalsIgnoreCase("#craft"))) {
            resolvedType = "craft";
        }

        if (isCancelCommand(trimmed)) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0, 0, null);
        }

        if (postAction != null || trimmed.equalsIgnoreCase("#craft")) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.BRIDGE_CRAFT,
                    CompletionPolicy.BRIDGE_CALLBACK, postAction, 90_000L, settleFor(trimmed), null);
        }

        if (trimmed.regionMatches(true, 0, "#task ", 0, 6)) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.BARITONE_TASK,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, timeoutFor(trimmed), settleFor(trimmed), null);
        }

        if (isTrackableRawBaritoneCommand(trimmed)) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.RAW_BARITONE,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, timeoutFor(trimmed), settleFor(trimmed), null);
        }

        return new PendingTask(id, trimmed, resolvedType, TaskKind.RAW_BARITONE,
                CompletionPolicy.UNTRACKED_TIMEOUT, null, timeoutFor(trimmed), settleFor(trimmed), null);
    }

    private long timeoutFor(String command) {
        return BridgeConfig.get().getTimeoutForCommand(command);
    }

    private long settleFor(String command) {
        return BridgeConfig.get().getSettleForCommand(command);
    }

    private void dispatchIfIdle() {
        lock.lock();
        try {
            if (activeTask == null && !paused) {
                dispatchNext();
            }
        } finally {
            lock.unlock();
        }
    }

    private void dispatchNext() {
        PendingTask next = pending.poll();
        if (next == null) return;
        activeTask = next;
        activePostActionStarted = false;
        activeSettleStarted = false;
        startActiveTask(next);
    }

    private void startActiveTask(PendingTask next) {
        if (next.completion() == CompletionPolicy.WORKER_THREAD) {
            startWorkerThread(next);
            return;
        }

        CommandExecutor.execute(next.command());

        if (next.completion() == CompletionPolicy.IMMEDIATE) {
            completeActiveId(next.id(), null);
        } else if (next.completion() == CompletionPolicy.UNTRACKED_TIMEOUT) {
            startTimeoutWatcher(next);
        } else if (next.completion() == CompletionPolicy.BARITONE_TASK_CHAT) {
            startBaritonePlanWatcher(next);
            startBaritoneIdleFallbackWatcher(next);
            if (next.timeoutMs() > 0) {
                startTimeoutWatcher(next);
            }
        } else if (next.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
            startBaritonePlanWatcher(next);
            if (next.timeoutMs() > 0) {
                startTimeoutWatcher(next);
            }
        }
    }

    private void startWorkerThread(PendingTask task) {
        String type = task.actionType();
        String payload = task.payloadJson();

        WorkerThreads.start("worker-" + type, () -> {
            try {
                if (Thread.currentThread().isInterrupted() || isCancellationRequested()) {
                    failActiveId(task.id(), type + ": cancelled");
                    return;
                }

                Worker w = ActionRegistry.get().getWorker(type);
                if (w == null) {
                    failActiveId(task.id(), type + ": no worker registered");
                    return;
                }

                WorkerContext ctx = new WorkerContext(
                    this,
                    this::isCancellationRequested,
                    payload,
                    type
                );
                WorkerResult result = w.execute(payload, ctx);

                if (Thread.currentThread().isInterrupted() || isCancellationRequested()) {
                    failActiveId(task.id(), type + ": cancelled");
                    return;
                }

                if (result != null && result.ok()) {
                    completeActiveId(task.id(), "worker-complete");
                } else {
                    failActiveId(task.id(), result == null ? type + ": null worker result" : result.error());
                }
            } catch (Throwable t) {
                failActiveId(task.id(), type + ": " + t.getMessage());
            }
        });

        if (task.timeoutMs() > 0) {
            startTimeoutWatcher(task);
        }
    }

    private void startTimeoutWatcher(PendingTask task) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            completeActiveId(task.id(), "timeout");
        }, task.timeoutMs(), TimeUnit.MILLISECONDS);
        activeTimeoutFuture = future;
    }

    private void startBaritonePlanWatcher(PendingTask task) {
        WorkerThreads.start("baritone-plan-watch-" + task.id(), () -> {
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
                        chatDebug("[Bridge] DEBUG: Baritone idle; bridge callback for "
                                + task.command());
                        runActivePostActionOnce(task.id());
                        completeActiveId(task.id(), "baritone-idle");
                    } else {
                        completeActiveId(task.id(), "baritone-idle");
                    }
                    return;
                }

                sleepQuietly(100);
            }
        });
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

    private Boolean readBaritoneHasGoal() {
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return null;
            Object pathing = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            if (pathing == null) return null;
            Object hasGoal = pathing.getClass().getMethod("hasGoal").invoke(pathing);
            return hasGoal instanceof Boolean ? (Boolean) hasGoal : null;
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

    private boolean isBaritoneIdleFallbackReady() {
        try {
            Boolean pathing = readBaritonePathingBusy();
            Integer pending = readBaritoneTaskPlanPendingCount();
            Boolean hasGoal = readBaritoneHasGoal();
            return !Boolean.TRUE.equals(pathing)
                    && (pending == null || pending <= 0)
                    && !Boolean.TRUE.equals(hasGoal);
        } catch (Throwable t) {
            return false;
        }
    }

    private void startBaritoneIdleFallbackWatcher(PendingTask task) {
        WorkerThreads.start("baritone-idle-fallback-" + task.id(), () -> {
            long idleSince = -1L;
            while (!Thread.currentThread().isInterrupted()) {
                PendingTask active = activeTask;
                if (active == null || active.id() != task.id()) return;
                if (paused || isCancellationRequested()) return;

                boolean idle = isBaritoneIdleFallbackReady();
                if (idle) {
                    if (idleSince < 0L) idleSince = System.currentTimeMillis();
                    long settle = Math.max(250L, task.settleMs());
                    if (System.currentTimeMillis() - idleSince >= settle) {
                        completeActiveId(task.id(), "baritone-idle-fallback");
                        return;
                    }
                } else {
                    idleSince = -1L;
                }

                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    private void maybeIdleBleed(PendingTask task, String reason) {
        if (task.kind() != TaskKind.BARITONE_TASK && task.kind() != TaskKind.RAW_BARITONE) return;
        if (reason != null && (reason.contains("timeout") || reason.contains("failed"))) return;
        long bleed = 150L + (long) (Math.random() * 250L);
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
            ClientThread.run(() -> {
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
            });
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
        WorkerThreads.start("task-settle-" + task.id(), () -> {
            sleepQuietly(task.settleMs());
            finishActiveId(task.id(), reason == null ? "settled" : reason + "+settled");
        });
    }

    private boolean finishActiveId(long id, String reason) {
        ScheduledFuture<?> future = activeTimeoutFuture;
        if (future != null && !future.isDone()) {
            future.cancel(false);
            activeTimeoutFuture = null;
        }
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
        restoreSuspendedOrDispatch();
        return true;
    }

    private void restoreSuspendedOrDispatch() {
        PendingTask parent = suspended.poll();
        if (parent != null) {
            activeTask = parent;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            return;
        }
        dispatchIfIdle();
    }

    private void runActivePostActionOnce(long id) {
        PendingTask active = activeTask;
        if (active == null || active.id() != id) return;
        if (active.postAction() == null) {
            chatDebug("[Bridge] DEBUG: no postAction for task " + id);
            return;
        }
        if (activePostActionStarted) {
            chatDebug("[Bridge] DEBUG: postAction already started for task " + id);
            return;
        }
        activePostActionStarted = true;
        chatDebug("[Bridge] DEBUG: running postAction for task " + id + " cmd=" + active.command());
        runPostAction(active.postAction());
        chatDebug("[Bridge] DEBUG: postAction complete for task " + id);
    }

    private void runPostAction(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            chatDebug("[Bridge] DEBUG: client is null - cannot execute post-action");
            return;
        }
        ClientThread.run(() -> {
            if (action != null) {
                try {
                    action.run();
                } catch (Throwable t) {
                    System.err.println("[TaskQueue] Post-Baritone action failed: " + t.getMessage());
                    t.printStackTrace();
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
                ClientThread.run(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(net.minecraft.text.Text.literal(msg), false);
                    }
                });
            }
        } catch (Throwable ignored) {}
    }
}
