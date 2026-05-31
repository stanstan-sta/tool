package com.mindcraft.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkerThreads {
    private static final AtomicLong IDS = new AtomicLong(1);
    private static final ConcurrentHashMap<Thread, String> ACTIVE = new ConcurrentHashMap<>();

    private WorkerThreads() {}

    public static Thread start(String label, Runnable body) {
        long id = IDS.getAndIncrement();
        String safeLabel = label == null || label.isBlank()
                ? "worker"
                : label.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                MindcraftBridgeMod.LOGGER.error("Worker thread failed: {}", safeLabel, t);
            } finally {
                ACTIVE.remove(Thread.currentThread());
            }
        }, "mindcraft-" + safeLabel + "-" + id);
        thread.setDaemon(true);
        ACTIVE.put(thread, safeLabel);
        thread.start();
        return thread;
    }

    public static void interruptAll(String reason) {
        for (Map.Entry<Thread, String> entry : ACTIVE.entrySet()) {
            Thread thread = entry.getKey();
            if (thread != null && thread.isAlive()) {
                MindcraftBridgeMod.LOGGER.info("Interrupting worker {} because {}", entry.getValue(), reason);
                thread.interrupt();
            }
        }
        // Do NOT clear ACTIVE — threads remove themselves in finally
    }

    public static int activeCount() {
        return ACTIVE.size();
    }
}
