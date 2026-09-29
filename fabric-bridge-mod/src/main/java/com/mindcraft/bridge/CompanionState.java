package com.mindcraft.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public final class CompanionState {
    private static final CompanionState INSTANCE = new CompanionState();
    private static final int MAX_EVENTS = 64;

    public static CompanionState get() {
        return INSTANCE;
    }

    private final AtomicReference<Snapshot> snap = new AtomicReference<>(Snapshot.EMPTY);
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

    public void putSnapshot(Snapshot s) {
        snap.set(s);
    }

    public Snapshot snapshot() {
        return snap.get();
    }

    public void addEvent(String json) {
        events.add(json);
        while (events.size() > MAX_EVENTS) {
            events.poll();
        }
    }

    public List<String> drainEvents() {
        List<String> out = new ArrayList<>();
        String e;
        while ((e = events.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    // Non-destructive copy for peek reads: the observation poll is the only
    // consumer that drains (A12).
    public List<String> peekEvents() {
        return new ArrayList<>(events);
    }

    /**
     * Clear all server-companion data. Called on disconnect so that /state does not
     * report stale roster/facts/events from a previous server after switching worlds.
     */
    public void reset() {
        snap.set(Snapshot.EMPTY);
        events.clear();
    }

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(null, null, null);

        public final String helloJson;
        public final String rosterJson;
        public final String factsJson;

        public Snapshot(String helloJson, String rosterJson, String factsJson) {
            this.helloJson = helloJson;
            this.rosterJson = rosterJson;
            this.factsJson = factsJson;
        }

        public Snapshot withHello(String helloJson) {
            return new Snapshot(helloJson, this.rosterJson, this.factsJson);
        }

        public Snapshot withRoster(String rosterJson) {
            return new Snapshot(this.helloJson, rosterJson, this.factsJson);
        }

        public Snapshot withFacts(String factsJson) {
            return new Snapshot(this.helloJson, this.rosterJson, factsJson);
        }
    }
}
