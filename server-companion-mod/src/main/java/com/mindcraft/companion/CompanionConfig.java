package com.mindcraft.companion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CompanionConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-server-companion");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CompanionConfig INSTANCE;

    public boolean enabled = true;
    public long broadcastIntervalMs = 1500;
    public boolean sendRoster = true;
    public boolean sendEvents = true;
    public boolean sendFacts = true;
    public List<String> playerAllowlist = new ArrayList<>();

    public static CompanionConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static void reload() {
        INSTANCE = load();
        LOGGER.info("Companion config reloaded");
    }

    private static CompanionConfig load() {
        Path configDir;
        try {
            configDir = FabricLoader.getInstance().getConfigDir();
        } catch (Throwable t) {
            configDir = null;
        }
        if (configDir == null) {
            LOGGER.warn("Fabric config directory unavailable, using defaults");
            return new CompanionConfig();
        }
        Path configPath = configDir.resolve("mindcraft-server-companion.json");

        if (!Files.exists(configPath)) {
            LOGGER.info("No config found at {}, writing defaults", configPath);
            CompanionConfig defaults = new CompanionConfig();
            defaults.save(configPath);
            return defaults;
        }

        try {
            String json = Files.readString(configPath);
            CompanionConfig config = GSON.fromJson(json, CompanionConfig.class);
            LOGGER.info("Loaded config from {}", configPath);
            return config;
        } catch (IOException e) {
            LOGGER.error("Failed to read config, using defaults", e);
            return new CompanionConfig();
        } catch (Exception e) {
            LOGGER.error("Failed to parse config, using defaults", e);
            return new CompanionConfig();
        }
    }

    public boolean save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("mindcraft-server-companion.json");
        return save(configPath);
    }

    private boolean save(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, GSON.toJson(this));
            LOGGER.info("Saved config to {}", path);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
            return false;
        }
    }
}
