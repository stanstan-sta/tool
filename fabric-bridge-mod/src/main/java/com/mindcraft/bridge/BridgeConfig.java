package com.mindcraft.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BridgeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-bridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BridgeConfig INSTANCE;

    // Queue
    public int queueMaxCapacity = 512;
    public int maxRequestBytes = 65536;
    public int maxSurfaceRadius = 16;

    // Timeouts (ms)
    public long clientThreadCallMs = 5000;
    public int workstationSearchRadius = 16;
    public double workstationApproachDistance = 4.75;
    public long workstationApproachTimeoutMs = 30000;
    public long screenOpenTimeoutMs = 3000;
    public long smeltTimeoutMs = 600000;
    public long gotoTimeoutMs = 180000;
    public long mineTimeoutMs = 180000;
    public long sleepTimeoutMs = 60000;
    public long surfaceTimeoutMs = 120000;
    public long exploreTimeoutMs = 120000;
    public long farmTimeoutMs = 120000;
    public long lootTimeoutMs = 60000;
    public long defaultTimeoutMs = 30000;

    // Combat
    public int combatRetreatHp = 10;
    public int combatSearchTimeS = 30;
    public int combatMaxAttempts = 100;

    // Loot
    public int lootContainerSearchRadius = 16;
    public int lootMaxContainersPerScan = 5;

    // Farm
    public boolean farmReplantAfterHarvest = true;
    public int farmCropSearchRadius = 16;

    // Logging
    public String logLevel = "INFO";
    public boolean logToChat = false;
    public boolean logToConsole = true;

    // Experimental actions
    public boolean enableExperimentalActions = false;

    // raw_command policy
    public boolean enableRawCommand = true;
    public java.util.List<String> rawCommandAllowlist = new java.util.ArrayList<>(
        java.util.List.of(
            "#sleep",
            "#goto",
            "#mine",
            "#cancel",
            "#stop",
            "#task smelt"
        )
    );

    // Task settle times (ms)
    public long gotoSettleMs = 300;
    public long mineSettleMs = 3000;
    public long portalSettleMs = 10000;

    public static BridgeConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static void reload() {
        INSTANCE = load();
        LOGGER.info("Configuration reloaded");
    }

    private static BridgeConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("mindcraft-bridge.json");

        if (!Files.exists(configPath)) {
            LOGGER.info("No config file found at {}, using defaults", configPath);
            BridgeConfig defaults = new BridgeConfig();
            defaults.save(configPath);
            return defaults;
        }

        try {
            String json = Files.readString(configPath);
            BridgeConfig config = GSON.fromJson(json, BridgeConfig.class);
            LOGGER.info("Loaded configuration from {}", configPath);
            return config;
        } catch (IOException e) {
            LOGGER.error("Failed to read config file, using defaults", e);
            return new BridgeConfig();
        } catch (Exception e) {
            LOGGER.error("Failed to parse config file, using defaults", e);
            return new BridgeConfig();
        }
    }

    public boolean save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("mindcraft-bridge.json");
        return save(configPath);
    }

    private boolean save(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String json = GSON.toJson(this);
            Files.writeString(path, json);
            LOGGER.info("Saved configuration to {}", path);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
            return false;
        }
    }

    public long getTimeoutForCommand(String command) {
        String lower = command.toLowerCase();
        if (lower.startsWith("#goto ")) return gotoTimeoutMs;
        if (lower.startsWith("#mine ")) return mineTimeoutMs;
        if (lower.equals("#sleep")) return sleepTimeoutMs;
        if (lower.startsWith("#surface") || lower.startsWith("#path")) return surfaceTimeoutMs;
        if (lower.startsWith("#explore") || lower.startsWith("#farm")) return exploreTimeoutMs;
        if (lower.startsWith("#task smelt ")) return smeltTimeoutMs;
        return defaultTimeoutMs;
    }

    public long getSettleForCommand(String command) {
        String lower = command.toLowerCase();
        if ((lower.startsWith("#goto ") || lower.startsWith("#task "))
                && (lower.contains("portal")
                    || lower.contains("nether")
                    || lower.contains("overworld")
                    || lower.contains("end_portal"))) {
            return portalSettleMs;
        }
        if (lower.startsWith("#goto ")) return gotoSettleMs;
        if (lower.startsWith("#mine ")) return mineSettleMs;
        return 0L;
    }
}
