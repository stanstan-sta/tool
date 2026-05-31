package com.mindcraft.bridge.workers;

import com.mindcraft.bridge.TaskQueue;
import java.util.function.Supplier;

public record WorkerContext(
    TaskQueue taskQueue,
    Supplier<Boolean> isCancelled,
    String actionJson,
    String actionType
) {
    public WorkerContext(TaskQueue taskQueue, Supplier<Boolean> isCancelled, String actionJson) {
        this(taskQueue, isCancelled, actionJson, null);
    }
}
