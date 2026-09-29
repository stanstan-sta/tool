package com.mindcraft.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W7: a companion-only event must flip an incremental {@code since=} poll from
 * {@code unchanged:true} to a full response. Companion events never bump
 * {@code stateSeq}, so the unchanged decision must explicitly account for
 * pending {@code server_events}; otherwise the event stays invisible until an
 * unrelated state/chat/world change forces a full response (and the drain
 * that already happened is then silently lost).
 */
@DisplayName("StateCollector companion-event visibility in incremental polls")
class StateCollectorCompanionUnchangedTest {

    @Test
    @DisplayName("companion-only pending event forces a full response")
    void companionOnlyEventFlipsUnchangedToFalse() {
        // seq matches, chat/world queues empty, but one companion event pending.
        assertFalse(StateCollector.isUnchangedPoll(7L, 7L, true, true,
                List.of("{\"v\":1,\"type\":\"event\",\"kind\":\"player_death\"}")));
    }

    @Test
    @DisplayName("no changes anywhere stays unchanged")
    void noChangesStaysUnchanged() {
        assertTrue(StateCollector.isUnchangedPoll(7L, 7L, true, true, List.of()));
    }

    @Test
    @DisplayName("chat or world activity still forces a full response")
    void chatOrWorldActivityForcesFullResponse() {
        assertFalse(StateCollector.isUnchangedPoll(7L, 7L, false, true, List.of()));
        assertFalse(StateCollector.isUnchangedPoll(7L, 7L, true, false, List.of()));
    }

    @Test
    @DisplayName("moved sequence still forces a full response")
    void movedSequenceForcesFullResponse() {
        assertFalse(StateCollector.isUnchangedPoll(6L, 7L, true, true, List.of()));
    }

    @Test
    @DisplayName("full (non-incremental) read is never unchanged")
    void nullSinceIsNeverUnchanged() {
        assertFalse(StateCollector.isUnchangedPoll(null, 7L, true, true, List.of()));
    }
}
