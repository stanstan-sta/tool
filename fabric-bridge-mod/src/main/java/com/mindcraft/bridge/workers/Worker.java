package com.mindcraft.bridge.workers;

public interface Worker {
    WorkerResult execute(String actionJson, WorkerContext ctx);
    default boolean requiresThread() { return true; }
}
