package com.mindcraft.bridge.workers;

public record WorkerResult(
    boolean ok,
    String error,
    String output
) {
    public static WorkerResult success(String output) {
        return new WorkerResult(true, null, output);
    }
    public static WorkerResult failure(String error) {
        return new WorkerResult(false, error, null);
    }
}
