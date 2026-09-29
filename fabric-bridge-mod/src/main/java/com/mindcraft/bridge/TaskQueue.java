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
import java.util.concurrent.CountDownLatch;

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
        TYPED_ACTION,
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

    public record TypedAction(String actionType, String payloadJson) {}

    public record TaskHandle(long id, long generation, String command) {}

    public record TaskTerminal(boolean success, String reason) {}

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
            String payloadJson,
            long generation,
            boolean nested
    ) {}

    private final Deque<PendingTask> pending = new ConcurrentLinkedDeque<>();
    private final Deque<PendingTask> suspended = new ConcurrentLinkedDeque<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, TaskTerminal> terminalTasks = new ConcurrentHashMap<>();
    private static final InheritableThreadLocal<TaskHandle> TASK_CONTEXT = new InheritableThreadLocal<>();

    private final Lock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mindcraft-task-scheduler");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> activeTimeoutFuture = null;
    private volatile ScheduledFuture<?> deferredDispatchFuture = null;

    private volatile PendingTask activeTask = null;
    private volatile boolean activePostActionStarted = false;
    private volatile boolean activeSettleStarted = false;
    private volatile boolean paused = false;
    private volatile String lastFailureReason = null;
    private volatile boolean enabled = true;
    private volatile long lastActivityMs = System.currentTimeMillis();
    private volatile boolean cancellationRequested = false;
    private volatile CountDownLatch cancelGate = new CountDownLatch(0);
    private volatile long generation = 0;
    private volatile long clientGeneration = Long.MIN_VALUE;

    private TaskQueue() {}

    public long getLastActivityMs() {
        return lastActivityMs;
    }

    public boolean acceptClientGeneration(Long requestedGeneration) {
        if (requestedGeneration == null) return true;
        lock.lock();
        try {
            if (requestedGeneration < clientGeneration) return false;
            clientGeneration = requestedGeneration;
            return true;
        } finally {
            lock.unlock();
        }
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
                payloadJson,
                generation,
                false
            );
            addPending(task);
            lastActivityMs = System.currentTimeMillis();
            dispatchIfIdle();
            return EnqueueResult.queued(java.util.List.of(id));
        } finally {
            lock.unlock();
        }
    }

    public EnqueueResult enqueueTypedActions(java.util.List<TypedAction> actions) {
        if (actions == null || actions.isEmpty()) return EnqueueResult.rejected("EMPTY");
        lock.lock();
        try {
            int max = Math.max(1, BridgeConfig.get().queueMaxCapacity);
            if (pending.size() + actions.size() > max) return EnqueueResult.rejected("QUEUE_FULL");
            discardPausedFailure();
            cancellationRequested = false;
            cancelGate.countDown();
            java.util.List<Long> taskIds = new java.util.ArrayList<>();
            for (TypedAction action : actions) {
                if (action == null || action.actionType() == null || action.actionType().isBlank()
                        || action.payloadJson() == null || action.payloadJson().isBlank()) continue;
                long id = ids.getAndIncrement();
                PendingTask task = new PendingTask(id, "#" + action.actionType(), action.actionType(),
                        TaskKind.TYPED_ACTION, CompletionPolicy.BRIDGE_CALLBACK, null,
                        BridgeConfig.get().defaultTimeoutMs, 0L, action.payloadJson(), generation, false);
                addPending(task);
                taskIds.add(id);
            }
            if (taskIds.isEmpty()) return EnqueueResult.rejected("EMPTY");
            lastActivityMs = System.currentTimeMillis();
            dispatchIfIdle();
            return EnqueueResult.queued(taskIds);
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
            java.util.List<PendingTask> tasks = new java.util.ArrayList<>();
            for (QueuedCommand c : valid) {
                Runnable postAction = valid.size() == 1 ? callbackForSingleCommand : null;
                PendingTask task = classify(c.command(), postAction, c.actionType());
                tasks.add(task);
                ids.add(task.id());
            }
            if (TASK_CONTEXT.get() != null) {
                for (int i = tasks.size() - 1; i >= 0; i--) pending.addFirst(tasks.get(i));
            } else {
                for (PendingTask task : tasks) pending.addLast(task);
            }

            lastActivityMs = System.currentTimeMillis();
            dispatchIfIdle();

            return EnqueueResult.queued(ids);
        } finally {
            lock.unlock();
        }
    }

    public void onBaritoneComplete() {
        chatDebug("[Bridge] DEBUG: ignored unlabelled Baritone completion");
    }

    public void onBaritoneComplete(String token) {
        PendingTask active = activeTask;
        if (active == null || token == null || !token.equals(baritoneToken(active))) return;
        if (active.completion() == CompletionPolicy.BARITONE_TASK_CHAT) completeActiveId(active.id(), "baritone-token");
        else if (active.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
            if (active.postAction() == null) completeActiveId(active.id(), "baritone-token");
            else runActivePostActionOnce(active.id());
        }
    }

    public void onBaritoneFailed(String reason) {
        chatDebug("[Bridge] DEBUG: ignored unlabelled Baritone failure - " + reason);
    }

    public void onBaritoneFailed(String token, String reason) {
        PendingTask active = activeTask;
        if (active == null) {
            chatDebug("[Bridge] DEBUG: ignored Baritone failure with no active task - " + reason);
            return;
        }
        if (token == null || !token.equals(baritoneToken(active))) return;

        if (active.completion() == CompletionPolicy.BARITONE_TASK_CHAT
                || active.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
            failActiveId(active.id(), reason);
            return;
        }

        chatDebug("[Bridge] DEBUG: ignored Baritone failure for " + active.kind()
                + " command=" + active.command() + " - " + reason);
    }

    public void cancelAll() {
        lock.lock();
        try {
            cancellationRequested = true;
            cancelActiveTimeoutFuture();
            if (activeTask != null) recordTerminal(activeTask.id(), new TaskTerminal(false, "cancelled"));
            for (PendingTask task : pending) recordTerminal(task.id(), new TaskTerminal(false, "cancelled"));
            for (PendingTask task : suspended) recordTerminal(task.id(), new TaskTerminal(false, "cancelled"));
            pending.clear();
            suspended.clear();
            activeTask = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            paused = false;
            lastFailureReason = "cancelled";
            cancelGate = new CountDownLatch(1);
            generation++;
            lastActivityMs = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
        WorkerThreads.interruptAll("cancelAll");
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
        TaskHandle context = TASK_CONTEXT.get();
        return context == null ? cancellationRequested : isCancellationRequested(context);
    }

    public void resetCancellation() {
        cancellationRequested = false;
        cancelGate.countDown();
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
        TaskHandle context = TASK_CONTEXT.get();
        return context != null && complete(context, command, null);
    }

    public boolean failActiveIf(String command, String reason) {
        TaskHandle context = TASK_CONTEXT.get();
        return context != null && fail(context, command, reason);
    }

    static TaskHandle currentTaskHandle() {
        return TASK_CONTEXT.get();
    }

    static <T> T withTaskHandle(TaskHandle handle, java.util.concurrent.Callable<T> action) throws Exception {
        TaskHandle previous = TASK_CONTEXT.get();
        if (handle == null) TASK_CONTEXT.remove(); else TASK_CONTEXT.set(handle);
        try {
            return action.call();
        } finally {
            if (previous == null) TASK_CONTEXT.remove(); else TASK_CONTEXT.set(previous);
        }
    }

    public TaskHandle captureActiveHandle(String command) {
        PendingTask active = activeTask;
        if (active == null || command == null || !sameCommand(active.command(), command)) return null;
        return handleFor(active);
    }

    public boolean complete(TaskHandle handle, String command, String reason) {
        lock.lock();
        try {
            return matchesHandle(handle, command) && completeActiveId(handle.id(), reason);
        } finally {
            lock.unlock();
        }
    }

    public boolean fail(TaskHandle handle, String command, String reason) {
        lock.lock();
        try {
            return matchesHandle(handle, command) && failActiveId(handle.id(), reason);
        } finally {
            lock.unlock();
        }
    }

    public boolean isCancellationRequested(TaskHandle handle) {
        if (handle == null) return cancellationRequested;
        if (handle.generation() != generation) return true;
        if (terminalTasks.containsKey(handle.id())) return true;
        PendingTask active = activeTask;
        if (active != null && active.id() == handle.id()) return false;
        for (PendingTask task : suspended) {
            if (task.id() == handle.id()) return false;
        }
        return true;
    }

    public TaskTerminal awaitTerminal(long taskId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() <= deadline) {
            TaskTerminal terminal = terminalTasks.get(taskId);
            if (terminal != null) {
                terminalTasks.remove(taskId, terminal);
                return terminal;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failNestedTask(taskId, "cancelled");
                return new TaskTerminal(false, "cancelled");
            }
        }
        failNestedTask(taskId, "timeout");
        return new TaskTerminal(false, "timeout");
    }

    // An obtain timeout must retire its child and any active craft substep,
    // otherwise the suspended obtain worker cannot report its own failure.
    private void failNestedTask(long id, String reason) {
        lock.lock();
        try {
            PendingTask target = activeTask;
            if (target == null || target.id() != id) {
                target = suspended.stream().filter(task -> task.id() == id).findFirst().orElse(null);
            }
            if (target == null || !target.nested()) return;
            cancelActiveTimeoutFuture();
            while (activeTask != null && activeTask.id() != id) {
                recordTerminal(activeTask.id(), new TaskTerminal(false, reason));
                activeTask = suspended.poll();
            }
            failActiveId(id, reason);
            // Retire child identities before cancelling Baritone: its synchronous
            // failure callbacks must not try to settle the retired child again.
            try { CommandExecutor.execute("#cancel"); } catch (Throwable ignored) {}
        } finally {
            lock.unlock();
        }
    }

    boolean isNestedCraftTask(TaskHandle handle) {
        PendingTask active = activeTask;
        return active != null && active.nested() && "craft".equals(active.actionType())
                && matchesHandle(handle, "#craft");
    }

    private boolean matchesHandle(TaskHandle handle, String command) {
        if (handle == null || handle.generation() != generation) return false;
        PendingTask active = activeTask;
        return active != null && active.id() == handle.id()
                && sameCommand(active.command(), command)
                && sameCommand(handle.command(), command);
    }

    private static boolean sameCommand(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static TaskHandle handleFor(PendingTask task) {
        return new TaskHandle(task.id(), task.generation(), task.command());
    }

    private boolean failActiveId(long id, String reason) {
        lock.lock();
        try {
            PendingTask active = activeTask;
            if (active == null || active.id() != id) {
                return false;
            }
            cancelActiveTimeoutFuture();
            String failure = reason == null ? "unknown" : reason;
            if (active.nested()) {
                activeTask = null;
                activePostActionStarted = false;
                activeSettleStarted = false;
                restoreSuspendedOrDispatch();
                recordTerminal(id, new TaskTerminal(false, failure));
                return true;
            }
            recordTerminal(id, new TaskTerminal(false, failure));
            chatDebug("[Bridge] DEBUG: bridge task failed - " + failure);
            lastFailureReason = failure;
            paused = true;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            StateCollector.pushWorldEvent("queue_failed", active.command() + " - " + failure);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            resetCancellation();
            if (activeTask == null) {
                paused = false;
                lastFailureReason = null;
                dispatchIfIdle();
                return;
            }
            // Restart active task through normal queue machinery so timeout,
            // Baritone plan watcher, and idle fallback watcher are restarted.
            paused = false;
            lastFailureReason = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            startActiveTask(activeTask);
        } finally {
            lock.unlock();
        }
    }

    public void skip() {
        if (!paused) return;
        resetCancellation();
        suspended.clear();
        activeTask = null;
        activePostActionStarted = false;
        activeSettleStarted = false;
        paused = false;
        lastFailureReason = null;
        dispatchIfIdle();
    }

    public void retry() {
        lock.lock();
        try {
            if (!paused || activeTask == null) return;
            // Restart through normal queue machinery so timeout,
            // Baritone plan watcher, and idle fallback watcher are restarted.
            paused = false;
            lastFailureReason = null;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            startActiveTask(activeTask);
        } finally {
            lock.unlock();
        }
    }

    public long createActiveTrackingTask(String command, String actionType) {
        lock.lock();
        try {
            discardPausedFailure();
            if (activeTask != null || paused) return -1L;
            long id = ids.getAndIncrement();
            PendingTask task = new PendingTask(id, command, actionType, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0, 0, null, generation, false);
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
                    CompletionPolicy.IMMEDIATE, null, 0, 0, null, generation, true);
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
        return startNestedCommand(null, command, actionType, null);
    }

    long startNestedCommand(TaskHandle parent, String command, String actionType, Runnable postAction) {
        lock.lock();
        try {
            if (command == null || command.isBlank()) return -1L;
            if (parent != null && !matchesHandle(parent, parent.command())) return -1L;
            cancelActiveTimeoutFuture();
            PendingTask active = activeTask;
            if (active != null) {
                suspended.push(active);
            }
            PendingTask task = classify(command, postAction, actionType);
            task = new PendingTask(task.id(), task.command(), task.actionType(), task.kind(), task.completion(),
                    task.postAction(), task.timeoutMs(), task.settleMs(), task.payloadJson(), task.generation(), true);
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

    public long startNestedTypedAction(String actionType, String payloadJson) {
        lock.lock();
        try {
            if (actionType == null || actionType.isBlank() || payloadJson == null || payloadJson.isBlank()) return -1L;
            TaskHandle caller = currentTaskHandle();
            if (caller != null && !matchesHandle(caller, caller.command())) return -1L;
            cancelActiveTimeoutFuture();
            PendingTask parent = activeTask;
            if (parent != null) suspended.push(parent);
            long id = ids.getAndIncrement();
            PendingTask task = new PendingTask(id, "#" + actionType, actionType, TaskKind.TYPED_ACTION,
                    CompletionPolicy.BRIDGE_CALLBACK, null, BridgeConfig.get().defaultTimeoutMs, 0L,
                    payloadJson, generation, true);
            activeTask = task;
            activePostActionStarted = false;
            activeSettleStarted = false;
            lastActivityMs = System.currentTimeMillis();
            startActiveTask(task);
            return id;
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

        if (isCancelCommand(trimmed) || trimmed.matches("(?i)#task\\s+(cancel|status|queue|clear)")) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.IMMEDIATE,
                    CompletionPolicy.IMMEDIATE, null, 0, 0, null, generation, false);
        }

        if (postAction != null || trimmed.equalsIgnoreCase("#craft")) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.BRIDGE_CRAFT,
                    CompletionPolicy.BRIDGE_CALLBACK, postAction, 90_000L, settleFor(trimmed), null, generation, false);
        }

        if (trimmed.regionMatches(true, 0, "#task ", 0, 6)) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.BARITONE_TASK,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, timeoutFor(trimmed), settleFor(trimmed), null, generation, false);
        }

        if (isTrackableRawBaritoneCommand(trimmed)) {
            return new PendingTask(id, trimmed, resolvedType, TaskKind.RAW_BARITONE,
                    CompletionPolicy.BARITONE_TASK_CHAT, null, timeoutFor(trimmed), settleFor(trimmed), null, generation, false);
        }

        return new PendingTask(id, trimmed, resolvedType, TaskKind.RAW_BARITONE,
                CompletionPolicy.UNTRACKED_TIMEOUT, null, timeoutFor(trimmed), settleFor(trimmed), null, generation, false);
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
                if (deferDispatchIfBlocked()) {
                    return;
                }
                dispatchNext();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean deferDispatchIfBlocked() {
        CountDownLatch gate = cancelGate;
        if (gate.getCount() == 0) return false;
        // Cancel was called but no new task has been enqueued yet.
        // Defer dispatch — resetCancellation() (called on next enqueue)
        // will count down the latch and allow dispatch to proceed.
        ScheduledFuture<?> existing = deferredDispatchFuture;
        if (existing == null || existing.isDone()) {
            deferredDispatchFuture = scheduler.schedule(() -> {
                // If the gate hasn't been counted down after 1s, proceed anyway
                // (avoid indefinite deferral if no new task arrives).
                if (cancelGate.getCount() > 0) {
                    cancelGate = new CountDownLatch(0);
                }
                dispatchIfIdle();
            }, 1_000L, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    private void dispatchNext() {
        PendingTask next = pending.poll();
        if (next == null) return;
        activeTask = next;
        activePostActionStarted = false;
        activeSettleStarted = false;
        startActiveTask(next);
    }

    private void addPending(PendingTask task) {
        // Work spawned by the active descriptor (notably craft continuations)
        // belongs before the untouched suffix of the original HTTP batch.
        if (TASK_CONTEXT.get() != null) pending.addFirst(task);
        else pending.addLast(task);
    }

    private void startActiveTask(PendingTask next) {
        lastFailureReason = null;
        if (next.kind() == TaskKind.TYPED_ACTION) {
            startTypedAction(next);
            return;
        }
        if (next.completion() == CompletionPolicy.WORKER_THREAD) {
            startWorkerThread(next);
            return;
        }

        // The caller holds the queue lock. A synchronous client-thread wait here
        // deadlocks when a rejected command logs its outcome back into this queue.
        WorkerThreads.start("baritone-dispatch-" + next.id(), () -> dispatchBaritoneTask(next));
    }

    private void dispatchBaritoneTask(PendingTask next) {
        try {
            ClientThread.run(() -> {
                PendingTask active = activeTask;
                if (active == null || active.id() != next.id() || active.generation() != next.generation()) return;
                Object process = installBaritoneToken(next);
                if (process == null && baritoneTokenProcessGetter(next.command()) != null
                        && next.completion() != CompletionPolicy.IMMEDIATE) {
                    // Idleness cannot distinguish rejection from success. Require
                    // the fork's outcome API instead of executing unverifiable work.
                    failActiveId(next.id(), "baritone_task_token_unavailable");
                    return;
                }
                try {
                    CommandExecutor.execute(next.command());
                } finally {
                    if (process != null) {
                        try {
                            process.getClass().getMethod("setBridgeTaskToken", String.class).invoke(process, (Object) null);
                        } catch (ReflectiveOperationException e) {
                            MindcraftBridgeMod.LOGGER.warn("Failed to clear Baritone command token", e);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            MindcraftBridgeMod.LOGGER.warn("Failed to execute command: {}", next.command(), t);
        }

        lock.lock();
        try {
            PendingTask active = activeTask;
            if (active == null || active.id() != next.id() || paused) return;

            if (next.completion() == CompletionPolicy.IMMEDIATE) {
                completeActiveId(next.id(), null);
            } else if (next.completion() == CompletionPolicy.UNTRACKED_TIMEOUT) {
                startTimeoutWatcher(next);
            } else if (next.completion() == CompletionPolicy.BARITONE_TASK_CHAT) {
                if (baritoneTokenProcessGetter(next.command()) == null) {
                    startBaritonePlanWatcher(next);
                    startBaritoneIdleFallbackWatcher(next);
                }
                if (next.timeoutMs() > 0) {
                    startTimeoutWatcher(next);
                }
            } else if (next.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
                if (baritoneTokenProcessGetter(next.command()) == null) startBaritonePlanWatcher(next);
                if (next.timeoutMs() > 0) {
                    startTimeoutWatcher(next);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void startTypedAction(PendingTask task) {
        TaskHandle previous = TASK_CONTEXT.get();
        TASK_CONTEXT.set(handleFor(task));
        try {
            CommandExecutor.TranslatedAction translated = CommandExecutor.translateTypedJson(task.payloadJson());
            if (translated == null || !translated.ok()) {
                failActiveId(task.id(), translated == null ? "invalid_action" : translated.message());
                return;
            }
            if (translated.genericWorker()) {
                PendingTask workerTask = new PendingTask(task.id(), task.command(), task.actionType(), TaskKind.WORKER,
                        CompletionPolicy.WORKER_THREAD, null, task.timeoutMs(), 0L, task.payloadJson(),
                        task.generation(), task.nested());
                activeTask = workerTask;
                startWorkerThread(workerTask);
                return;
            }
            if ("queued".equals(translated.lifecycle()) && translated.command() != null) {
                PendingTask classified = classifyWithId(task.id(), translated.command(), translated.actionType(),
                        task.generation(), task.nested());
                activeTask = classified;
                startActiveTask(classified);
                return;
            }
            if ("immediate".equals(translated.lifecycle())) {
                completeActiveId(task.id(), "typed-immediate");
                return;
            }
            if ("self_executing".equals(translated.lifecycle())) {
                // Craft translation schedules its continuation tasks through this queue.
                // The descriptor itself is only the launcher; completing it lets the
                // first concrete craft/prerequisite task become active.
                if ("craft".equals(task.actionType())) {
                    // Nested crafting owns its whole goal; its runner waits for
                    // individual steps and completes only after checking inventory.
                    if (!task.nested()) completeActiveId(task.id(), "craft-launched");
                    return;
                }
                if (task.timeoutMs() > 0) startTimeoutWatcher(task);
                return;
            }
            failActiveId(task.id(), "unsupported lifecycle");
        } catch (Throwable t) {
            failActiveId(task.id(), task.actionType() + ": " + t.getMessage());
        } finally {
            if (previous == null) TASK_CONTEXT.remove(); else TASK_CONTEXT.set(previous);
        }
    }

    private PendingTask classifyWithId(long id, String command, String actionType, long taskGeneration, boolean nested) {
        PendingTask generated = classify(command, null, actionType);
        return new PendingTask(id, generated.command(), generated.actionType(), generated.kind(), generated.completion(),
                generated.postAction(), generated.timeoutMs(), generated.settleMs(), generated.payloadJson(),
                taskGeneration, nested);
    }

    private void startWorkerThread(PendingTask task) {
        String type = task.actionType();
        String payload = task.payloadJson();

        WorkerThreads.start("worker-" + type, () -> {
            TaskHandle previous = TASK_CONTEXT.get();
            TASK_CONTEXT.set(handleFor(task));
            try {
                if (Thread.currentThread().isInterrupted() || isCancellationRequested(handleFor(task))) {
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
                    () -> isCancellationRequested(handleFor(task)),
                    payload,
                    type
                );
                WorkerResult result = w.execute(payload, ctx);

                if (Thread.currentThread().isInterrupted() || isCancellationRequested(handleFor(task))) {
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
            } finally {
                if (previous == null) TASK_CONTEXT.remove(); else TASK_CONTEXT.set(previous);
            }
        });

        if (task.timeoutMs() > 0) {
            startTimeoutWatcher(task);
        }
    }

    private void startTimeoutWatcher(PendingTask task) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            if (task.kind() == TaskKind.WORKER) {
                cancellationRequested = true;
            }
            failActiveId(task.id(), "timeout");
        }, task.timeoutMs(), TimeUnit.MILLISECONDS);
        activeTimeoutFuture = future;
    }

    private void cancelActiveTimeoutFuture() {
        ScheduledFuture<?> future = activeTimeoutFuture;
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        activeTimeoutFuture = null;
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
                long gen = generation;

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
                    if (gen != generation) return;
                    onBaritoneIdle(task.id());
                    return;
                }

                sleepQuietly(100);
            }
        });
    }

    void onBaritoneIdle(long id) {
        PendingTask task = activeTask;
        if (task == null || task.id() != id) return;
        if (task.completion() == CompletionPolicy.BRIDGE_CALLBACK) {
            runActivePostActionOnce(id);
            // Opening the table starts the worker; inventory work finishes later.
            if ("#craft".equalsIgnoreCase(task.command()) && task.postAction() != null) return;
        }
        completeActiveId(id, "baritone-idle");
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
            Object goal = pathing.getClass().getMethod("getGoal").invoke(pathing);
            return goal != null ? Boolean.TRUE : Boolean.FALSE;
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
                long gen = generation;

                boolean idle = isBaritoneIdleFallbackReady();
                if (idle) {
                    if (idleSince < 0L) idleSince = System.currentTimeMillis();
                    long settle = Math.max(250L, task.settleMs());
                    if (System.currentTimeMillis() - idleSince >= settle) {
                        if (gen != generation) return;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        lock.lock();
        try {
            PendingTask active = activeTask;
            if (active == null || active.id() != id) return false;
            cancelActiveTimeoutFuture();
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
            recordTerminal(id, new TaskTerminal(true, reason));
            return true;
        } finally {
            lock.unlock();
        }
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
        PendingTask owner = activeTask;
        TaskHandle ownerHandle = owner == null ? null : handleFor(owner);
        ClientThread.run(() -> {
            if (action != null) {
                try {
                    withTaskHandle(ownerHandle, () -> { action.run(); return null; });
                } catch (Throwable t) {
                    System.err.println("[TaskQueue] Post-Baritone action failed: " + t.getMessage());
                    t.printStackTrace();
                }
            }
        });
    }

    private void recordTerminal(long id, TaskTerminal terminal) {
        terminalTasks.put(id, terminal);
        if (terminalTasks.size() > 1024) {
            Long oldest = terminalTasks.keySet().stream().min(Long::compareTo).orElse(null);
            if (oldest != null && oldest != id) terminalTasks.remove(oldest);
        }
        scheduler.schedule(() -> terminalTasks.remove(id, terminal), 5, TimeUnit.MINUTES);
    }

    private static boolean isCancelCommand(String command) {
        return "#cancel".equalsIgnoreCase(String.valueOf(command).trim());
    }

    private static boolean isTrackableRawBaritoneCommand(String command) {
        String lower = String.valueOf(command).trim().toLowerCase();
        return lower.startsWith("#goto ")
                || lower.startsWith("#mine ")
                || lower.equals("#sleep") || lower.startsWith("#sleep ")
                || lower.startsWith("#surface")
                || lower.startsWith("#path");
    }

    private static String baritoneToken(PendingTask task) {
        return task.id() + "-" + task.generation();
    }

    static String baritoneTokenProcessGetter(String command) {
        String verb = String.valueOf(command).trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return switch (verb) {
            case "#task", "#craft" -> "getTaskPlanProcess";
            case "#sleep" -> "getSleepInBedProcess";
            default -> null;
        };
    }

    private Object installBaritoneToken(PendingTask task) {
        String getter = baritoneTokenProcessGetter(task.command());
        if (getter == null || task.completion() == CompletionPolicy.IMMEDIATE) return null;
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return null;
            Object process = baritone.getClass().getMethod(getter).invoke(baritone);
            process.getClass().getMethod("setBridgeTaskToken", String.class)
                    .invoke(process, baritoneToken(task));
            return process;
        } catch (Throwable t) {
            MindcraftBridgeMod.LOGGER.warn("Failed to install Baritone task token", t);
            return null;
        }
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
