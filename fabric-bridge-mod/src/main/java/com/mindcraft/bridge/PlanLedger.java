package com.mindcraft.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlanLedger {
    private final Map<String, Integer> inventory = new HashMap<>();
    private final java.util.Set<String> reservedTools = new java.util.HashSet<>();

    public static PlanLedger fromInventory(Map<String, Integer> source) {
        PlanLedger ledger = new PlanLedger();
        if (source != null) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                ledger.produce(entry.getKey(), entry.getValue());
            }
        }
        return ledger;
    }

    public PlanLedger fork() {
        PlanLedger copy = new PlanLedger();
        copy.inventory.putAll(this.inventory);
        copy.reservedTools.addAll(this.reservedTools);
        return copy;
    }

    public void commitFrom(PlanLedger child) {
        this.inventory.clear();
        this.inventory.putAll(child.inventory);
        this.reservedTools.clear();
        this.reservedTools.addAll(child.reservedTools);
    }

    public Map<String, Integer> snapshot() {
        return new HashMap<>(inventory);
    }

    public int available(String itemId) {
        return inventory.getOrDefault(ItemIds.normalize(itemId), 0);
    }

    public boolean has(String itemId) {
        String normalized = ItemIds.normalize(itemId);
        return available(normalized) > 0 || reservedTools.contains(normalized);
    }

    public int consume(String itemId, int count) {
        if (count <= 0) return 0;
        String normalized = ItemIds.normalize(itemId);
        int have = inventory.getOrDefault(normalized, 0);
        int used = Math.min(have, count);
        if (used > 0) {
            int left = have - used;
            if (left > 0) inventory.put(normalized, left);
            else inventory.remove(normalized);
        }
        return count - used;
    }

    public int consumeAny(List<String> patterns, int count) {
        if (count <= 0) return 0;
        int remaining = count;
        java.util.List<String> keys = new java.util.ArrayList<>(inventory.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (remaining <= 0) break;
            if (!matches(key, patterns)) continue;
            remaining = consume(key, remaining);
        }
        return remaining;
    }

    public void produce(String itemId, int count) {
        if (count <= 0) return;
        inventory.merge(ItemIds.normalize(itemId), count, Integer::sum);
    }

    public void reserveTool(String itemId) {
        reservedTools.add(ItemIds.normalize(itemId));
    }

    public boolean hasToolOrReserved(String itemId) {
        return has(itemId);
    }

    private static boolean matches(String itemId, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return false;
        String normalizedItem = ItemIds.normalize(itemId);
        for (String pattern : patterns) {
            if (pattern == null) continue;
            String normalized = ItemIds.normalize(pattern.replace("*", ""));
            if (pattern.contains("*")) {
                if (normalizedItem.endsWith(normalized.replace("minecraft:", ""))) return true;
            } else if (normalizedItem.equals(ItemIds.normalize(pattern))) {
                return true;
            }
        }
        return false;
    }
}
