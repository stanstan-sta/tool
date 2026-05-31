package com.mindcraft.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandExecutorJsonTest {

    @Test
    void extractJsonString_returnsValueForKey() {
        String json = "{\"name\":\"diamond_sword\",\"count\":5}";
        assertEquals("diamond_sword", CommandExecutor.extractJsonString(json, "name"));
    }

    @Test
    void extractJsonString_returnsNullForMissingKey() {
        String json = "{\"name\":\"diamond_sword\"}";
        assertNull(CommandExecutor.extractJsonString(json, "missing"));
    }

    @Test
    void extractJsonString_handlesEscapedQuotes() {
        String json = "{\"msg\":\"hello \\\"world\\\"\"}";
        assertEquals("hello \"world\"", CommandExecutor.extractJsonString(json, "msg"));
    }

    @Test
    void extractJsonString_handlesEscapedNewlines() {
        String json = "{\"msg\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", CommandExecutor.extractJsonString(json, "msg"));
    }

    @Test
    void extractJsonPrimitive_returnsNumber() {
        String json = "{\"count\":5}";
        assertEquals("5", CommandExecutor.extractJsonPrimitive(json, "count"));
    }

    @Test
    void extractJsonPrimitive_returnsQuotedString() {
        String json = "{\"type\":\"smith\"}";
        assertEquals("smith", CommandExecutor.extractJsonPrimitive(json, "type"));
    }

    @Test
    void extractJsonPrimitive_returnsNullForMissingKey() {
        String json = "{\"count\":5}";
        assertNull(CommandExecutor.extractJsonPrimitive(json, "missing"));
    }

    @Test
    void extractJsonObject_returnsNestedObject() {
        String json = "{\"items\":{\"sword\":1,\"pickaxe\":2},\"extra\":\"data\"}";
        String obj = CommandExecutor.extractJsonObject(json, "items");
        assertNotNull(obj);
        assertEquals("{\"sword\":1,\"pickaxe\":2}", obj);
    }

    @Test
    void extractJsonObject_returnsNullForMissingKey() {
        String json = "{\"items\":{\"sword\":1}}";
        assertNull(CommandExecutor.extractJsonObject(json, "missing"));
    }

    @Test
    void splitJsonArray_parsesSimpleArray() {
        var result = CommandExecutor.splitJsonArray("[\"a\",\"b\",\"c\"]");
        assertEquals(3, result.size());
        assertEquals("\"a\"", result.get(0));
        assertEquals("\"b\"", result.get(1));
        assertEquals("\"c\"", result.get(2));
    }

    @Test
    void splitJsonArray_parsesEmptyArray() {
        var result = CommandExecutor.splitJsonArray("[]");
        assertEquals(0, result.size());
    }

    @Test
    void splitJsonArray_parsesNestedObjects() {
        var result = CommandExecutor.splitJsonArray("[{\"x\":1},{\"y\":2}]");
        assertEquals(2, result.size());
        assertEquals("{\"x\":1}", result.get(0));
        assertEquals("{\"y\":2}", result.get(1));
    }

    @Test
    void splitJsonArray_returnsNullInputAsEmptyList() {
        var result = CommandExecutor.splitJsonArray(null);
        assertNotNull(result);
        assertEquals(0, result.size());
    }
}
