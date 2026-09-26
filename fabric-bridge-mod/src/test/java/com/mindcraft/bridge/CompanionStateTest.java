package com.mindcraft.bridge;

import com.mindcraft.protocol.ProtocolCodec;
import com.mindcraft.protocol.RosterMessage;
import com.mindcraft.protocol.PlayerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompanionStateTest {

    @BeforeEach
    void resetSingletonState() {
        // CompanionState.get() is a process-wide singleton; sibling tests mutate it
        // via putSnapshot/addEvent/route. Reset before each test so execution order
        // does not matter and the "starts empty" contract holds deterministically.
        CompanionState.get().reset();
    }

    @Test
    void snapshotStartsEmpty() {
        CompanionState state = CompanionState.get();
        CompanionState.Snapshot snap = state.snapshot();
        assertNull(snap.helloJson);
        assertNull(snap.rosterJson);
        assertNull(snap.factsJson);
    }

    @Test
    void putSnapshotReplacesAtomically() {
        CompanionState state = CompanionState.get();
        CompanionState.Snapshot s1 = new CompanionState.Snapshot("hello1", null, null);
        state.putSnapshot(s1);
        assertEquals("hello1", state.snapshot().helloJson);

        CompanionState.Snapshot s2 = new CompanionState.Snapshot("hello2", "roster2", "facts2");
        state.putSnapshot(s2);
        CompanionState.Snapshot snap = state.snapshot();
        assertEquals("hello2", snap.helloJson);
        assertEquals("roster2", snap.rosterJson);
        assertEquals("facts2", snap.factsJson);
    }

    @Test
    void snapshotWithMethodsCreateNewInstances() {
        CompanionState.Snapshot empty = CompanionState.Snapshot.EMPTY;
        assertNull(empty.helloJson);
        assertNull(empty.rosterJson);
        assertNull(empty.factsJson);

        CompanionState.Snapshot withHello = empty.withHello("helloJson");
        assertEquals("helloJson", withHello.helloJson);
        assertNull(withHello.rosterJson);   // preserves null from EMPTY
        assertNull(withHello.factsJson);

        CompanionState.Snapshot withRoster = withHello.withRoster("rosterJson");
        assertEquals("helloJson", withRoster.helloJson);  // preserved
        assertEquals("rosterJson", withRoster.rosterJson);
        assertNull(withRoster.factsJson);

        CompanionState.Snapshot withFacts = withRoster.withFacts("factsJson");
        assertEquals("helloJson", withFacts.helloJson);
        assertEquals("rosterJson", withFacts.rosterJson);
        assertEquals("factsJson", withFacts.factsJson);
    }

    @Test
    void addEventAndDrainEvents() {
        CompanionState state = CompanionState.get();
        // Clear any prior state
        state.drainEvents();
        state.putSnapshot(CompanionState.Snapshot.EMPTY);

        state.addEvent("{\"type\":\"event\",\"kind\":\"block_break\"}");
        state.addEvent("{\"type\":\"event\",\"kind\":\"player_join\"}");

        List<String> drained = state.drainEvents();
        assertEquals(2, drained.size());
        assertTrue(drained.get(0).contains("block_break"));
        assertTrue(drained.get(1).contains("player_join"));

        // Second drain returns empty
        assertTrue(state.drainEvents().isEmpty());
    }

    @Test
    void eventQueueBoundedAtMaxEvents() {
        CompanionState state = CompanionState.get();
        state.drainEvents(); // clear

        for (int i = 0; i < 70; i++) {
            state.addEvent("{\"type\":\"event\",\"seq\":" + i + "}");
        }

        List<String> drained = state.drainEvents();
        assertTrue(drained.size() <= 64, "Queue should be capped at 64");
        // The oldest entries should have been evicted
        String first = drained.get(0);
        int firstSeq = Integer.parseInt(
            first.replaceAll(".*\"seq\":(\\d+).*", "$1"));
        assertTrue(firstSeq >= 6, "Oldest event should be evicted"); // 70-64=6
    }

    @Test
    void routeDispatchesRosterToSnapshot() {
        CompanionState state = CompanionState.get();
        state.putSnapshot(CompanionState.Snapshot.EMPTY);

        RosterMessage roster = new RosterMessage();
        PlayerInfo p = new PlayerInfo();
        p.name = "Alex";
        roster.players = List.of(p);
        String json = ProtocolCodec.encode(roster);

        CompanionChannel.route(json);

        assertNotNull(state.snapshot().rosterJson);
        assertTrue(state.snapshot().rosterJson.contains("Alex"));
    }

    @Test
    void routeDispatchesEventToQueue() {
        CompanionState state = CompanionState.get();
        state.drainEvents(); // clear
        state.putSnapshot(CompanionState.Snapshot.EMPTY);

        String eventJson = "{\"v\":1,\"type\":\"event\",\"kind\":\"player_death\",\"player\":\"Alex\"}";
        CompanionChannel.route(eventJson);

        List<String> drained = state.drainEvents();
        assertEquals(1, drained.size());
        assertTrue(drained.get(0).contains("player_death"));
    }

    @Test
    void routeDispatchesHelloToSnapshot() {
        CompanionState state = CompanionState.get();
        state.putSnapshot(CompanionState.Snapshot.EMPTY);

        String helloJson = "{\"v\":1,\"type\":\"hello\",\"modVersion\":\"1.0.0\",\"capabilities\":[\"roster\"]}";
        CompanionChannel.route(helloJson);

        assertNotNull(state.snapshot().helloJson);
        assertTrue(state.snapshot().helloJson.contains("1.0.0"));
    }

    @Test
    void routeDispatchesFactsToSnapshot() {
        CompanionState state = CompanionState.get();
        state.putSnapshot(CompanionState.Snapshot.EMPTY);

        String factsJson = "{\"v\":1,\"type\":\"facts\",\"spawn\":[0,64,0]}";
        CompanionChannel.route(factsJson);

        assertNotNull(state.snapshot().factsJson);
        assertTrue(state.snapshot().factsJson.contains("[0,64,0]"));
    }

    @Test
    void routeUnknownTypeIgnored() {
        CompanionState state = CompanionState.get();
        state.putSnapshot(CompanionState.Snapshot.EMPTY);
        state.drainEvents();

        String unknownJson = "{\"v\":1,\"type\":\"unknown_thing\"}";
        CompanionChannel.route(unknownJson);

        assertNull(state.snapshot().helloJson);
        assertNull(state.snapshot().rosterJson);
        assertNull(state.snapshot().factsJson);
        assertTrue(state.drainEvents().isEmpty());
    }

    @Test
    void routeGarbageIgnored() {
        CompanionState state = CompanionState.get();
        state.drainEvents();

        CompanionChannel.route("not json");
        CompanionChannel.route("{");

        assertTrue(state.drainEvents().isEmpty());
    }
}
