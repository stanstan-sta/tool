package com.mindcraft.bridge;

/**
 * A world event emitted by the Fabric mod and consumed by the Node.js bridge agent.
 * Events describe moments in time (state transitions) that the Node side may miss
 * between its 0.8–5s polling gaps.
 */
public class WorldEvent {
    public final String type;
    public final String detail;
    public final long timestamp;

    public WorldEvent(String type, String detail) {
        this.type = type;
        this.detail = detail;
        this.timestamp = System.currentTimeMillis();
    }

    String toJson() {
        return "{\"type\":\"" + escape(type) + "\",\"detail\":\"" + escape(detail) + "\",\"timestamp\":" + timestamp + "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
