package com.mindcraft.bridge.integration;

import com.mindcraft.bridge.workers.WorkerResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerResult")
class WorkerResultTest {

    @Test
    @DisplayName("success factory creates ok result with output")
    void successFactory() {
        WorkerResult result = WorkerResult.success("crafted sword");
        assertTrue(result.ok());
        assertNull(result.error());
        assertEquals("crafted sword", result.output());
    }

    @Test
    @DisplayName("failure factory creates error result")
    void failureFactory() {
        WorkerResult result = WorkerResult.failure("no smithing table nearby");
        assertFalse(result.ok());
        assertEquals("no smithing table nearby", result.error());
        assertNull(result.output());
    }

    @Test
    @DisplayName("success result has null error")
    void successHasNoError() {
        WorkerResult result = WorkerResult.success("done");
        assertNull(result.error());
    }

    @Test
    @DisplayName("failure result has null output")
    void failureHasNoOutput() {
        WorkerResult result = WorkerResult.failure("failed");
        assertNull(result.output());
    }

    @Test
    @DisplayName("success with empty string output")
    void successEmptyOutput() {
        WorkerResult result = WorkerResult.success("");
        assertTrue(result.ok());
        assertEquals("", result.output());
    }

    @Test
    @DisplayName("failure with empty string error")
    void failureEmptyError() {
        WorkerResult result = WorkerResult.failure("");
        assertFalse(result.ok());
        assertEquals("", result.error());
    }
}
