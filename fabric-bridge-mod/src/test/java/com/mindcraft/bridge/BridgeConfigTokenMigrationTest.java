package com.mindcraft.bridge;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BridgeConfigTokenMigrationTest {
    @TempDir Path configDir;

    @Test
    void missingLegacyTokenIsPersistedAndSurvivesReload() throws Exception {
        assertStableToken("{\"queueMaxCapacity\":333}");
    }

    @Test
    void blankLegacyTokenIsPersistedAndSurvivesReload() throws Exception {
        assertStableToken("{\"queueMaxCapacity\":333,\"bridgeToken\":\"\"}");
    }

    @Test
    void existingTokenIsKept() throws Exception {
        assertEquals("synthetic-existing-token", assertStableToken(
                "{\"queueMaxCapacity\":333,\"bridgeToken\":\"synthetic-existing-token\"}"));
    }

    private String assertStableToken(String json) throws Exception {
        Path file = configDir.resolve("mindcraft-bridge.json");
        Files.writeString(file, json);
        Field instance = BridgeConfig.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        FabricLoader loader = mock(FabricLoader.class);
        when(loader.getConfigDir()).thenReturn(configDir);
        try (MockedStatic<FabricLoader> factory = mockStatic(FabricLoader.class)) {
            factory.when(FabricLoader::getInstance).thenReturn(loader);
            BridgeConfig.reload();
            String token = BridgeConfig.get().bridgeToken;
            assertNotNull(token);
            assertFalse(token.isBlank());
            assertEquals(333, BridgeConfig.get().queueMaxCapacity);
            assertEquals(token, JsonParser.parseString(Files.readString(file))
                    .getAsJsonObject().get("bridgeToken").getAsString());
            BridgeConfig.reload();
            assertEquals(token, BridgeConfig.get().bridgeToken);
            return token;
        } finally {
            instance.set(null, previous);
        }
    }
}
